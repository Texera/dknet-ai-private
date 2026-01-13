# Operator Port Result Cache Design

## Objective

Enable **incremental workflow execution** through cost-aware caching of operator output ports. When users iteratively refine workflows (modify parameters, add/remove operators), the system should:

1. **Reuse cached results** where upstream computation is unchanged
2. **Make cost-aware decisions** comparing cache read cost vs recomputation cost
3. **Maintain correctness** via deterministic fingerprinting of upstream sub-DAGs
4. **Preserve physical plan immutability** — caching is scheduler metadata, not plan modification

**Key principle**: The physical plan remains unchanged; cache-or-recompute decisions are made by the scheduler (Pasta / CostBasedScheduleGenerator) based on cache metadata keyed by a deterministic SHA-256 fingerprint of the upstream sub-DAG.

**Research goals**:
- Extend Pasta scheduler with cost-based caching decisions
- Develop cost models and pruning heuristics for what/when to cache
- Evaluate speedup on iterative data science workflows
- (Optional) Lifecycle management: eviction, invalidation, garbage collection

## Key Design Decisions

### 1. Physical Plan Immutability
The physical plan is never modified to accommodate caching. Cache decisions are metadata passed to the scheduler via `WorkflowSettings.cachedOutputs`. This preserves the integrity of the Pasta scheduling framework.

### 2. Fingerprint-Based Correctness
Cache keys use SHA-256 hashes of canonical upstream sub-DAG representations (operators + schemas + edges + init info). This ensures:
- Deterministic matching: same computation = same fingerprint
- Automatic invalidation: any upstream change = different fingerprint = cache miss
- No explicit dependency tracking needed

### 3. Region Homogeneity Constraint
A region is either fully cached (ToSkip) or fully executed (ToExecute) — no partial execution within a region. This simplifies:
- Scheduler logic (binary cached flag per region)
- Runtime state management (consistent execution mode)
- Cost model (compare full region costs)

### 4. Shallow State Hierarchy for Cached Regions
ToSkip regions create lightweight state structures (Workflow → Region → Operator/Link) and store cached metrics at the operator level. No Worker/Channel states are created, so `numWorkers=0` and no worker assignments are emitted.

### 5. Stats Emission via Direct Client Updates
Cached regions emit synthetic `ExecutionStatsUpdate` messages directly via `asyncRPCClient.sendToClient()`, with cached operator metrics (`numWorkers=0`). This reuses existing stats infrastructure without special-casing the frontend.

### 6. No Explicit Cache Flag in Metrics
Cached execution is inferred from `numWorkers=0` + instant completion, rather than adding a `from_cache` flag to protobuf messages. This minimizes protocol changes.

### 7. Deferred Lifecycle Management
V1 assumes unlimited storage. Eviction, TTL, and garbage collection are research topics for future work, not implementation blockers.

## Data Model
- Table `operator_port_cache` (PK `(workflow_id, global_port_id, subdag_hash)`):
  - `fingerprint_json`: canonical JSON of the upstream sub‑DAG.
  - `subdag_hash`: SHA-256 of `fingerprint_json`.
  - `result_uri`: materialization URI.
  - `tuple_count` (optional), `source_execution_id` (optional).
  - `updated_at`: TIMESTAMPTZ managed by database (DEFAULT now()).
- `global_port_id` uses existing `GlobalPortIdentity` serialization.
- Status: schema + migration added (`sql/updates/cache.sql`).

## Fingerprint
- Utility: `FingerprintUtil.computeSubdagFingerprint(physicalPlan, globalPortId) -> (fingerprintJson, subdagHash)`.
- Canonical payload (sorted):
  - Target port ID.
  - Upstream physical operators with exec init info (proto string) and output schemas (string form).
  - Edges between those operators.
- Hash: SHA-256 of the payload JSON.

## End-to-End Workflow

### 1. Cache Lookup (Submission Time)
**Location**: `WorkflowExecutionService` → `OperatorPortCacheService` → `OperatorPortCacheDao`

