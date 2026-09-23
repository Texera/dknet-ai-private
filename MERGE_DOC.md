# Merging rodeo-pipeline `staging` into dknet-ai-private `feat/alphafold3`

Written for whoever deploys this branch to a server and debugs it there. It says what changed,
what is most likely to break, and what to check first.

Merge commit: **`eeec4d5c9`**. The branch is 77 commits ahead of the previous
`origin/feat/alphafold3` (`ad85c0815`).

---

## 1. Where the two sides came from

Both forks descend from apache/texera **`a310473663c5e34b20214de252538a61fc47b8c0`**, which is
their exact `git merge-base`, so this was a real merge and not a replay of patches.

| | |
| --- | --- |
| **Ours** — `dknet-ai-private/feat/alphafold3` @ `ad85c0815` | operator-port result cache, CloudMapper/CloudBioMapper operator, StarSolo "cluster" pages, multi-agent chat assistant, GPU selection UI, dataset/file-selection UI, user permissions, a heavily customised Helm chart. Despite the branch name it contained **no AlphaFold code**. |
| **Theirs** — `rodeo-pipeline/staging` @ `f633af398` | public computing units + fair-share run queue, dataset/model mounting, curated computing-unit images + admin page + pre-pull, the AlphaFold 3 demo, a local-minikube overlay — **plus 19 apache/texera main commits** this fork did not have. |

