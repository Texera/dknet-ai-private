# Operator Port Result Cache Design

## Objective

Enable **incremental workflow execution** through deterministic cache reuse of operator output ports. Under MVP Assumption III, when users iteratively refine workflows (modify parameters, add/remove operators), the system should:

1. **Reuse cached results** where upstream computation is unchanged
2. **Never recompute a required output port with a cache hit** (forced cache use)
3. **Maintain correctness** via deterministic fingerprinting of upstream sub-DAGs
4. **Preserve physical plan immutability**: skip/execute/cache/fresh are scheduler annotations, not plan mutations

**Key principle (MVP)**: The physical plan remains unchanged. Scheduling is a two-step process in `CostBasedScheduleGenerator`: (1) deterministic requiredness propagation with forced cache use, then (2) original Pasta optimization on the residual fresh-required structure only.

**Future research goals**:

- Extend Pasta scheduler with cost-based cache-vs-recompute decisions
- Develop cost models and pruning heuristics for what/when to cache
- Evaluate speedup on iterative data science workflows
- (Optional) Lifecycle management: eviction, invalidation, garbage collection

## Key Design Decisions

### 1. Physical Plan Immutability

The physical plan is never modified to accommodate caching. Cache decisions are metadata passed to the scheduler via `WorkflowSettings.cachedOutputs` and required output ports.

### 2. Fingerprint-Based Correctness

Cache keys use SHA-256 hashes of canonical upstream sub-DAG representations (operators + schemas + edges + init info). This ensures:

- Deterministic matching: same computation = same fingerprint
- Automatic invalidation: any upstream change = different fingerprint = cache miss
- No explicit dependency tracking needed

### 3. Region Homogeneity Constraint

A region is either fully cached (ToSkip) or fully executed (ToExecute) — no partial execution within a region. This simplifies:

- Scheduler logic (binary cached flag per region)
- Runtime state management (consistent execution mode)
- MVP planning (skip regions are excluded from Pasta planning scope)

### 4. Forced Cache Use (Assumption III)

For any required output port:

- If cache exists, the requirement is satisfied from cache.
- The port is not recomputed for satisfying that requirement.
- There is no per-port or per-region cache-vs-recompute cost decision in MVP.

This includes mixed-output operators: if one required output port is cache miss and another required output port is cache hit, the operator may execute, but consumers of the cache-hit output must still bind to the cached URI.

### 5. Shallow State Hierarchy for Cached Regions

ToSkip regions create lightweight state structures (Workflow → Region → Operator/Link) and store cached metrics at the operator level. No Worker/Channel states are created, so `numWorkers=0` and no worker assignments are emitted.

### 6. Stats Emission via Direct Client Updates

Cached regions emit synthetic `ExecutionStatsUpdate` messages directly via `asyncRPCClient.sendToClient()`, with cached operator metrics (`numWorkers=0`). This reuses existing stats infrastructure without special-casing the frontend.

### 7. Explicit Cache State in Metrics

Cached operators use a dedicated `COMPLETED_FROM_CACHE` state in `WorkflowAggregatedState` protobuf enum. This provides clear visual feedback to users and distinguishes cache-hit completion from normal execution completion.

### 8. Deferred Lifecycle Management

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
- Required output ports (first-class): sink output ports + visible intermediate output ports (`outputPortsNeedingStorage`)

**MVP Scheduling Flow (Assumption III)**:

1. **Deterministic requiredness propagation** (port/edge aware):
   - Seed required outports with required sink + visible ports.
   - For each operator, if any required outport is cache miss, mark operator `Execute`; otherwise `Skip`.
   - For each `Execute` operator, mark all upstream outports on incoming edges as required.
   - Iterate to fixed point.
2. **Classify inputs of executing operators**:
   - `Cache-fed` if upstream required outport has cache hit.
   - `Fresh-required` if upstream required outport has cache miss.
3. **Residual Pasta optimization**:
   - Build residual planning structure containing only `Execute` operators and `Fresh-required` dependencies.
   - Run original Pasta search/regioning/materialization on this residual structure.
   - Keep blocking-edge constraints within the residual scope.
4. **Assemble final full schedule**:
   - Include residual execute regions (`ToExecute`) and skipped regions (`ToSkip`) in one schedule.
   - Regions remain homogeneous (`cached=true/false`).
   - All URI bindings are fixed at planning time.
   - Cache-hit required outputs are marked as reuse-only and are never re-materialized during execution.