- Compile logical workflow to `PhysicalPlan`
- Call `OperatorPortCacheService.lookupCachedOutputs(workflowId, physicalPlan)`:
  - For each materializable output port (internal/external):
    - Compute fingerprint via `FingerprintUtil.computeSubdagFingerprint(physicalPlan, globalPortId)`
    - Query `operator_port_cache` table via DAO by `(workflow_id, global_port_id, subdag_hash)`
  - Return `Map[GlobalPortIdentity, CachedOutput]` with all cache hits
- Convert to serialized form: `Map[String, CachedOutput]` (keyed by serialized `GlobalPortIdentity` to avoid Jackson map key deserialization issues)
- Store in `WorkflowSettings.cachedOutputs` and pass to scheduler

### 2. Scheduler Integration (Pasta / CostBasedScheduleGenerator)
**Location**: `CostBasedScheduleGenerator`

**Inputs**:
- Physical plan (immutable)
- `cachedOutputs` (Δ): map of cache hits from step 1
- Visible ports (☐): ports that must be materialized for user visibility

**Region Classification**:
- **Homogeneity constraint**: A region is either fully cached (ToSkip) or fully executed (ToExecute) — no mixing within a region
- **ToExecute regions**: Contain visible ports without cache hits OR depend on uncached intermediate materializations
- **ToSkip regions**: All required output ports have cache hits AND no visible ports need fresh computation