So this is an upstream sync as well as a feature port. The apache commits that come with it and
are most visible: Form View on by default (#8528), `@angular/core` 21.2.20 (#8494), warehouse
dashboard (#8005), notebook→workflow migration (#8544), operator state/statistics split (#8301),
curated-image admin page (#8518), per-node image pre-pull (#8485).

The merge left **12 conflicted files / 21 hunks**. Most were additive; five needed real
integration and are described in §5.

---

## 2. Database — read this before deploying

### 2.1 The chart does not migrate anything

`bin/k8s/templates/base/postgresql/postgresql-init-script-config.yaml` renders an `init.sh` that
loads `sql/texera_ddl.sql` (plus the lakefs/iceberg/lakekeeper files). The Bitnami postgresql
subchart runs it from `/docker-entrypoint-initdb.d`, which **only executes on a first init of an
empty data directory**. There is no Liquibase job, no migration Job, no initContainer.

**An existing server's database will therefore not pick up this branch's schema change on its
own.** You have to apply it by hand.

### 2.2 What the schema gains

One change, from the public-computing-unit feature:

```sql
SET search_path TO texera_db;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'computing_unit_access_scope_enum') THEN
    CREATE TYPE texera_db.computing_unit_access_scope_enum AS ENUM ('PRIVATE', 'PUBLIC');
  END IF;
END $$;

ALTER TABLE texera_db.workflow_computing_unit
  ADD COLUMN IF NOT EXISTS access_scope texera_db.computing_unit_access_scope_enum
      NOT NULL DEFAULT 'PRIVATE';

-- Two live public units may not share a name. Partial: terminated units keep their names,
-- and private units are namespaced by their owner rather than globally.
CREATE UNIQUE INDEX IF NOT EXISTS ux_wcu_public_name
  ON texera_db.workflow_computing_unit (name)
  WHERE access_scope = 'PUBLIC' AND terminate_time IS NULL;
```

The enum values are upper case deliberately: jOOQ turns an enum value into a Java identifier, and
`private`/`public` are Java keywords, which it would mangle.

Existing rows default to `PRIVATE`, so nothing changes behaviourally until an admin creates a
public unit.

### 2.3 The migration-numbering trap

Both forks independently added a `sql/updates/51.sql`:

- **This fork's** 51 added `user.permission JSONB` and was **never registered** in
  `sql/changelog.xml` — which is exactly why the two collided.
- **Staging's** 51 is the public-CU migration above, and *is* registered.

Resolution: staging's file keeps **51**, this fork's `user.permission` migration became
**`sql/updates/52.sql`**, and both are now registered in `sql/changelog.xml` as changeSets 51 and 52.

> **Check before running Liquibase against an existing database.** If that database has a
> `public.databasechangelog` row for id `51` — recorded from this fork's old, unregistered
> migration by `bin/local-dev.sh`'s seeding path — Liquibase will consider 51 applied and
> **silently skip the public-CU migration**, leaving `access_scope` absent. Confirm with:
>
> ```sql
> SELECT id, author, dateexecuted FROM public.databasechangelog WHERE id IN ('51','52');
> ```
>
> If a stale 51 is there, apply §2.2 by hand and insert a 52 row rather than letting Liquibase
> decide. On a Helm-deployed server this is moot — nothing runs Liquibase there.

### 2.4 `sql/updates/cluster.sql` — still not applied by anything

Unchanged by this merge, but it will bite you, so it is recorded here:

Nothing in the repo applies it — not `changelog.xml`, not the chart's init script, not CI. Yet
`amber`'s `ClusterResource` / `ClusterUtils` / `ClusterCallbackResource` import
`jooq.generated.enums.ClusterStatus`, `tables.daos.ClusterDao` and `tables.pojos.Cluster`.

- **At build time**: without it, `sbt DAO/jooqGenerate` produces no cluster classes and **amber
  does not compile**.
- **At run time**: without it, `/api/cluster` returns 500 and the CloudBioMapper Clusters page
  breaks.

```bash
psql -d texera_db -c "SET search_path TO texera_db;" -f sql/updates/cluster.sql
```

(`sql/updates/cache.sql` is harmless — `operator_port_cache` is already in `texera_ddl.sql`.)

### 2.5 jOOQ codegen needs the new column

`common/dao` generates its sources at build time from a **live database**
(`common/dao/build.sbt`, `jooqGenerate`); the generated tree is gitignored. Build against a
database that already has §2.2 applied, or `access_scope` will be missing from the generated
classes and `ComputingUnitManagingResource` will not compile.

---

## 3. Configuration and chart changes

### 3.1 New environment variables

| Variable | Default in `values.yaml` | What it does |
| --- | --- | --- |
| `COMPUTING_UNIT_PUBLIC_ENABLED` | `"true"` | Turns on public units **and** the queue. Nothing else queues, so turning this off removes queuing entirely. |
| `COMPUTING_UNIT_PUBLIC_MAX_RUN_SECONDS` | `"0"` | Watchdog: how long one run may hold a public unit before the queue reclaims it. `0` disables. The run is never killed, the unit is only handed on. |

**Both must also reach each computing unit's own pod**, because a unit decides for itself whether
to queue. `ComputingUnitManagingResource.optionalComputingUnitEnvNames` forwards them. If a unit
does not see the flag it reads it as off and runs everything immediately — which on a public unit
is the bug the feature exists to prevent.

The config keys behind them live in `common/config/src/main/resources/computing-unit.conf`
(`computing-unit.public.enabled`, `computing-unit.public.max-run-seconds`) and are published to
the browser by `config-service` as `publicComputingUnitEnabled`.

### 3.2 New and changed chart values

| Key | Default | Note |
| --- | --- | --- |
| `workflowComputingUnitPool.imageTag` | `""` | **New, added by this merge.** Empty means "use `texera.imageTag`". Lets computing units run a different engine image tag from the rest of the deployment. Read by both the manager's `KUBERNETES_IMAGE_NAME` and the pool's pre-pull DaemonSet. |
| `cloudmapper.enabled` | `false` | **New.** The CloudMapper templates used to be ungated plain YAML. See §6.2. |
| `litellm.databaseName` | `texera_litellm` | **New.** Was only in `values-development.yaml`; without it the postgres init script rendered a bare `CREATE DATABASE`. |
| `pythonLanguageServer.imagePullPolicy` | `IfNotPresent` | **New.** The pod spec previously carried an empty `imagePullPolicy`. |
| `workflowComputingUnitPool.maxRequestedResources.gpuResources` | `{}` | **New here.** The resource-quota template ranges over it but it was only defined under `jupyterPool`, so the per-model GPU cap silently never applied. |
| `curatedImages.enabled` | `true` | From staging. Curated images + validation jobs. |
| `curatedImages.prepull.enabled` | `true` | From staging. One DaemonSet per ready image, per node. **Costs node disk** — each node holds each ready image. |
| `mounter.enabled` | `false` | From the base. Must be `true` for model/dataset mounting. See §3.4. |
| `rustfs.extraEnv` `RUSTFS_CORS_ALLOWED_ORIGINS: "*"` | — | From apache #8562, so browsers can follow presigned URLs. Review before production. |

### 3.3 RBAC change — the CU manager can now manage DaemonSets

`workflow-computing-unit-manager-service-account.yaml` gains, **scoped to the pool namespace**:

```yaml
- apiGroups: ["apps"]
  resources: ["daemonsets"]
  verbs: ["get","list","watch","create","update","patch","delete"]
```

Needed because one pre-pull DaemonSet is created per ready curated image as images are registered,
so the chart cannot declare them. Deliberately **not** granted in the release namespace, which
holds the privileged mounter. If your cluster applies its own RBAC policy, this is the change most
likely to be rejected — the symptom is curated images going READY but never pre-pulling.

### 3.4 Mounting prerequisites

Model/dataset mounting needs `mounter.enabled: true`, which gives every computing-unit pod a
**hostPath volume**. The `baseline` and `restricted` Pod Security Standards forbid that: on a
cluster enforcing either on the pool namespace, **every CU pod will be rejected**. Check your
namespace's PSS labels before enabling.

Also required, and set by the chart when the mounter is on: `TEXERA_MOUNT_IN_POD_ROOT`
(`/mnt/texera-mounts`), `KUBERNETES_MOUNTER_HOST_ROOT` (`/var/lib/texera-mounts`),
`KUBERNETES_MOUNTER_PORT` (`8100`), `ACCESS_CONTROL_SERVICE_URL` fully qualified, and
`FILE_SERVICE_URL` as scheme+authority only. The node needs `/dev/fuse`.

### 3.5 metrics-server is now load-bearing

Creating a computing unit reads pod metrics. With no metrics API, `/computing-unit/create` fails
with a 500 from `metrics.k8s.io` "Not Found". Ensure either the chart's `metrics-server` or the
cluster's own is running. (Observed during local verification.)

### 3.6 Sidebar tabs come from the database, not from env

`GUI_TABS_*` only **seed** rows at first init. On an existing database, change them through the
product's own API:

```
PUT /api/config/settings/<key>   {"value":"true"}
```

Relevant keys: `models_enabled`, `model_enabled` (both off by default; the AlphaFold demo needs
them, since it delivers its sequence database as a mounted model).

---

## 4. What's new, operationally

### 4.1 Public computing units + fair-share queue

- Created by an admin at **`POST /api/computing-unit/admin/public`** (`@RolesAllowed("ADMIN")`),
  same body as `/create`. Scope is the *endpoint*, never a request field, so a forged body cannot
  produce a public unit. In the UI it is a checkbox in the ordinary create dialog, shown only when
  `publicComputingUnitEnabled` **and** the user is an admin.
- Access is **implicit**: `ComputingUnitAccess` grants `WRITE` on a live public unit to every
  authenticated user, with no `computing_unit_user_access` row. A *terminated* public unit grants
  nothing.
- The queue lives **in the computing unit's own process**, in memory, one per cuid — not in the web
  server. It is authoritative without coordination because the access-control service rewrites Host
  to `workflow_computing_unit.uri`, so every websocket for a cuid reaches exactly one pod. Nothing
  is persisted: on a restart the in-flight executions are gone anyway, so a stored queue would only
  be a stale one. **If a public unit is ever given more than one replica this breaks**, and the
  queue has to move to the database (`FOR UPDATE SKIP LOCKED` over a queue table). This is why
  `COMPUTING_UNIT_PUBLIC_ENABLED` must reach the unit's pod (§3.1) and not just the web server.
- A public unit's pod deliberately carries **no** `ENV_USER_JWT_TOKEN`. Each run supplies its own
  identity via `RunIdentity` / `UserTokenProvider`; absent rather than blank, so a unit missing the
  per-run identity fails to read rather than silently reading as the admin.
- `RunIdentity.setCurrentUser` is a single `@volatile` value. Documented caveat: **do not run more
  than one public *local* unit in one JVM.**

### 4.2 Curated computing-unit images

- Admin page at **`/admin/cu-image`**; API at `/api/cu-image`.
- Registration runs a Kubernetes **Job** `cu-image-check-<iid>-<attempt>` in the pool namespace
  using `quay.io/skopeo/stable:v1.16.1`. It resolves the digest, requires the start command to
  contain `computing-unit-master`, and requires a non-root `USER`. The image is then pinned to the
  digest that passed.
- **A locally built image cannot be registered.** Validation does `skopeo inspect docker://<ref>`
  from inside the cluster, and a curated unit runs `repo@sha256:…`, which kubelet cannot satisfy
  from a local tag. There is no `--tls-verify=false`, so a plain-HTTP in-cluster registry fails too.
  Push to a registry the cluster can reach over HTTPS.
- For an engine image you cannot push, use `workflowComputingUnitPool.imageTag` (§3.2) instead —
  that path is a tag, not a digest.

### 4.3 Model/dataset mounting from a Python UDF

A UDF declares `self.UiParameter("NAME", AttributeType.STRING, value=Resource.MODEL)`; the property
panel then renders a picker instead of a text box, and the UDF receives the **local directory the
chosen version is mounted at**. The engine mounts the LakeFS repository into the unit rather than
copying it. A model is **staged until a version is committed** — nothing can mount it before then.

### 4.4 AlphaFold 3 demo

`bin/demo/alphafold3/`. Image: `bin/dockerfiles/computing-unit-alphafold3.dockerfile`, built
**FROM this fork's engine image**; it installs Python 3.12 alongside the system 3.10 and points
`UDF_PYTHON_PATH` at it.

| UDF | Runs without a GPU? |
| --- | --- |
| `generate_sequences.py` | yes — three hard-coded sequences |
| `msa_search.py` | yes — CPU jackhmmer against a mounted model |
| `predict_structure.py` | **no** — gates on `nvidia-smi -L`, needs `af3.bin` + ~630 GB of databases |
| `fold_complex.py` | **no** — same |

---

## 5. Conflict resolutions that changed behaviour — test these

Eight conflicts were mechanical "keep both". These five were not, and are where a regression would
most plausibly hide.

### 5.1 LakeFS admin credentials are now withheld from public units
`ComputingUnitManagingResource`. This fork hands a unit the LakeFS admin username/password so a CU
can read files in dev mode. Staging replaced the baked-in user token with `userIdentityEnv`, empty
for a public unit. Keeping both as-written would have handed every public unit the admin
credentials — every user's run would read every dataset as the creating admin, defeating the
per-run token. The credentials are now gated on scope.

**Test:** a *private* unit must still read datasets (the dev-mode path this fork depends on); a
*public* unit must read only what the running user may read.

### 5.2 Workflow disposal releases the unit before clearing artifacts
`WorkflowService`. Staging's hook released the computing unit and cleared the *latest* execution;
this fork's cleared *all* executions with a `Seq` signature and also invalidates cache keyed by
source execution. Merged so the unit is released **first** — it must happen even if clearing throws
— then every execution's artifacts are cleared.

**Test:** abandoning a run must not wedge a public unit's queue; cached results must still be
invalidated on disposal.

### 5.3 Operator state is now passed to statistics rendering
`joint-ui.service.ts`. Apache #8301 removed `operatorState` from `OperatorStatistics`, and this
fork read it there for cache rendering. `changeOperatorStatistics` now takes the state as its own
argument, and `workflow-editor.component.ts` passes it from the state stream. State rendering stays
with `changeOperatorState`.

**Test:** operator colours during a run; the "from cache" worker label and `-` port counts on a
cached operator.

### 5.4 Computing-unit creation signature
`workflow-computing-unit-managing.service.ts` now carries this fork's `gpuModel` alongside
staging's `iid` and `isPublic`. The auto-merge had left `createLocalComputingUnit` passing one
`undefined` too few, so **`isPublic` landed on the `iid` parameter** — fixed. `getAvailableGpuModels`
was also nearly deleted by the conflict and was restored.

**Test:** create a local unit, a kubernetes unit, a unit with a GPU model, a unit from a curated
image, and a public unit.

### 5.5 `cacheService` is lazy; its teardown is null-checked
`WorkflowService.cacheService` was an eager `val` calling `SqlServer.getInstance()`, so *constructing*
a WorkflowService required a database — and the websocket resource constructs one per session. It is
now `lazy`. Separately, `WorkflowExecutionService.unsubscribeAll` called
`executionCacheService.unsubscribeAll()` under a guard that only checked `client != null`, so
teardown could throw and skip the reconfiguration service, leaking its subscriptions. Now
null-checked.

**Test:** open and close many workflow sessions; confirm no subscription leak and no error on teardown.

---

## 6. Known issues — do not chase these as merge regressions

### 6.1 Pre-existing breakage on this fork, measured before the merge
- **`sbt Test/compile` did not compile.** Production signatures changed without updating the specs
  that construct them (`WorkflowExecutionManager` / `RegionExecutionManager` gained `executionId`,
  `WorkflowExecutionService` gained `cacheService`, `KubernetesClient.createPod` gained `uid`), and
  `FingerprintUtilSpec` used the pre-rename `org.apache.amber.*` packages. **Fixed on this branch.**
- **`npx tsc --noEmit` reports 49 errors**, all in `*.spec.ts` and `stub-*` files, because this
  fork's "revert non-agent UI features" commit deleted members the upstream specs still test
  (`ShareAccessComponent.hasWriteAccess` / `verifyRevokeAccess` / `changeAccessLevel`,
  `ListItemComponent.renderedDescription`, `SettingsComponent.updateExecutionMode`) and added
  interface members without updating stubs (`SidebarTabs.cluster_enabled`,
  `IOperatorMetadataService.getAvailableTexeraOperatorsAndDescriptions`).
  **These block `ng test` entirely — before and after the merge.** `yarn build` is unaffected,
  because those files are outside the production build graph. **Still open.**
- `frontend/src/app/workspace/component/file-selection/file-selection.component.ts` imports a module
  that does not exist (`common/type/dataset-file`). Nothing references the component, so the build
  never compiles it. **Still open.**

The merge itself introduced **exactly one** new type error, measured by diffing per-file error sets
against the pre-merge baseline. It was fixed.

### 6.2 Fixed here, but worth knowing why
- `gateway-routes.yaml` declared `<release>-agent-service-route` **twice**, both gated on
  `agentService.enabled`. Two objects under one name makes `helm install` fail with `AlreadyExists`
  as soon as agent-service is on. The templated one was kept.
- The CloudMapper templates were ungated, so every cluster got a Deployment wanting AWS
  credentials, an SSH key and a `local-storage` class. Now behind `cloudmapper.enabled: false`.
  **If your server runs CloudMapper, you must now set it to `true`.**

### 6.3 Environment, not code
- `texera/pylsp:latest` on Docker Hub is a wrong-architecture publish — `exec format error` on
  amd64. Affects editor autocomplete only. Build it from `bin/pylsp/` if you need it.
- A failed `POST /computing-unit/create` can leave an **orphaned pod** whose DB row rolled back
  (seen when the metrics API was absent). Worth a sweep of the pool namespace after failures.

---

## 7. Build prerequisites on the server

1. **JDK 17.** jooq-codegen is class-file 61; Java 11 dies with `UnsupportedClassVersionError`.
2. **A live Postgres for `sbt DAO/jooqGenerate`**, with `texera_ddl.sql` **and**
   `sql/updates/cluster.sql` applied (§2.4, §2.5). Override with `STORAGE_JDBC_URL`,
   `STORAGE_JDBC_USERNAME`, `STORAGE_JDBC_PASSWORD`.
3. **`STORAGE_ICEBERG_CATALOG_TYPE=postgres`** for tests — `storage.conf` defaults to a REST catalog
   on `localhost:8181`, but this fork disables Lakekeeper and keeps the catalog on Postgres.
4. **Run Scala module tests in separate sbt invocations.** Chaining them in one JVM leaves the
   Postgres JDBC driver deregistered after `WorkflowOperator/test`, and
   `WorkflowCompilingServiceRunSpec` then fails with "Failed to get driver instance". Each module
   passes alone.
5. The engine's Python tests need `amber/src/main/python/proto/`, which is generated, not checked in
   (`bash bin/python-proto-gen.sh`, protoc per `bin/protoc-version.txt`).

---

## 8. Deployment checklist

```
[ ] Back up texera_db.
[ ] Check public.databasechangelog for a stale id '51' (§2.3).
[ ] Apply the access_scope migration (§2.2) to the existing database.
[ ] Apply sql/updates/cluster.sql if it was never applied (§2.4).
[ ] Build with JDK 17 against a database that has both (§7).
[ ] Decide: curatedImages.prepull.enabled — costs node disk per image.
[ ] Decide: mounter.enabled — requires hostPath; check the pool namespace's PSS labels (§3.4).
[ ] If CloudMapper is in use, set cloudmapper.enabled=true (§6.2).
[ ] Confirm metrics-server is available (§3.5).
[ ] Confirm the CU manager's new DaemonSet RBAC is admitted (§3.3).
[ ] If the deployment runs >1 web-server replica, note the in-memory queue caveat (§4.1).
[ ] helm upgrade; do NOT use --wait if optional components are off rather than removed.
[ ] Enable the Models sidebar tabs via the settings API if the AlphaFold demo is wanted (§3.6).
```

## 9. Smoke test after deploying

```
[ ] Log in; the workspace opens; existing workflows load and run.
[ ] Create a private computing unit — the previous behaviour must be unchanged.
[ ] Create a public unit as admin; a non-admin sees it with a Public tag and WRITE.
[ ] Run two workflows against one public unit; the second shows "Queued 1/1" and starts on its own.
[ ] Register a curated image; the check Job goes READY and a pre-pull DaemonSet appears.
[ ] Regression, this fork's own: cache panel and cached-result highlighting; the CloudBioMapper
    Clusters page (must not 500 — that means cluster.sql is missing); the GPU model picker;
    the dataset/file-selection dialogs.
```

Useful log lines when debugging:

```
# mounting (in the computing unit's pod)
RepositoryMountManager - requesting a mount of model-N:<hash> for computing unit <cuid>
RepositoryMountManager - model-N:<hash> mounted at /mnt/texera-mounts/model-N/<hash>

# the queue (in the computing unit's pod)
ComputingUnitExecutionQueue - [cu=N] queued WorkflowIdentity(W) for user U; K waiting, running=...
ComputingUnitExecutionQueue - [cu=N] admitting WorkflowIdentity(W) (user U, round R)
RunIdentity$ - computing unit is now acting as user U (role ROLE)
ComputingUnitExecutionQueue - [cu=N] WorkflowIdentity(W) released the unit (execution COMPLETED)
```

---

## 10. What was verified, and where

On a local minikube cluster, on this branch:

| | |
| --- | --- |
| Scala compile + test compile | clean |
| Scala tests, 12 modules run separately | **4,529 passed, 0 failed** |
| Angular production build (`yarn build`) | passes |
| `helm template` + `bin/k8s/tests/test_helm_values.sh` | 82 objects, no duplicates; lint passes |
| End to end | admin created a public unit → a workflow mounted a model version → AlphaFold 3 MSA search ran on it |

The MSA search returned msa_depths of **466 / 459 / 322** for ubiquitin / NEDD8 / SUMO1, matching
the demo's recorded run, and in that order — a search returning a fixed number, or the same number
three times, would not be searching. The engine log confirmed the model was *mounted*, not copied.

**Not verified:** the Angular test suite (§6.1 blocks it); AlphaFold structure prediction and the
3Dmol.js viewer (GPU + ~630 GB of databases); the GPU variant of the AlphaFold image; multi-replica
behaviour of the in-memory queue; anything on a cluster that enforces Pod Security Standards.
