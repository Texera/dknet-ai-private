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
"""Trains the Iris classifier and writes the artifacts to disk.

    python src/train.py

Produces, under artifacts/:

    iris_embedding_classifier.pt   ~1.0 GB state dict -- the trained head plus
                                   the embedding table
    metadata.json                  species order, feature order, accuracy

The embedding table is initialised rather than learned. It is there because the
demo is about serving a large checkpoint, not about representation learning,
and pretending otherwise in a file called train.py would be dishonest.
"""

from __future__ import annotations

import json
import os
import sys
import time

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from model import (  # noqa: E402
    EMBEDDING_DIM,
    EMBEDDING_ROWS,
    FEATURES,
    SPECIES,
    IrisClassifier,
    read_csv,
)

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
DATA_CSV = os.path.join(PROJECT, "data", "Iris.csv")
ARTIFACTS = os.path.join(PROJECT, "artifacts")

EPOCHS = 400
LEARNING_RATE = 0.01
#: Rows per fill step. The table is too big to materialise in one allocation on
#: a laptop, so it is written in slices.
FILL_CHUNK_ROWS = 50_000


def train_classifier(features: torch.Tensor, labels: torch.Tensor) -> tuple[IrisClassifier, float]:
    torch.manual_seed(0)
    classifier = IrisClassifier()
    optimizer = torch.optim.Adam(classifier.parameters(), lr=LEARNING_RATE)
    loss_fn = torch.nn.CrossEntropyLoss()

    for epoch in range(EPOCHS):
        optimizer.zero_grad()
        loss = loss_fn(classifier(features), labels)
        loss.backward()
        optimizer.step()
        if (epoch + 1) % 100 == 0:
            print(f"  epoch {epoch + 1:>4}/{EPOCHS}  loss {loss.item():.4f}")

    classifier.eval()
    with torch.no_grad():
        accuracy = (classifier(features).argmax(dim=1) == labels).float().mean().item()
    return classifier, accuracy


def build_embedding_table() -> torch.Tensor:
    """Allocates and fills the embedding table in slices, to bound peak memory."""
    generator = torch.Generator().manual_seed(0)
    table = torch.empty(EMBEDDING_ROWS, EMBEDDING_DIM, dtype=torch.float32)
    for start in range(0, EMBEDDING_ROWS, FILL_CHUNK_ROWS):
        rows = min(FILL_CHUNK_ROWS, EMBEDDING_ROWS - start)
        table[start : start + rows].normal_(mean=0.0, std=0.02, generator=generator)
    return table


def main() -> int:
    samples = read_csv(DATA_CSV)
    labelled = [sample for sample in samples if sample.species is not None]
    if not labelled:
        print(f"No labelled rows in {DATA_CSV}", file=sys.stderr)
        return 1

    features = torch.tensor([sample.features for sample in labelled], dtype=torch.float32)
    labels = torch.tensor([SPECIES.index(sample.species) for sample in labelled], dtype=torch.long)
    print(f"Training on {len(labelled)} rows from {os.path.relpath(DATA_CSV, PROJECT)}")

    classifier, accuracy = train_classifier(features, labels)
    print(f"  training accuracy {accuracy:.3f}")

    print(f"Building the {EMBEDDING_ROWS:,} x {EMBEDDING_DIM} embedding table")
    started = time.time()
    embedding = build_embedding_table()

    os.makedirs(ARTIFACTS, exist_ok=True)
    checkpoint_path = os.path.join(ARTIFACTS, "iris_embedding_classifier.pt")

    # A plain tensor state dict, so it loads with weights_only=True and does not
    # depend on this file being importable wherever it is opened -- including
    # inside a Texera Python UDF, which has no copy of this project.
    state_dict = {f"classifier.{name}": tensor for name, tensor in classifier.state_dict().items()}
    state_dict["embedding.weight"] = embedding
    torch.save({"state_dict": state_dict, "meta": {"species": SPECIES, "features": FEATURES}}, checkpoint_path)
    del embedding

    size_gib = os.path.getsize(checkpoint_path) / (1024**3)
    print(f"  wrote {os.path.relpath(checkpoint_path, PROJECT)} ({size_gib:.2f} GiB) in {time.time() - started:.0f}s")

    metadata_path = os.path.join(ARTIFACTS, "metadata.json")
    with open(metadata_path, "w") as handle:
        json.dump(
            {
                "species": SPECIES,
                "features": FEATURES,
                "embedding_rows": EMBEDDING_ROWS,
                "embedding_dim": EMBEDDING_DIM,
                "training_rows": len(labelled),
                "training_accuracy": round(accuracy, 4),
            },
            handle,
            indent=2,
        )
        handle.write("\n")
    print(f"  wrote {os.path.relpath(metadata_path, PROJECT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
