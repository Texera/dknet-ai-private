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
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Folds one biomolecular complex with AlphaFold 3 and reports everything AlphaFold
# Server shows for a job -- and a little more -- as one long table.
#
# Input: one row per molecule of the complex, with the columns
#   job_name  the same on every row; names the job
#   entity    a label for the molecule ("ADAT2 deaminase")
#   type      protein | rna | dna | ion | ligand
#   copies    how many copies of it
#   sequence  residues for a polymer; the CCD code for an ion or ligand ("ZN")
#
# Output: one row per fact, told apart by `section`, so that plain Filter, Projection,
# Aggregate and Sort operators can route each kind of fact to its own chart:
#
#   summary     ipTM, pTM, ranking score, fraction disordered, clash flag, mean pLDDT
#   entity      the molecules that went in, with the chains they became
#   chain       per-chain pTM and ipTM
#   chain_pair  chain-pair ipTM and minimum PAE, one row per ordered pair
#   sample      ranking score of each of the predicted samples
#   residue     per-residue pLDDT and its confidence band
#   pae         predicted aligned error, averaged over PAE_BIN x PAE_BIN residue blocks
#   structure   a self-contained page with the 3D structure, coloured by pLDDT
#
# Weights and databases are Texera resources, mounted read-only when the run starts:
#   AF3_WEIGHTS    a model holding af3.bin. Keep it private: the AlphaFold 3 weights
#                  terms forbid sharing the parameters.
#   AF3_DATABASES  a dataset holding the files fetch_databases.sh produces.
#
# Run it on a computing unit started from the GPU image
# (texera/computing-unit-master:alpha3-cuda-amd64) with one GPU.

from pytexera import *

import base64
import csv
import json
import os
import re
import shutil
import string
import subprocess
import sys
import tempfile
import time

RUN_ALPHAFOLD = "/opt/alphafold3/run_alphafold.py"
SEED = 1

# A 667-token complex has 445k PAE cells; the heatmap needs far fewer to read.
PAE_BIN = 3

# Band labels are shown in charts, which Texera re-serializes; keep <, > and & out.
BANDS = [
    (90, "Very high (90-100)"),
    (70, "Confident (70-90)"),
    (50, "Low (50-70)"),
    (0, "Very low (0-50)"),
]


def band(plddt):
    for floor, label in BANDS:
        if plddt >= floor:
            return label
    return BANDS[-1][1]


def empty_row():
    return {
        "section": None, "chain": None, "chain_b": None, "label": None,
        "category": None, "i": None, "j": None, "value": None, "value2": None,
        "iptm": None, "ptm": None, "ranking_score": None,
        "fraction_disordered": None, "has_clash": None, "mean_plddt": None,
        "residues": None, "text": None,
    }


def row(**fields):
    r = empty_row()
    r.update(fields)
    return r


def atom_site(cif):
    """Rows of the mmCIF _atom_site loop as dicts. Enough of a parser for AF3 output."""
    lines = cif.splitlines()
    cols, rows, i = [], [], 0
    while i < len(lines):
        if lines[i].startswith("_atom_site."):
            while i < len(lines) and lines[i].startswith("_atom_site."):
                cols.append(lines[i].split(".", 1)[1].strip())
                i += 1
            while i < len(lines) and lines[i].startswith(("ATOM", "HETATM")):
                rows.append(dict(zip(cols, lines[i].split())))
                i += 1
            break
        i += 1
    return rows