**Cost Model** (currently simple, needs refinement for research):
- Cached regions: cost = 0
- Executing regions: cost = (# operators × DEFAULT_OPERATOR_COST) + materialization read/write costs
- Cache read/write: small fixed costs (0.5 per port)
- **Future**: Historical stats-based estimation from `runtime_statistics` table

**Output**:
- Schedule with regions marked `cached=true/false`
- Port configs updated with cached URIs for ToSkip regions
- Cached tuple counts reused in port metadata

### 3. Runtime Execution
**Location**: `RegionExecutionCoordinator`, `PortCompletedHandler`

#### ToSkip Regions (Cached)
Entry point: `RegionExecutionCoordinator` constructor branches on `region.cached` flag

**Execution path**:
1. **Skip operator execution**: Call `completeCachedRegion()` immediately
2. **State hierarchy** (shallow):
   - Create: Workflow → Region → Operator/Link states
   - Record cached operator metrics (numWorkers=0)
   - Skip: Worker/Channel states (not needed)
3. **Mark ports completed**: Treat cached operators as completed and record cached URIs
4. **Emit synthetic stats** via `asyncRPCClient.sendToClient(ExecutionStatsUpdate(...))`:
   - `numWorkers = 0`
   - `dataProcessingTime = 0`, `controlProcessingTime = 0`, `idleTime = 0`
   - Input/output tuple counts from cached metadata
5. **Propagate cached URIs**: Downstream operators receive cached `result_uri` for materialized inputs
6. **No WorkerAssignmentUpdate**: Cached regions don't send worker assignment events (consistent with numWorkers=0)
7. **Set phase to Completed**: Region lifecycle completes immediately

#### ToExecute Regions (Normal Execution)
**Location**: `PortCompletedHandler` → `PortMaterialized event` → `ExecutionCacheService` → `OperatorPortCacheService` → `OperatorPortCacheDao`

1. **Execute operators**: Normal execution path via worker actors
2. **On output port completion** (`PortCompletedHandler`):
   - Retrieve result URI from `WorkflowExecutionsResource.getResultUriByPhysicalPortId`
   - Retrieve tuple count (best-effort via `DocumentFactory.openDocument(uri).getCount`)
   - Send `PortMaterialized(portId, resultUri, tupleCount)` event to client via `sendToClient()`
3. **Service layer** (`ExecutionCacheService`):
   - Registered callback via `client.registerCallback[PortMaterialized]` receives event
   - Calls `OperatorPortCacheService.upsertCachedOutput(...)`:
     - Computes fingerprint via `FingerprintUtil.computeSubdagFingerprint(physicalPlan, portId)`
     - Upserts to `operator_port_cache` table via DAO with:
       - `workflow_id`, `global_port_id`, `subdag_hash`
       - `fingerprint_json`, `result_uri`
       - `tuple_count`, `source_execution_id`, timestamps
4. **Normal stats emission**: Real execution metrics sent to client

**Architecture note**: Event-based communication follows existing controller pattern - handler emits events via `sendToClient()`, service layer registers callbacks to handle them. Clean separation: engine layer knows nothing about web/service layer.

### 4. Client-Side State Management
**Location**: `ExecutionStatsService`, `ExecutionStateStore`

**Stats propagation**:
- `ExecutionStatsUpdate` events (from both cached and normal regions) update `ExecutionStatsStore`
- Frontend receives operator metrics via WebSocket subscription
- Cached regions appear as instantly completed operators with 0 workers and 0 processing time

**No structural changes needed**:
- Existing protobuf messages (`OperatorStatistics`) handle cached regions naturally
- No `from_cache` flag required (can infer from numWorkers=0 + instant completion)
- Lifecycle management (eviction, cleanup) deferred to future work

### 5. Service & DAO Architecture

#### OperatorPortCacheDao
**Location**: `/amber/src/main/scala/org/apache/texera/web/dao/OperatorPortCacheDao.scala`

Low-level database access using Jooq:
```scala
class OperatorPortCacheDao(sqlServer: SqlServer) {

  /** Query cache entry by PK (workflow_id, global_port_id, subdag_hash) */
  def get(
      workflowId: Long,
      serializedPortId: String,
      subdagHash: String
  ): Option[OperatorPortCacheRecord]

  /** Upsert cache entry (insert or update on conflict) */
  def upsert(record: OperatorPortCacheRecord): Unit

  /** Delete all cache entries for a workflow */
  def deleteByWorkflow(workflowId: Long): Unit
}
```

#### OperatorPortCacheService
**Location**: `/amber/src/main/scala/org/apache/texera/web/service/OperatorPortCacheService.scala`

High-level cache operations with business logic:
```scala
class OperatorPortCacheService(dao: OperatorPortCacheDao) {

  /** Lookup cached outputs for all ports in a physical plan (submission time) */
  def lookupCachedOutputs(
      workflowId: WorkflowIdentity,
      physicalPlan: PhysicalPlan
  ): Map[GlobalPortIdentity, CachedOutput]

  /** Upsert cache entry when output port completes (runtime) */
  def upsertCachedOutput(
      workflowId: WorkflowIdentity,
      executionId: ExecutionIdentity,
      portId: GlobalPortIdentity,
      physicalPlan: PhysicalPlan,
      resultUri: URI,
      tupleCount: Option[Long]
  ): Unit

  /** Invalidate all cache entries for a workflow (lifecycle management) */
  def invalidateWorkflowCache(workflowId: WorkflowIdentity): Unit

  /** Future: Cost-aware eviction when storage quota exceeded */
  def evictLowValueEntries(quotaBytes: Long): Unit
}
```

**Key responsibilities**:
- Encapsulates fingerprint computation (calls `FingerprintUtil`)
- Handles `GlobalPortIdentity` ↔ String serialization
- Manages tuple count retrieval (best-effort via `DocumentFactory`)
- Provides workflow-level abstractions (batch lookup, invalidation)

#### WorkflowExecutionsResource (REST API - Optional)
**Location**: `/amber/src/main/scala/org/apache/texera/web/resource/dashboard/user/workflow/WorkflowExecutionsResource.scala`

HTTP endpoints for external access (if needed):
- `GET /cache/{workflowId}/{portId}/{hash}`: Manual cache lookup
- `DELETE /cache/{workflowId}`: Manual cache invalidation

**Note**: Internal services use `OperatorPortCacheService`, not the REST resource.

### 6. Implemented Components Reference

Phase 1.1 Service/DAO architecture is complete. Key components:

| Component | Location | Purpose |
|-----------|----------|---------|
| **OperatorPortCacheDao** | `/amber/src/main/scala/org/apache/texera/web/dao/OperatorPortCacheDao.scala` | Low-level database access using Jooq. Methods: `get()`, `upsert()`, `deleteByWorkflow()` |
| **OperatorPortCacheService** | `/amber/src/main/scala/org/apache/texera/web/service/OperatorPortCacheService.scala` | High-level cache operations. Methods: `lookupCachedOutputs()`, `upsertCachedOutput()`, `invalidateWorkflowCache()` |
| **ExecutionCacheService** | `/amber/src/main/scala/org/apache/texera/web/service/ExecutionCacheService.scala` | Event listener that registers callback for `PortMaterialized` events and bridges to service layer |
| **PortMaterialized Event** | `/amber/src/main/scala/org/apache/texera/amber/engine/architecture/controller/ClientEvent.scala` | Client event emitted when output port completes with URI and tuple count |

**Integration Points**:
- **WorkflowService**: Instantiates `cacheService` at workflow level (shared across executions)
- **WorkflowExecutionService**:
  - Uses `cacheService.lookupCachedOutputs()` at submission time
  - Instantiates `executionCacheService` per execution for cache writes
- **PortCompletedHandler**: Emits `PortMaterialized` event via `sendToClient()` when output ports complete

**Architecture**: Event-based communication follows existing patterns (ExecutionStatsUpdate, WorkerAssignmentUpdate). Engine layer has zero knowledge of web/service layer.

### 7. Testing Strategy
- **Unit tests**: Fingerprint determinism, cost model logic, region classification
- **Integration tests**: Cache upsert → DB verification, cache lookup → region marking
- **E2E tests**: Run workflow → populate cache → re-run → verify ToSkip behavior and result correctness
- **Change detection**: Modify operator params → verify fingerprint mismatch → cache miss

## Current Implementation Status

### Architecture Layers

**Clean Architecture (Implemented)**:
```
WorkflowExecutionService ──→ lookupCachedOutputs()
                            ↓
ExecutionCacheService ────→ upsertCachedOutput()     OperatorPortCacheService
    ↑                           ↓                             ↓
    └─ registerCallback() ──────┘                    OperatorPortCacheDao (Jooq)
         (PortMaterialized event)                             ↓
                                                        operator_port_cache table
```

**Event-based communication flow**:
1. `PortCompletedHandler` emits `PortMaterialized` event via `sendToClient()`
2. `ExecutionCacheService` registers callback via `client.registerCallback[PortMaterialized]`
3. Callback invokes `OperatorPortCacheService.upsertCachedOutput()`
4. Service calls `OperatorPortCacheDao.upsert()` for database persistence

**Clean layering**: Engine layer (PortCompletedHandler) has zero knowledge of web/service layer. Event-based pattern matches existing controller communication (ExecutionStatsUpdate, WorkerAssignmentUpdate, etc.).

### Completed Components
- **Schema/migration**: `operator_port_cache` table added (`sql/updates/cache.sql`)
  - Columns: `workflow_id`, `global_port_id`, `subdag_hash` (PK), `fingerprint_json`, `result_uri`, `tuple_count`, `source_execution_id`, `updated_at`
  - Timestamp managed by database (`DEFAULT now()`)
- **Service/DAO architecture** (Phase 1.1 - Complete):
  - `OperatorPortCacheDao`: Low-level database access with get/upsert/delete methods
  - `OperatorPortCacheService`: High-level cache operations (lookupCachedOutputs, upsertCachedOutput, invalidateWorkflowCache)
  - `ExecutionCacheService`: Event listener bridging controller events to service layer
  - Event-based communication via `PortMaterialized` event and `client.registerCallback[T]`
- **Fingerprinting**: `FingerprintUtil` implemented with workflow-based specs for deterministic subDAG hashing
- **Submission-time lookup**: `WorkflowExecutionService` uses `OperatorPortCacheService.lookupCachedOutputs()` to compute fingerprints for all physical output ports, queries cache, stores hits in `WorkflowSettings.cachedOutputs`
- **Cache persistence**: `PortCompletedHandler` emits `PortMaterialized` event → `ExecutionCacheService` → `OperatorPortCacheService.upsertCachedOutput()` → `OperatorPortCacheDao.upsert()` (includes fingerprint, URI, tuple count)
- **Scheduler integration**: `CostBasedScheduleGenerator` marks regions cached when all required outputs have hits, reuses cached URIs in port configs
- **Runtime execution**: `RegionExecutionCoordinator` branches on `region.cached` flag:
  - ToSkip regions: `completeCachedRegion()` records cached operator metrics (numWorkers=0, processingTime=0) without creating workers, propagates cached URIs downstream
  - ToExecute regions: normal execution path
- **Stats emission**: Cached regions emit `ExecutionStatsUpdate` via direct client updates, maintaining consistency with normal execution lifecycle

### Architecture Integration
The cache system integrates with three layers:
1. **Execution Planning Layer**: Cache lookup at workflow submission, fingerprint computation
2. **Scheduler Layer (Pasta)**: Cost-based cache-or-recompute decisions, region classification
3. **Runtime Layer**: Short-circuit execution for cached regions, state management, stats emission

## Research Contributions

### 1. Cost Model & Pruning (Primary Contribution)
**Goal**: Determine when caching is beneficial and what outputs to cache.

**Components**:
- **Operator cost estimation**: Predict execution cost using historical runtime statistics
- **Cache I/O cost**: Model read/write cost for materialized outputs
- **Pruning heuristics**:
  - Skip caching small outputs (recompute is cheap)
  - Skip terminal operators (no downstream reuse)
  - Skip low-reuse-probability operators
- **Cache-or-recompute decision**: Per-region cost comparison (cache read vs recompute)

**Current status**: Simple cost model (cached=0, execute>0). Need to develop historical stats-based estimation.

**Data source**: `runtime_statistics` table already captures execution metrics (data/control processing time, tuple counts, worker counts per operator).

### 2. Result Lifecycle Management (Secondary Contribution)
**Goal**: Maintain cache correctness and efficiency over time.

**Components**:
- **Cache eviction**: When storage is limited, prioritize keeping high recompute-cost/storage-cost ratio entries
- **Invalidation**: Fingerprint-based (already handles operator param changes)
- **Source change detection**: Track external data source versions (deferred - hard problem)
- **Garbage collection**: Clean up cache entries for deleted workflows/executions

**Current status**: Assumed unlimited storage. Lifecycle management tabled for initial implementation.

**Future considerations**:
- Cost-aware eviction policies (LRU/LFU variants)
- Source versioning for common sources (files, databases)
- Cross-workflow cache sharing (global fingerprint registry)

## Evaluation Plan

### Benchmark Workloads
1. **Linear pipeline**: A → B → C → D (sequential operators)
2. **Diamond pattern**: A → B, A → C, B+C → D (shared prefix)
3. **Multiple branches**: Complex DAGs with shared subgraphs
4. **UDF-heavy workflows**: Expensive computation (Python/R operators)

### Metrics
- **Execution time**: Full execution vs cached re-execution
- **Cache overhead**: Fingerprint computation + DB lookup latency
- **Storage cost**: Cache entry sizes across workload types
- **Hit rate**: Percentage of regions served from cache

### Experiments
1. **Baseline**: Execute workflow without caching
2. **Cold cache**: First execution (populate cache, measure overhead)
3. **Warm cache**: Re-execution (all hits, measure speedup)
4. **Partial invalidation**: Modify one operator, re-execute (measure partial hit benefit)
5. **Scalability**: Vary workflow size (10, 50, 100, 200 operators)

### Comparison Baselines
- Spark RDD persistence (in-memory, no cross-execution)
- Manual materialization (user-defined intermediate saves)
- No caching (full re-execution)

## Next Steps

### Phase 1: Complete Prototype (Engineering)

#### 1.1 Refactor to Service/DAO Architecture ✓ COMPLETE
- [x] Create `OperatorPortCacheDao` with get/upsert/delete methods
  - Extracted Jooq code into dedicated DAO layer
  - Defined `OperatorPortCacheRecord` case class matching database schema
  - Location: `/amber/src/main/scala/org/apache/texera/web/dao/OperatorPortCacheDao.scala`
- [x] Create `OperatorPortCacheService` with high-level methods
  - `lookupCachedOutputs(workflowId, physicalPlan)`: batch lookup at submission
  - `upsertCachedOutput(...)`: cache write on port completion
  - `invalidateWorkflowCache(workflowId)`: manual invalidation
  - Encapsulates fingerprint computation and serialization
  - Location: `/amber/src/main/scala/org/apache/texera/web/service/OperatorPortCacheService.scala`
- [x] Create `ExecutionCacheService` for event handling
  - Registers callback for `PortMaterialized` events
  - Bridges controller events to service layer
  - Location: `/amber/src/main/scala/org/apache/texera/web/service/ExecutionCacheService.scala`
- [x] Add `PortMaterialized` event type
  - Location: `/amber/src/main/scala/org/apache/texera/amber/engine/architecture/controller/ClientEvent.scala`
- [x] Refactor `WorkflowExecutionService.computeCachedOutputs()` to use service
  - Uses `OperatorPortCacheService.lookupCachedOutputs()`
- [x] Refactor `PortCompletedHandler` to emit events
  - Emits `PortMaterialized` event via `sendToClient()` instead of direct service calls
- [x] Instantiate services in `WorkflowService` and `WorkflowExecutionService`
  - `cacheService` created at workflow level
  - `executionCacheService` created per execution
- [ ] Add unit tests for DAO operations
- [ ] (Optional) Add REST endpoints in `WorkflowExecutionsResource` that delegate to service

#### 1.2 Testing & Validation
- [ ] Verify downstream cached URI consumption across all operator types
- [ ] Add integration tests: cache upsert → DB verification
- [ ] Add E2E tests: run → cache → rerun → verify skip
- [ ] Clean up state hierarchy for cached regions (confirm shallow hierarchy)
- [ ] Verify tuple count accuracy in cache metadata

### Phase 2: Cost Model Development (Research)
- [ ] Analyze historical runtime_statistics data for cost patterns
- [ ] Implement cost estimation for operator execution (based on historical stats)
- [ ] Implement cache I/O cost model (storage backend dependent)
- [ ] Develop pruning heuristics (size thresholds, reuse patterns)
- [ ] Integrate cost model with `DefaultCostEstimator`
- [ ] Evaluate cache decisions: cost-based vs always-cache vs never-cache

### Phase 3: Lifecycle Management (Research - Optional)
- [ ] Design cost-aware eviction policy
- [ ] Implement storage quota management
- [ ] Add garbage collection for deleted workflows
- [ ] Evaluate eviction policy effectiveness

### Phase 4: Evaluation & Publication
- [ ] Design and implement benchmark workloads
- [ ] Run experiments: measure speedup, overhead, hit rates
- [ ] Scalability analysis (varying workflow sizes)
- [ ] Write research paper (target: SIGMOD/VLDB or workshop)

## Open Research Questions
1. **Cost model accuracy**: How well can historical stats predict future execution costs? Does operator heterogeneity require per-operator-type models?
2. **Reuse patterns**: Can we predict which outputs will be reused based on user interaction patterns?
3. **Source change detection**: For external data sources (files, databases), how to efficiently detect changes without explicit versioning?
4. **Cross-workflow sharing**: Is global cache sharing (same subDAG across workflows) worth the complexity?
5. **Incremental maintenance**: When source data changes slightly, can we update cache incrementally vs full invalidation?

## Publication Strategy
**Primary target**: Cost-aware caching framework for incremental workflow execution

**Minimum viable contributions** (Workshop/Demo paper):
- Pasta extension for cache-aware scheduling
- Fingerprint-based correctness
- Working prototype with evaluation

**Full paper contributions** (SIGMOD/VLDB):
- Above + cost model with formal problem definition
- Cost-aware eviction (lifecycle management)
- Comprehensive evaluation with multiple baselines

**Alternative positioning**:
- Demo paper: Focus on Texera integration and user experience
- Industrial track: Deployment story with real user workloads
