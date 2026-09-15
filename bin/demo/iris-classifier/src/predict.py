#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""Scores a CSV with the trained checkpoint.

    python src/predict.py [csv] [--model-dir DIR]

`--model-dir` exists so the same code runs against a directory that is not this
project's `artifacts/` -- which is exactly what a Texera Python UDF does: the
model is mounted somewhere under /mnt and the UDF is handed that path.
"""

from __future__ import annotations

import argparse
import os
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from model import SPECIES, IrisClassifier, embedding_index, read_csv  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
CHECKPOINT_NAME = "iris_embedding_classifier.pt"


def load(model_dir: str) -> tuple[IrisClassifier, torch.Tensor]:
    """Loads the classifier and the embedding table from a checkpoint directory."""
    checkpoint_path = os.path.join(model_dir, CHECKPOINT_NAME)
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    state_dict = checkpoint["state_dict"]

    classifier = IrisClassifier()
    classifier.load_state_dict({key[len("classifier.") :]: value for key, value in state_dict.items() if key.startswith("classifier.")})
    classifier.eval()
    return classifier, state_dict["embedding.weight"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="?", default=os.path.join(PROJECT, "data", "Iris.csv"))
    parser.add_argument("--model-dir", default=os.path.join(PROJECT, "artifacts"))
    parser.add_argument("--limit", type=int, default=10, help="rows to print")
    args = parser.parse_args()

    started = time.time()
    classifier, embedding = load(args.model_dir)
    print(f"Loaded the checkpoint from {args.model_dir} in {time.time() - started:.1f}s")

    samples = read_csv(args.csv)
    features = torch.tensor([sample.features for sample in samples], dtype=torch.float32)
    indices = torch.tensor([embedding_index(sample.sample_id) for sample in samples], dtype=torch.long)

    started = time.time()
    with torch.no_grad():
        probabilities = torch.softmax(classifier(features), dim=1)
        confidences, predictions = probabilities.max(dim=1)
        # One gather per row: the whole reason the table can stay on the far
        # side of a mount instead of being copied to the worker.
        signatures = embedding.index_select(0, indices).mean(dim=1)
    elapsed = time.time() - started

    correct = sum(
        1
        for sample, prediction in zip(samples, predictions.tolist())
        if sample.species is not None and sample.species == SPECIES[prediction]
    )
    labelled = sum(1 for sample in samples if sample.species is not None)

    print(f"Scored {len(samples)} rows in {elapsed:.2f}s")
    if labelled:
        print(f"Accuracy on the {labelled} labelled rows: {correct / labelled:.3f}")

    print()
    print(f"{'id':>4}  {'predicted':<16} {'confidence':>10}  {'signature':>10}  actual")
    for sample, prediction, confidence, signature in list(
        zip(samples, predictions.tolist(), confidences.tolist(), signatures.tolist())
    )[: args.limit]:
        print(
            f"{sample.sample_id:>4}  {SPECIES[prediction]:<16} {confidence:>10.4f}  "
            f"{signature:>10.6f}  {sample.species or '-'}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
