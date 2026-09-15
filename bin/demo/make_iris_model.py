#!/usr/bin/env python3
"""Build a ~1 GB CPU-only PyTorch checkpoint for the Texera model-mount demo.

Two parts, saved as a plain tensor state dict (portable across torch versions,
loadable with weights_only=True):

  classifier head  - a small 4 -> 64 -> 32 -> 3 MLP, actually trained on Iris, so the
                     demo's predictions are real (~97% train accuracy). Microseconds
                     per row on CPU.
  embedding bank   - 1,000,000 x 256 float32 (~1.02 GB), the reason the checkpoint is
                     big. Inference gathers ONE row per tuple (a hashing-trick lookup),
                     which is a pointer chase, not compute. This is what makes the model
                     heavy to *store* and cheap to *run* - exactly the case lazy FUSE
                     mounting is for.

Usage: python make_iris_model.py <iris.csv> <out.pt>
"""

import sys

import numpy as np
import torch
import torch.nn as nn

EMBED_ROWS = 1_000_000
EMBED_DIM = 256
SPECIES = ["Iris-setosa", "Iris-versicolor", "Iris-virginica"]
FEATURES = ["SepalLengthCm", "SepalWidthCm", "PetalLengthCm", "PetalWidthCm"]


def load_iris(csv_path):
    import csv

    xs, ys = [], []
    with open(csv_path) as fh:
        for row in csv.DictReader(fh):
            xs.append([float(row[f]) for f in FEATURES])
            ys.append(SPECIES.index(row["Species"]))
    return np.array(xs, dtype=np.float32), np.array(ys, dtype=np.int64)


class Head(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(len(FEATURES), 64),
            nn.ReLU(),
            nn.Linear(64, 32),
            nn.ReLU(),
            nn.Linear(32, len(SPECIES)),
        )

    def forward(self, x):
        return self.net(x)


def main():
    csv_path, out_path = sys.argv[1], sys.argv[2]
    torch.manual_seed(0)

    x, y = load_iris(csv_path)
    mean, std = x.mean(axis=0), x.std(axis=0)
    xn = torch.from_numpy((x - mean) / std)
    yt = torch.from_numpy(y)

    head = Head()
    opt = torch.optim.Adam(head.parameters(), lr=0.01)
    loss_fn = nn.CrossEntropyLoss()
    for epoch in range(400):
        opt.zero_grad()
        loss = loss_fn(head(xn), yt)
        loss.backward()
        opt.step()
        if (epoch + 1) % 100 == 0:
            acc = (head(xn).argmax(1) == yt).float().mean().item()
            print(f"  epoch {epoch + 1:4d}  loss {loss.item():.4f}  acc {acc:.3f}")

    with torch.no_grad():
        acc = (head(xn).argmax(1) == yt).float().mean().item()
    print(f"final train accuracy: {acc:.3f}")

    print(f"allocating embedding bank {EMBED_ROWS} x {EMBED_DIM} float32 ...")
    # Built in chunks so peak memory stays close to the tensor itself.
    embedding = torch.empty(EMBED_ROWS, EMBED_DIM, dtype=torch.float32)
    generator = torch.Generator().manual_seed(1)
    chunk = 50_000
    for start in range(0, EMBED_ROWS, chunk):
        stop = min(start + chunk, EMBED_ROWS)
        embedding[start:stop].normal_(0.0, 0.02, generator=generator)

    state = {
        # trained classifier
        **{f"head.{k}": v for k, v in head.state_dict().items()},
        # feature standardisation used at inference time
        "norm.mean": torch.from_numpy(mean),
        "norm.std": torch.from_numpy(std),
        # the large part
        "embedding.weight": embedding,
    }
    meta = {
        "features": FEATURES,
        "classes": SPECIES,
        "embed_rows": EMBED_ROWS,
        "embed_dim": EMBED_DIM,
        "train_accuracy": round(acc, 4),
    }

    print(f"saving to {out_path} ...")
    torch.save({"state_dict": state, "meta": meta}, out_path)
    import os

    print(f"wrote {out_path}: {os.path.getsize(out_path) / 2**30:.3f} GiB")


if __name__ == "__main__":
    main()
