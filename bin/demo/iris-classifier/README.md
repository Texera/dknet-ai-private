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

# iris-classifier

An ordinary little PyTorch project: it trains a classifier on the Iris dataset, saves the weights, and
scores a CSV with them. Nothing about it knows what Texera is.

It exists so there is something real to bring *into* Texera — a trained model on someone's laptop, of the
kind people actually have — and so the interesting part of the demo is the part that follows: asking a
chatbot to publish it and build a workflow around it, over
[the MCP server](../../../mcp-service/README.md).

```bash
pip install -r requirements.txt
python src/train.py                  # ~2 min, CPU only
python src/predict.py                # sanity check, prints predictions
```

## What it builds

`src/train.py` writes two files into `artifacts/`:

| File | Size | What it is |
| --- | --- | --- |
| `iris_embedding_classifier.pt` | ~1.0 GB | Trained classifier weights **and** a 1,000,000 × 256 embedding table |
| `metadata.json` | ~300 B | Species order, feature order, training accuracy |

The classifier is a 4 → 64 → 32 → 3 MLP trained for 400 epochs; it reaches 98.7% training accuracy on
Iris, which says more about Iris than about the model. The embedding table is initialised, not learned —
it is there to make the checkpoint the size a real model is.

That combination is the point. The checkpoint is a gigabyte, and scoring a row reads a few kilobytes of
it: the classifier is 3,000 parameters, and each row gathers exactly one 256-float embedding row, chosen
by hashing the record id so the reads are scattered rather than sequential. Lopsided in exactly this way
is what a great many production models look like — recommenders, retrieval encoders, anything with a
large vocabulary — and it is the case that makes mounting worth doing. Copying a gigabyte into every
worker to read kilobytes of it is the thing worth not doing.

`--model-dir` on `predict.py` points the same code at a checkpoint directory somewhere else, which is
precisely what happens in Texera: the model is mounted under `/mnt/texera-mounts/...` and the UDF is
handed that path.

## Bringing it into Texera

With the [MCP server](../../../mcp-service/README.md) connected to a chatbot, the whole of it is a
request in English. What the chatbot does on your behalf:

```
dataset_create  -> dataset_upload_local_file(data/Iris.csv) -> dataset_create_version
model_create    -> model_upload_local_file(artifacts/iris_embedding_classifier.pt)
                -> model_upload_local_file(artifacts/metadata.json) -> model_create_version
computing_unit_create
workflow_create -> workflow_open -> workflow_add_operator … -> workflow_validate -> workflow_run
```

One ordering in there is load-bearing and easy to miss: an uploaded file is staged, not committed, so
nothing can reference it until `model_create_version` runs. Mounting is not something anyone arranges —
the UDF names a committed version and the worker mounts it on startup.

The workflow the chatbot ends up building does the preprocessing and the analysis in Texera's own
operators and uses exactly one Python UDF, for the model:

```
CSVFileScan → Filter → Projection → PythonUDFV2 ─┬─ Scatterplot
                                    (mounted)    ├─ Limit
                                                 └─ Filter → Aggregate → Sort → BarChart
```

Inside that UDF the mount is just a directory:

```python
model = torch.load(os.path.join(IRIS_MODEL, "iris_embedding_classifier.pt"),
                   map_location="cpu", weights_only=True)
```

`IRIS_MODEL` comes from a `self.UiParameter("IRIS_MODEL", AttributeType.STRING, value=Resource.MODEL)` declaration in the
UDF itself; the property panel offers a model picker for that row, and the value is the mount path.
The first `torch.load` pays for reading the gigabyte it actually needs; after that, scoring is
sub-second.

## Layout

```
data/Iris.csv       150 rows, the classic dataset
src/model.py        architecture, feature order, the id -> embedding-row hash
src/train.py        trains, builds the table, writes artifacts/
src/predict.py      loads a checkpoint directory and scores a CSV
artifacts/          produced by train.py; not checked in (~1 GB)
```