**Output**:

- Single schedule over the full workflow with both `ToSkip` and `ToExecute` regions
- Planning-time URI bindings for cache-fed inputs and fresh-required outputs
- Reuse-only output semantics for cache-hit required ports to suppress rematerialization

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
   - **UI**: The graph view displays `-` for cached input counts and for cached output ports that were not materialized; worker counts show `from cache`.
   - **Note**: Cached stats are synthetic (inputs default to 0; non-materialized outputs may be omitted). Do not use them for cost modeling until we add explicit tagging/filtering in `runtime_statistics`.
5. **Planning-time URI bindings**: Downstream operators consume URIs bound during scheduling; no runtime cache-vs-fresh URI decisions
6. **No WorkerAssignmentUpdate**: Cached regions don't send worker assignment events (consistent with numWorkers=0)
7. **Set phase to Completed**: Region lifecycle completes immediately

#### ToExecute Regions (Normal Execution)

**Location**: `PortCompletedHandler` → `PortMaterialized event` → `ExecutionCacheService` → `OperatorPortCacheService` → `OperatorPortCacheDao`

1. **Execute operators**: Normal execution path via worker actors
2. **On output port completion** (`PortCompletedHandler`):
   - Retrieve result URI from `WorkflowExecutionsResource.getResultUriByPhysicalPortId`

- Retrieve tuple count (best-effort via runtime stats from worker metrics)
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

**Forced-cache nuance**:

- If an operator executes due to one required output miss, other required outputs on the same operator that are cache hits are reuse-only.
- Those cache-hit outputs are not freshly materialized in this run, and consumers remain bound to cached URIs.

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

**Cache metadata UI (Implemented)**:

- Left panel "Cache" tab listing workflow cache entries (physical op id, port id, tuple count, source execution id, updated_at, short subdag hash)
- Highlight cache entries usable by the current execution (fingerprint + source execution id match)
- Cache panel toggle to show only entries usable by the current execution
- Cache panel action to clear all cached results (deletes cache entries, result documents, and port result records)
- Output port labels show tuple counts, plus a second line with source execution id for cached outputs
- Output ports show a cached indicator when any cache entry exists for that port
- Editor context menu can evict cache for the selected operator or its upstream operators
- Result URI hidden from the UI

**Cache UX & invalidation (Implemented)**:

- Output ports show a cached indicator when any cache entry exists for that port (no usable/not-usable distinction on the graph)
- Context menu actions: "Clear cache" (selected operator) and "Clear cache up to this operator" (includes disabled operators and the selected operator)
- Cache invalidation on compile: evict cache entries whose fingerprints no longer match the current workflow
- Cache panel shows a notice when auto-invalidation removes entries after a compile
- Cache panel toggle to enable or disable auto invalidation after compile
- Cache panel shows a notice when users manually clear or evict cache entries (panel or context menu)
- Cache panel auto-refreshes when cache entries are upserted or an execution completes
- Compile endpoint accepts HashJoin join types (e.g., "full outer") to avoid 400s during invalidation
- TODO: Use source execution runtime stats for cached operator input/output counts, with fallback to `operator_port_cache.tuple_count` when stats are missing

**Cache usage updates**:

