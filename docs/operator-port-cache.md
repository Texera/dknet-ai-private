# Operator Port Result Cache Design

## Objective
Reuse previously materialized operator output ports without modifying the physical plan. The physical plan remains immutable; reuse decisions are made by the scheduler (Pasta / CostBasedScheduleGenerator) based on cache metadata keyed by a deterministic fingerprint of the upstream sub‑DAG.

## Data Model
- Table `operator_port_cache` (PK `(workflow_id, global_port_id, subdag_hash)`):
  - `fingerprint_json`: canonical JSON of the upstream sub‑DAG.
  - `subdag_hash`: SHA-256 of `fingerprint_json`.
  - `result_uri`: materialization URI.
  - `tuple_count` (optional), `source_execution_id` (optional), timestamps.
- `global_port_id` uses existing `GlobalPortIdentity` serialization.
- Status: schema + migration added (`sql/texera_ddl.sql`, `sql/updates/16.sql`).

## Fingerprint
- Utility: `FingerprintUtil.computeSubdagFingerprint(physicalPlan, globalPortId) -> (fingerprintJson, subdagHash)`.
- Canonical payload (sorted):
  - Target port ID.
  - Upstream physical operators with exec init info (proto string) and output schemas (string form).
  - Edges between those operators.
- Hash: SHA-256 of the payload JSON.

## End-to-End Workflow
1) **Lookup before execution (mark Δ ports)**
   - Compile to `PhysicalPlan`.
   - For each materializable output port (internal/external), compute fingerprint.
   - Query `operator_port_cache` by `(workflow_id, global_port_id, subdag_hash)`; collect hits into `cachedOutputs`.
   - `WorkflowSettings` carries `cachedOutputs` keyed by serialized `GlobalPortIdentity` to avoid custom map key deserialization.

2) **Scheduler integration (Pasta)**
   - Inputs: physical plan, `cachedOutputs` (Δ), visible ports (☐).
   - Classify regions: `must-execute` if they contain visible ports without cache or depend on uncached materializations; remaining regions are `cached`.
   - Cost model: cached regions cost 0; executing operators >0; materialization reads/writes small fixed costs.
   - Schedule marks cached vs must-execute; runtime skips cached regions and uses cached URIs for materialized reads.

3) **Runtime behavior**
   - Cached regions: skip operator execution; mark operators completed; ensure downstream readers use cached `result_uri`.
   - Must-execute regions: run normally; on output port completion, compute fingerprint and upsert `operator_port_cache` with hash/fingerprint/URI (tuple count if available).

4) **API/Helpers**
   - `WorkflowExecutionsResource`: `getResultUriByPhysicalPortId`, `upsertOperatorPortCache`.
   - Optional cache DAO/service wrapper for cleaner calls.

5) **Testing**
   - Fingerprint determinism/change detection.
   - Cache lookup integration (insert + retrieve by hash).
   - Region classification tests for Δ/☐ combinations.
   - End-to-end: run → populate cache → rerun → verify cached regions are skipped and results served from cache.

## Current Progress (checkpoint)
- Schema/migration added.
- `FingerprintUtil` implemented and covered with workflow-based specs.
- Submission-time cache lookup wired: `WorkflowExecutionService` fingerprints all physical output ports, queries cache, and stores hits in `WorkflowSettings.cachedOutputs` (keyed by serialized `GlobalPortIdentity`).
- Cache upsert on output port completion in `PortCompletedHandler` (guarded on plan + URI; tuple count best-effort).
- `WorkflowExecutionsResource` exposes lookup/upsert helpers (`getResultUriByPhysicalPortId`, `getOperatorPortCache`, `upsertOperatorPortCache`); cache maps currently use stringified port IDs to avoid Jackson map key serde issues.
- Scheduler/runtime path now reuses cached materializations: `CostBasedScheduleGenerator` marks regions cached when all required outputs have cache hits, reuses cached URIs (and cached tuple counts) in port configs, and cached regions emit stats with cached counts; `WorkflowExecutionCoordinator`/`RegionExecutionCoordinator` short-circuit cached regions, mark ports/workers completed, emit stats, and propagate cached URIs downstream. Completion notification remains aligned with the normal lifecycle.

## Next Actions
- Refine region classification/cost model (Δ/☐ rules) and ensure cached vs must-execute decisions align with visibility/materialization needs.
- Tighten runtime semantics: double-check downstream consumption of cached URIs and UI exposure for visible ports; consider marking worker/region states for observability.
- Refine cache upsert if needed (tuple count accuracy, avoid duplicate writes).
- Add integration and end-to-end tests for cache lookup/reuse paths and scheduler decisions.
