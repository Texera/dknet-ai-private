<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# AlphaFold 3 on Texera — a curated computing-unit image

A proof of concept for a workload nothing installed at run time can express, delivered
as a computing-unit image a user picks from a dropdown.

## What it is proving

[AlphaFold 3](https://github.com/google-deepmind/alphafold3) is the sharpest example
available, because it defeats run-time installation in three independent ways at once:

| AlphaFold 3 needs | Installing into a running unit offers |
| --- | --- |
| Python ≥ 3.12 | the image's interpreter — Ubuntu jammy's 3.10 |
| a source build (not on PyPI, compiles a C++ extension) | `pip install <spec>`, no build step |
| `jackhmmer` / `nhmmer`, which are executables | Python packages only |

None of these is a matter of effort. Shell access to a running pod does not fix them
either: that shell is the unprivileged `texera` user (uid 1001), so `apt install hmmer`
fails; and anything it did install would live in the container's writable layer and
vanish on the next restart.

An image settles all three at build time, as root, once — and identically for every
computing unit started from it, across every restart.

## What it is not

This runs AlphaFold 3's **data pipeline**, not structure prediction. Specifically:

- **No GPU inference and no model parameters.** AF3 inference wants an A100/H100 with
  CUDA 12.6, and its weights come from DeepMind under terms of their own. The image
  installs CPU jax deliberately, which keeps ~3 GB of unusable NVIDIA wheels out of it.
- **No 630 GB genetic databases.** The demo searches a few hundred real UniProt
  sequences instead — enough for a genuine alignment, small enough to commit in seconds.

What *is* real: AF3 3.0.5 built from source, `alphafold3.data.tools.jackhmmer` — the
same class AF3's own pipeline calls — and the real `jackhmmer` binary, on Python 3.12,
inside a Texera workflow, searching a database delivered as a mounted model version.

## Build the image

```bash
docker build -f bin/dockerfiles/computing-unit-alphafold3.dockerfile \
  --build-arg BASE_IMAGE=texera-local/texera-workflow-execution-coordinator:dev \
  -t texera-local/texera-workflow-execution-coordinator:alpha3 .
```

Build context is the repository root, and the base image is this fork's engine image
(`bin/dockerfiles/computing-unit-master.dockerfile` builds it under that name).

### Registering it, and why a local build cannot be registered

A curated image is resolved **from a registry** by the platform, never from a local
Docker daemon. Registration runs a Kubernetes Job that does
`skopeo inspect docker://<ref>` from inside the cluster, and a unit created from a
curated image then runs the **digest** reference `repo@sha256:…`, which kubelet cannot
satisfy from a locally tagged image even with `IfNotPresent`. So to use the curated-image
path the image has to be pushed somewhere the cluster can reach over HTTPS:

```bash
docker tag  texera-local/texera-workflow-execution-coordinator:alpha3 <you>/cu-alphafold3:1.0
docker push <you>/cu-alphafold3:1.0      # then register <you>/cu-alphafold3:1.0
```

On a laptop, where pushing several GB is the slow part, the alternative is to make the
**pool** image the AlphaFold image, which is referenced by tag rather than by digest:

```bash
helm upgrade texera bin/k8s -n texera-dev -f bin/k8s/values-local-minikube.yaml \
  --set texera.imageTag=alpha3
```

Every unit is then an AlphaFold unit, including a public one, which is enough to run the
demo below. What that does not reproduce is the negative control at the end of this file:
the point that the image choice is load-bearing needs two different images in one cluster.

The build fails rather than the workflow if anything is missing: a verification layer
imports `alphafold3`, `jax`, `pyarrow`, checks `sys.version_info >= (3, 12)` and
resolves `jackhmmer` on `PATH` before the image is tagged.

### How the engine finds Python 3.12

Python 3.12 is installed alongside the system 3.10, and the image sets
`UDF_PYTHON_PATH=/usr/bin/python3.12`. That is the supported way to name the interpreter
that runs UDFs (`common/config/src/main/resources/udf.conf`); nothing in the engine had
to change. The system `python3` stays 3.10 so apt and the distribution's own tooling
keep working — only the UDF worker moves.

The 3.12 interpreter also gets `amber/requirements.txt`. That is not optional: the engine
launches its own Python worker with this interpreter, so an interpreter holding only AF3
would fail before any user code ran.

## Register it as a curated image

As an administrator, on the **Curated Images** admin page, register:

```
texera/computing-unit-master:alpha3-amd64
```

The platform runs a validation job that reads the image's manifest from the registry —
it checks the start command names `computing-unit-master` and that the image declares a
non-root `USER` — then pins the image to the digest that passed. A tag moved afterwards
cannot change what units run.

Once it is ready, **the image is a property of the computing unit, not of the pool**:
the dialog that creates a unit offers it, and one user can run AlphaFold while everyone
else runs the stock image.

## Run the demo

```bash
bin/demo/alphafold3/fetch-sequence-db.sh      # ~226 reviewed ubiquitin-family sequences
```

Upload `build/ubiquitin_family.fasta` as a model named `alphafold3-seqdb` and commit a
version — a model is staged until a version commits it, and nothing can mount it before
then. Then build a two-operator workflow:

Create a computing unit and choose the AlphaFold 3 image in the dialog. Then:

```
Python UDF Source ──▶ Python UDF
 (three sequences)     (model: alphafold3-seqdb)
```

- Source operator: [`udf/generate_sequences.py`](udf/generate_sequences.py) — ubiquitin,
  NEDD8 and SUMO1, three real human proteins at deliberately different distances from
  the database.
- Search operator: [`udf/msa_search.py`](udf/msa_search.py) — pick the model version for
  the `SEQ_DB` row the property panel offers. There is no environment to select.

## What it produced

Run on minikube, computing unit 21 (2 CPU, 4 GiB, no GPU):

| protein | accession | residues | msa_depth | search_seconds | python | alphafold3 |
| --- | --- | --- | --- | --- | --- | --- |
| ubiquitin | P62979 | 76 | 466 | 0.075 | 3.12.14 | 3.0.5.dev1+g97d20234c |
| NEDD8 | Q15843 | 81 | 459 | 0.274 | 3.12.14 | 3.0.5.dev1+g97d20234c |
| SUMO1 | P63165 | 101 | 322 | 0.297 | 3.12.14 | 3.0.5.dev1+g97d20234c |

Three things in that table are the result:

**`python 3.12.14`**, from an engine whose own interpreter is 3.10 — the operator ran on
a different Python than the process that launched it.

**The alignment depths differ, and in the right order.** Ubiquitin sits in the middle of
the family it is being searched against, NEDD8 close by, SUMO1 at the edge. A search
returning a fixed number, or the same number three times, would not be searching.

**The database was never copied.** The engine log shows the model version mounted rather
than downloaded:

```
Requesting mount of model-10:321f07ad… from node mounter at http://192.168.58.2:8100/mount
Model model-10:321f07ad… mounted at /mnt/texera-mounts/model-10/321f07ad…
```

### The negative control

Run the identical workflow on a unit started from the stock image and it fails:

```
EXECUTION_FAILURE: java.lang.Throwable: No module named 'alphafold3'
```

Which is the point of the whole exercise. The image choice is load-bearing, and what it
selects is something no amount of run-time installation could have assembled.

## Layout

```
fetch-sequence-db.sh          builds the searchable database from UniProt
udf/generate_sequences.py     source operator: three query sequences
udf/msa_search.py             AF3's jackhmmer MSA search
build/                        fetched data; not checked in
```

AlphaFold 3 is licensed CC BY-NC-SA 4.0 and is neither vendored nor redistributed here —
the image clones it at build time. UniProt sequences are CC BY 4.0 and are fetched, not
committed, for the same reason.