- `CacheUsageUpdateEvent` publishes cached outputs usable by the current execution (fingerprint + source execution id match)
- Frontend uses the event to drive cache entry highlighting and per-port cache labels; matching includes source execution id to avoid treating new upserts as usable
- Cache usage snapshots are re-emitted on websocket connect to keep labels visible after refresh
- `CacheEntryUpdateEvent` is emitted when cached outputs are upserted during execution

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

  /** List cache entries for a workflow (ordered by updated_at desc) */
  def listByWorkflow(
      workflowId: Long,
      limit: Int,
      offset: Int
  ): Seq[OperatorPortCacheRecord]

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

  /** Invalidate all cache entries and cached result artifacts for a workflow */
  def invalidateWorkflowCache(workflowId: WorkflowIdentity): Unit

  /** Future: Cost-aware eviction when storage quota exceeded */
  def evictLowValueEntries(quotaBytes: Long): Unit
}
```

**Key responsibilities**:

- Encapsulates fingerprint computation (calls `FingerprintUtil`)
- Handles `GlobalPortIdentity` ↔ String serialization
- Manages tuple count propagation (best-effort via runtime stats)
- Provides workflow-level abstractions (batch lookup, invalidation + artifact cleanup)

#### WorkflowExecutionsResource (REST API - Optional)

**Location**: `/amber/src/main/scala/org/apache/texera/web/resource/dashboard/user/workflow/WorkflowExecutionsResource.scala`

HTTP endpoints for external access:

- `GET /executions/{workflowId}/cache?limit=<n>&offset=<n>`: List cache entries (result_uri omitted)
- `DELETE /executions/{workflowId}/cache`: Clear all cache entries and delete stored result documents
- `POST /executions/{workflowId}/cache/clear`: POST alternative for environments that block DELETE
- `POST /executions/{workflowId}/cache/evict`: Evict cache entries for specified logical operator IDs
- `POST /executions/{workflowId}/cache/invalidate`: Remove cache entries whose fingerprints do not match the provided logical plan

**Note**: Internal services use `OperatorPortCacheService`, not the REST resource.

### 6. Implemented Components Reference

Phase 1.1 Service/DAO architecture is complete. Key components:

| Component                          | Location                                                                                           | Purpose                                                                                                                 |
| ---------------------------------- | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **OperatorPortCacheDao**     | `/amber/src/main/scala/org/apache/texera/web/dao/OperatorPortCacheDao.scala`                     | Low-level database access using Jooq. Methods:`get()`, `upsert()`, `listByWorkflow()`, `deleteByWorkflow()`     |
| **OperatorPortCacheService** | `/amber/src/main/scala/org/apache/texera/web/service/OperatorPortCacheService.scala`             | High-level cache operations. Methods:`lookupCachedOutputs()`, `upsertCachedOutput()`, `invalidateWorkflowCache()` |
| **ExecutionCacheService**    | `/amber/src/main/scala/org/apache/texera/web/service/ExecutionCacheService.scala`                | Event listener that registers callback for `PortMaterialized` events and bridges to service layer                     |
| **PortMaterialized Event**   | `/amber/src/main/scala/org/apache/texera/amber/engine/architecture/controller/ClientEvent.scala` | Client event emitted when output port completes with URI and tuple count                                                |

**Integration Points**:

- **WorkflowService**: Instantiates `cacheService` at workflow level (shared across executions)
- **WorkflowExecutionService**:
  - Uses `cacheService.lookupCachedOutputs()` at submission time
  - Instantiates `executionCacheService` per execution for cache writes
- **PortCompletedHandler**: Emits `PortMaterialized` event via `sendToClient()` when output ports complete

**Architecture**: Event-based communication follows existing patterns (ExecutionStatsUpdate, WorkerAssignmentUpdate). Engine layer has zero knowledge of web/service layer.

### 7. Testing Strategy

- **Unit tests**: Fingerprint determinism, forced-cache propagation logic, region classification
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
  - `OperatorPortCacheDao`: Low-level database access with get/upsert/listByWorkflow/delete methods
  - `OperatorPortCacheService`: High-level cache operations (lookupCachedOutputs, upsertCachedOutput, invalidateWorkflowCache)
  - `ExecutionCacheService`: Event listener bridging controller events to service layer
  - Event-based communication via `PortMaterialized` event and `client.registerCallback[T]`
- **Fingerprinting**: `FingerprintUtil` implemented with workflow-based specs for deterministic subDAG hashing
- **Submission-time lookup**: `WorkflowExecutionService` uses `OperatorPortCacheService.lookupCachedOutputs()` to compute fingerprints for all physical output ports, queries cache, stores hits in `WorkflowSettings.cachedOutputs`
- **Cache persistence**: `PortCompletedHandler` emits `PortMaterialized` event → `ExecutionCacheService` → `OperatorPortCacheService.upsertCachedOutput()` → `OperatorPortCacheDao.upsert()` (includes fingerprint, URI, tuple count)
- **Scheduler integration**:
  - Implemented behavior (current branch): deterministic requiredness propagation + residual Pasta + full schedule assembly with skip regions excluded from Pasta planning scope
  - Planning bindings are prepared before Pasta and consumed during `createRegions` in search:
    - fresh-required outputs receive fresh URIs,
    - cache-hit required outputs are bound as reuse-only (`materialize=false`),
    - cache-fed inputs are pre-bound to cached URIs.
  - No post-search region re-annotation step; resource allocation remains in Pasta search (`allocateResourcesAndEvaluateCost`) and final execute-region input configs stay as `InputPortConfig`.
- **Runtime execution**: `RegionExecutionCoordinator` branches on `region.cached` flag:
  - ToSkip regions: `completeCachedRegion()` records cached operator metrics (numWorkers=0, processingTime=0) without creating workers, propagates cached URIs downstream
  - ToExecute regions: normal execution path
- **Stats emission**: Cached regions emit `ExecutionStatsUpdate` via direct client updates, maintaining consistency with normal execution lifecycle
- **Controller stats query robustness**:
  - `QueryWorkerStatisticsHandler` now uses optional operator-execution lookup and skips operators not yet initialized.
  - This avoids `None.get` during global stats polling when schedule levels are launched incrementally (including skip-first ordering where some execute regions are still pending initialization).
- **Frontend cache visualization** (Phase 1.2 - Complete):
  - Added `COMPLETED_FROM_CACHE` state to `WorkflowAggregatedState` protobuf enum
  - Added `CompletedFromCache` phase to `RegionExecutionCoordinator` for cached region lifecycle
  - Backend state conversion in `Utils.scala`: `aggregatedStateToString`, `stringToAggregatedState`, `maptoStatusCode`
  - State aggregation in `ExecutionUtils.aggregateStates()` handles `COMPLETED_FROM_CACHE` as terminal state
  - Frontend `OperatorState` enum includes `CompletedFromCache`
  - Operator visualization: blue fill color (`#1890ff`) for cached operators in `joint-ui.service.ts`
  - Port metrics display: cached input counts show `-`, and cached output ports without materialization show `-`
  - Input ports fed by cached sub-operators (e.g., HashJoin build cached, probe executed) report unknown counts and render as `-`
  - Worker count label: cached operators show `from cache` instead of `#workers`
  - Region visualization: blue fill (`rgba(24,144,255,0.3)`) for cached regions in `workflow-editor.component.ts`
  - Region visibility: shared state via `WorkflowActionService.showRegion` ensures correct visibility when regions are created during execution
