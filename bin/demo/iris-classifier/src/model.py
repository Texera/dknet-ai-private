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
"""Model definition shared by training and inference.

The architecture is deliberately lopsided: a tiny classifier that does all the
predicting, next to a large embedding table that does almost no work. That is
the shape of a great many real models -- recommenders, retrieval encoders,
anything with a big vocabulary -- and it is the shape that makes lazy mounting
worth having. The weights are a gigabyte; scoring a row touches a few kilobytes
of them.
"""

from __future__ import annotations

import csv
import hashlib
from dataclasses import dataclass

import torch
import torch.nn as nn

FEATURES = ["SepalLengthCm", "SepalWidthCm", "PetalLengthCm", "PetalWidthCm"]
SPECIES = ["Iris-setosa", "Iris-versicolor", "Iris-virginica"]

#: Rows in the embedding table. 1M x 256 float32 is ~1.02 GB, which is the
#: point: it is large enough that copying it into every worker pod would be
#: the dominant cost of a run.
EMBEDDING_ROWS = 1_000_000
EMBEDDING_DIM = 256


class IrisClassifier(nn.Module):
    """4 -> 64 -> 32 -> 3 MLP. This is the part that actually classifies."""

    def __init__(self) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(len(FEATURES), 64),
            nn.ReLU(),
            nn.Linear(64, 32),
            nn.ReLU(),
            nn.Linear(32, len(SPECIES)),
        )

    def forward(self, features: torch.Tensor) -> torch.Tensor:
        return self.net(features)


def embedding_index(sample_id: int) -> int:
    """Maps a record id into the embedding table (the usual hashing trick).

    Hashed rather than taken modulo so consecutive ids land far apart, which is
    what makes the lookup a genuinely random read into the mounted file instead
    of a sequential scan of its first page.
    """
    digest = hashlib.blake2b(str(sample_id).encode(), digest_size=8).digest()
    return int.from_bytes(digest, "big") % EMBEDDING_ROWS


@dataclass
class IrisSample:
    sample_id: int
    features: list[float]
    species: str | None


def read_csv(path: str) -> list[IrisSample]:
    """Reads the Iris CSV. `Species` is optional, so this also reads unlabelled input."""
    samples: list[IrisSample] = []
    with open(path, newline="") as handle:
        for row in csv.DictReader(handle):
            samples.append(
                IrisSample(
                    sample_id=int(row.get("Id") or len(samples) + 1),
                    features=[float(row[name]) for name in FEATURES],
                    species=row.get("Species") or None,
                )
            )
    return samples