class ProcessTupleOperator(UDFOperatorV2):

    @overrides
    def open(self):
        self.model_dir = self.UiParameter(
            "AF3_WEIGHTS", AttributeType.STRING, value=Resource.MODEL
        ).value
        self.db_dir = self.UiParameter(
            "AF3_DATABASES", AttributeType.STRING, value=Resource.DATASET
        ).value
        if not os.path.isfile(os.path.join(self.model_dir, "af3.bin")):
            raise FileNotFoundError(f"No af3.bin in the mounted model at {self.model_dir}")
        if not os.path.exists(os.path.join(self.db_dir, "uniref90_2022_05.fa")):
            raise FileNotFoundError(f"No genetic databases in the mounted dataset at {self.db_dir}")
        if subprocess.run(["nvidia-smi", "-L"], capture_output=True).returncode != 0:
            raise RuntimeError(
                "No NVIDIA GPU is visible. Start the computing unit from the "
                "alpha3-cuda image with one GPU."
            )
        self.cpus = max(1, len(os.sched_getaffinity(0)))
        self.molecules = []

    @overrides
    def process_tuple(self, tuple_: Tuple, port: int) -> Iterator[Optional[TupleLike]]:
        self.molecules.append({
            "job_name": str(tuple_["job_name"]),
            "entity": str(tuple_["entity"]),
            "type": str(tuple_["type"]).strip().lower(),
            "copies": int(tuple_["copies"]),
            "sequence": re.sub(r"\s+", "", str(tuple_["sequence"])),
        })
        yield from ()

    @overrides
    def on_finish(self, port: int) -> Iterator[Optional[TupleLike]]:
        if not self.molecules:
            return
        job_title = self.molecules[0]["job_name"]
        job = re.sub(r"[^a-z0-9_]", "_", job_title.lower()).strip("_") or "job"

        # Chain ids in input order, as AlphaFold Server assigns them.
        ids = iter(string.ascii_uppercase)
        sequences, chain_names, chain_types = [], {}, {}
        for m in self.molecules:
            chains = [next(ids) for _ in range(m["copies"])]
            m["chains"] = chains
            one = chains[0] if len(chains) == 1 else chains
            if m["type"] == "protein":
                sequences.append({"protein": {"id": one, "sequence": m["sequence"].upper()}})
            elif m["type"] in ("rna", "dna"):
                sequences.append({m["type"]: {"id": one, "sequence": m["sequence"].upper()}})
            elif m["type"] in ("ion", "ligand"):
                sequences.append({"ligand": {"id": one, "ccdCodes": [m["sequence"].upper()]}})
            else:
                raise ValueError(f"Unknown molecule type {m['type']!r} for {m['entity']}")
            for c in chains:
                chain_names[c] = m["entity"]
                chain_types[c] = m["type"]

        work = tempfile.mkdtemp(prefix=f"af3-{job}-")
        try:
            json_path = os.path.join(work, "input.json")
            with open(json_path, "w") as f:
                json.dump({"name": job, "sequences": sequences, "modelSeeds": [SEED],
                           "dialect": "alphafold3", "version": 1}, f)
            out_dir = os.path.join(work, "out")
            started = time.time()
            proc = subprocess.run(
                [sys.executable, RUN_ALPHAFOLD,
                 f"--json_path={json_path}",
                 f"--model_dir={self.model_dir}",
                 f"--db_dir={self.db_dir}",
                 f"--output_dir={out_dir}",
                 f"--jackhmmer_n_cpu={min(8, self.cpus)}",
                 f"--nhmmer_n_cpu={min(8, self.cpus)}"],
                capture_output=True, text=True,
            )
            seconds = time.time() - started
            if proc.returncode != 0:
                raise RuntimeError(f"AlphaFold 3 failed (exit {proc.returncode}):\n{proc.stderr[-4000:]}")

            res = os.path.join(out_dir, job)
            with open(os.path.join(res, f"{job}_model.cif")) as f:
                cif = f.read()
            with open(os.path.join(res, f"{job}_summary_confidences.json")) as f:
                summary = json.load(f)
            with open(os.path.join(res, f"{job}_confidences.json")) as f:
                conf = json.load(f)
            with open(os.path.join(res, f"{job}_ranking_scores.csv")) as f:
                ranking = list(csv.DictReader(f))
        finally:
            shutil.rmtree(work, ignore_errors=True)

        label = lambda c: f"{c} · {chain_names[c]}"

        # Per-residue pLDDT, in token order. A polymer residue is one token; an ion or
        # ligand atom is its own token, so those are keyed by atom.
        residues = {}
        for a in atom_site(cif):
            chain = a.get("label_asym_id")
            seq = a.get("label_seq_id", ".")
            key = (chain, seq if seq != "." else a.get("id"))
            r = residues.setdefault(key, {"chain": chain, "seq": seq, "comp": a.get("label_comp_id"), "b": []})
            r["b"].append(float(a["B_iso_or_equiv"]))
        per_residue = [(k, v, sum(v["b"]) / len(v["b"])) for k, v in residues.items()]
        atom_plddts = conf["atom_plddts"]
        mean_plddt = sum(atom_plddts) / len(atom_plddts)

        yield row(section="summary", label=job_title,
                  iptm=summary.get("iptm"), ptm=summary.get("ptm"),
                  ranking_score=summary.get("ranking_score"),
                  fraction_disordered=summary.get("fraction_disordered"),
                  has_clash="yes" if summary.get("has_clash") else "no",
                  mean_plddt=round(mean_plddt, 2),
                  residues=len(conf["token_chain_ids"]), value=round(seconds, 1))

        for m in self.molecules:
            yield row(section="entity", label=m["entity"], category=m["type"],
                      chain=", ".join(m["chains"]), i=m["copies"],
                      residues=len(m["sequence"]) if m["type"] not in ("ion", "ligand") else 1,
                      text=m["sequence"] if len(m["sequence"]) <= 60 else m["sequence"][:57] + "...")

        chain_ids = [c for m in self.molecules for c in m["chains"]]
        for idx, c in enumerate(chain_ids):
            yield row(section="chain", chain=label(c), category=chain_types[c],
                      value=summary["chain_ptm"][idx], value2=summary["chain_iptm"][idx])
        for a_idx, a in enumerate(chain_ids):
            for b_idx, b in enumerate(chain_ids):
                pae_min = summary.get("chain_pair_pae_min")
                yield row(section="chain_pair", chain=label(a), chain_b=label(b),
                          value=summary["chain_pair_iptm"][a_idx][b_idx],
                          value2=pae_min[a_idx][b_idx] if pae_min else None)

        for r in ranking:
            yield row(section="sample", label=f"seed {r['seed']} · sample {r['sample']}",
                      i=int(r["sample"]), value=float(r["ranking_score"]))

        for token, (key, r, plddt) in enumerate(per_residue, start=1):
            yield row(section="residue", chain=label(r["chain"]), i=token,
                      j=int(r["seq"]) if r["seq"] != "." else None,
                      label=r["comp"], value=round(plddt, 2), category=band(plddt))

        pae = conf["pae"]
        n = len(pae)
        for bi in range(0, n, PAE_BIN):
            for bj in range(0, n, PAE_BIN):
                block = [pae[x][y] for x in range(bi, min(bi + PAE_BIN, n))
                         for y in range(bj, min(bj + PAE_BIN, n))]
                yield row(section="pae", i=bj + 1, j=bi + 1, value=round(sum(block) / len(block), 2))

        yield row(section="structure", label=job_title,
                  text=self._viewer(job_title, cif, summary, mean_plddt, chain_ids, chain_names))

    @staticmethod
    def _viewer(title, cif, summary, mean_plddt, chain_ids, chain_names):
        # Two things Texera's visualization frame does shape this page. It stretches the
        # first div to full height, so that div holds both the header and the canvas. And
        # it re-serializes the page with XMLSerializer, which escapes <, > and & -- also
        # inside scripts, which the iframe then reads back as raw text. So the script has
        # none of them: the mmCIF travels as base64, and the confidence band is computed
        # arithmetically. AlphaFold writes per-atom pLDDT into the B-factor column.
        cif_b64 = base64.b64encode(cif.encode()).decode()
        chains = " &middot; ".join(f"{c}: {chain_names[c]}" for c in chain_ids)
        swatch = lambda colour, text: (
            f'<span class="sw"><i style="background:{colour}"></i>{text}</span>')
        legend = "".join([
            swatch("#0053d6", "Very high (pLDDT 90-100)"),
            swatch("#65cbf3", "Confident (70-90)"),
            swatch("#ffdb13", "Low (50-70)"),
            swatch("#ff7d45", "Very low (0-50)"),
        ])
        return f"""<!doctype html>
<html><head><meta charset="utf-8">
<script src="https://cdn.jsdelivr.net/npm/3dmol@2.4.0/build/3Dmol-min.js"></script>
<style>
  html, body {{ height:100%; }}
  body {{ margin:0; font-family:system-ui,-apple-system,sans-serif; background:#ffffff; color:#1f2328; }}
  .page {{ display:flex; flex-direction:column; height:100%; }}
  .bar {{ flex:none; padding:12px 16px 10px; border-bottom:1px solid #e5e7eb; }}
  .name {{ font-size:18px; font-weight:600; }}
  .scores {{ margin-top:4px; font-size:14px; }}
  .scores b {{ font-weight:600; }}
  .meta {{ font-size:12px; color:#57606a; margin-top:4px; }}
  .legend {{ margin-top:8px; font-size:12px; display:flex; flex-wrap:wrap; gap:14px; }}
  .sw i {{ display:inline-block; width:22px; height:6px; border-radius:3px; margin-right:6px; vertical-align:middle; }}
  #v {{ position:relative; flex:1; min-height:380px; width:100%; }}
</style></head>
<body>
<div class="page">
  <div class="bar">
    <div class="name">{title}</div>
    <div class="scores">ipTM = <b>{summary.get("iptm", 0):.2f}</b> &nbsp; pTM = <b>{summary.get("ptm", 0):.2f}</b>
      &nbsp; ranking score = <b>{summary.get("ranking_score", 0):.2f}</b> &nbsp; mean pLDDT = <b>{mean_plddt:.1f}</b></div>
    <div class="meta">{chains}</div>
    <div class="legend">{legend}</div>
  </div>
  <div id="v"></div>
</div>
<script>
  var viewer = $3Dmol.createViewer(document.getElementById("v"), {{backgroundColor:"#ffffff"}});
  viewer.addModel(atob("{cif_b64}"), "cif");
  // Bands: under 50, 50-70, 70-90, over 90. ceil((b - 50) / 20), clamped to 0..3.
  var bands = ["#ff7d45", "#ffdb13", "#65cbf3", "#0053d6"];
  var byPlddt = function(atom) {{
    return bands[Math.min(3, Math.max(0, Math.ceil((atom.b - 50) / 20)))];
  }};
  viewer.setStyle({{}}, {{cartoon:{{colorfunc: byPlddt}}}});
  viewer.addStyle({{hetflag:true}}, {{sphere:{{colorfunc: byPlddt, radius:1.2}}}});
  viewer.zoomTo();
  viewer.render();
</script>
</body></html>"""