- **Cache metadata UI** (Phase 1.3 - Complete):
  - `CacheUsageUpdateEvent` publishes cached outputs usable by the current execution (fingerprint + source execution id match)
  - Left panel "Cache" tab lists cache entries (physical op id, port id, tuple count, source execution id, updated_at, short subdag hash)
  - Cache entries highlight when usable by the current execution (fingerprint + source execution id match)
  - Cache panel "Clear cache" action removes cached results and associated result artifacts
- Cache panel can filter to only show entries usable by the current execution
  - Cached output ports show source execution id on a second label line
  - REST: `GET /executions/{wid}/cache` lists cache entries (result URI omitted)
  - Result URI omitted from UI payloads

### Architecture Integration

The cache system integrates with three layers:

1. **Execution Planning Layer**: Cache lookup at workflow submission, fingerprint computation
2. **Scheduler Layer (Pasta)**: Deterministic forced-cache propagation + residual Pasta optimization + full schedule assembly
3. **Runtime Layer**: Short-circuit execution for cached regions, planning-time URI binding consumption, stats emission

## Research Contributions

### 1. Cost Model & Pruning (Future Contribution)

**Goal**: Determine when caching is beneficial and what outputs to cache, beyond MVP Assumption III.

**Components**:

- **Operator cost estimation**: Predict execution cost using historical runtime statistics
- **Cache I/O cost**: Model read/write cost for materialized outputs
- **Pruning heuristics**:
  - Skip caching small outputs (recompute is cheap)
  - Skip terminal operators (no downstream reuse)
  - Skip low-reuse-probability operators
- **Cache-or-recompute decision**: Per-region or per-port comparison (cache read vs recompute)

**Current MVP status**: Disabled for merge target. MVP uses forced cache use when required output ports have cache hits.

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

- [X] Create `OperatorPortCacheDao` with get/upsert/delete methods
  - Extracted Jooq code into dedicated DAO layer
  - Defined `OperatorPortCacheRecord` case class matching database schema
  - Location: `/amber/src/main/scala/org/apache/texera/web/dao/OperatorPortCacheDao.scala`
- [X] Create `OperatorPortCacheService` with high-level methods
  - `lookupCachedOutputs(workflowId, physicalPlan)`: batch lookup at submission
  - `upsertCachedOutput(...)`: cache write on port completion
  - `invalidateWorkflowCache(workflowId)`: manual invalidation
  - Encapsulates fingerprint computation and serialization
  - Location: `/amber/src/main/scala/org/apache/texera/web/service/OperatorPortCacheService.scala`
- [X] Create `ExecutionCacheService` for event handling
  - Registers callback for `PortMaterialized` events
  - Bridges controller events to service layer
  - Location: `/amber/src/main/scala/org/apache/texera/web/service/ExecutionCacheService.scala`
- [X] Add `PortMaterialized` event type
  - Location: `/amber/src/main/scala/org/apache/texera/amber/engine/architecture/controller/ClientEvent.scala`
- [X] Refactor `WorkflowExecutionService.computeCachedOutputs()` to use service
  - Uses `OperatorPortCacheService.lookupCachedOutputs()`
- [X] Refactor `PortCompletedHandler` to emit events
  - Emits `PortMaterialized` event via `sendToClient()` instead of direct service calls
- [X] Instantiate services in `WorkflowService` and `WorkflowExecutionService`
  - `cacheService` created at workflow level
  - `executionCacheService` created per execution
- [ ] Add unit tests for DAO operations
- [X] Add cache listing endpoint in `WorkflowExecutionsResource` (`GET /executions/{wid}/cache`)

#### 1.2 Frontend Cache Visualization ✓ COMPLETE

- [X] Add `COMPLETED_FROM_CACHE` state to `WorkflowAggregatedState` protobuf enum
  - Location: `/amber/src/main/protobuf/.../controlreturns.proto`
- [X] Add `CompletedFromCache` phase to `RegionExecutionCoordinator`
  - Cached regions use this phase instead of `Completed`
  - Location: `/amber/src/main/scala/.../scheduling/RegionExecutionCoordinator.scala`
- [X] Update backend state conversion functions in `Utils.scala`
  - `aggregatedStateToString`: `COMPLETED_FROM_CACHE` → `"CompletedFromCache"`
  - `stringToAggregatedState`: `"completedfromcache"` → `COMPLETED_FROM_CACHE`
  - `maptoStatusCode`: `COMPLETED_FROM_CACHE` → `6`
- [X] Update `ExecutionUtils.aggregateStates()` to handle `COMPLETED_FROM_CACHE`
  - Treated as terminal state alongside `COMPLETED` and `TERMINATED`
- [X] Add `CompletedFromCache` to frontend `OperatorState` enum
  - Location: `/frontend/src/app/workspace/types/execute-workflow.interface.ts`
- [X] Add blue fill color for cached operators in `joint-ui.service.ts`
  - Color: `#1890ff` (Ant Design blue)
- [X] Add blue fill for cached regions in `workflow-editor.component.ts`
  - Color: `rgba(24,144,255,0.3)` (translucent blue)
- [X] Show `-` for cached input counts and cached output ports without materialization
- [X] Replace cached worker count label with `from cache`
- [X] Fix region visibility with shared state via `WorkflowActionService.showRegion`
  - Ensures regions show correctly when user toggles visibility before execution

#### 1.3 Cache Metadata UI ✓ COMPLETE

- [X] Add left panel "Cache" tab listing workflow cache entries (physical op id, port id, tuple count, source execution id, updated_at, short subdag hash)
- [x] Highlight cache entries usable by the current execution (fingerprint + source execution id match)
- [X] Show per-output-port sourceExecutionId on a second output port label line
- [X] Re-emit cache usage snapshots on websocket connect to refresh cache labels after reload
- [X] Keep result URI hidden in the UI

#### 1.4 Cache UX & Invalidation ✓ COMPLETE

- [X] Show cached indicator on output ports for any cache entry
- [X] Add context-menu actions to clear cache for selected operator and upstream operators (includes disabled)
- [X] Invalidate mismatched cache entries on compile (fingerprint comparison)
- [ ] TODO: Use source execution runtime stats for cached operator counts, with tuple-count fallback

#### 1.5 Testing & Validation

- [X] Verify downstream cached URI consumption across all operator types
- [ ] Add integration tests: cache upsert → DB verification
- [ ] Add E2E tests: run → cache → rerun → verify skip
- [X] Clean up state hierarchy for cached regions (confirm shallow hierarchy)
- [X] Verify tuple count accuracy in cache metadata

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
