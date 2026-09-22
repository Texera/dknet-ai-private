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

# Full AlphaFold 3 structure prediction: the data pipeline against the complete
# genetic databases, then GPU inference with the real model parameters.
#
# Both inputs are Texera resources, and neither is copied into the computing unit:
#
#   AF3_WEIGHTS    a model resource holding af3.bin. Keep it private -- the
#                  AlphaFold 3 weights terms forbid sharing the parameters.
#   AF3_DATABASES  a dataset resource holding the files fetch_databases.sh
#                  produces, under their original names (about 630 GB).
#
# Each is mounted read-only when the run starts, and the property panel's value is
# the directory it landed in.
#
# Run it on a computing unit started from the GPU image
# (texera/computing-unit-master:alpha3-cuda-amd64) with one GPU. On the CPU image
# the data pipeline still runs, and inference fails with no GPU to run on.
#
# AlphaFold runs as a child process with the same interpreter rather than being
# imported here: jax claims most of the GPU when it initialises, and a worker that
# had done that would leave nothing for a second prediction in the same run.

from pytexera import *

import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

RUN_ALPHAFOLD = "/opt/alphafold3/run_alphafold.py"
SEED = 1


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
            raise FileNotFoundError(
                f"No af3.bin in the mounted model at {self.model_dir}. "
                f"Found: {sorted(os.listdir(self.model_dir))}"
            )
        for name in ("uniref90_2022_05.fa", "mgy_clusters_2022_05.fa", "mmcif_files"):
            if not os.path.exists(os.path.join(self.db_dir, name)):
                raise FileNotFoundError(
                    f"No {name} in the mounted dataset at {self.db_dir}. "
                    f"Found: {sorted(os.listdir(self.db_dir))}"
                )

        # Without a GPU the data pipeline would run for many minutes and only then fail.
        if shutil.which("nvidia-smi") is None or subprocess.run(
            ["nvidia-smi", "-L"], capture_output=True
        ).returncode != 0:
            raise RuntimeError(
                "No NVIDIA GPU is visible. Start the computing unit from the "
                "alpha3-cuda image with one GPU."
            )
        self.cpus = max(1, len(os.sched_getaffinity(0)))

    @overrides
    def process_tuple(self, tuple_: Tuple, port: int) -> Iterator[Optional[TupleLike]]:
        sequence = re.sub(r"\s+", "", tuple_["sequence"]).upper()
        name = re.sub(r"[^a-z0-9_]", "_", tuple_["accession"].lower())
        work = tempfile.mkdtemp(prefix=f"af3-{name}-")
        try:
            json_path = os.path.join(work, "input.json")
            with open(json_path, "w") as f:
                json.dump(
                    {
                        "name": name,
                        "sequences": [{"protein": {"id": "A", "sequence": sequence}}],
                        "modelSeeds": [SEED],
                        "dialect": "alphafold3",
                        "version": 1,
                    },
                    f,
                )
            out_dir = os.path.join(work, "out")

            started = time.time()
            proc = subprocess.run(
                [
                    sys.executable,
                    RUN_ALPHAFOLD,
                    f"--json_path={json_path}",
                    f"--model_dir={self.model_dir}",
                    f"--db_dir={self.db_dir}",
                    f"--output_dir={out_dir}",
                    f"--jackhmmer_n_cpu={min(8, self.cpus)}",
                    f"--nhmmer_n_cpu={min(8, self.cpus)}",
                ],
                capture_output=True,
                text=True,
            )
            seconds = time.time() - started
            if proc.returncode != 0:
                raise RuntimeError(
                    f"AlphaFold 3 failed for {tuple_['accession']} "
                    f"(exit {proc.returncode}):\n{proc.stderr[-4000:]}"
                )

            result_dir = os.path.join(out_dir, name)
            with open(os.path.join(result_dir, f"{name}_model.cif")) as f:
                cif = f.read()
            with open(os.path.join(result_dir, f"{name}_summary_confidences.json")) as f:
                summary = json.load(f)
            with open(os.path.join(result_dir, f"{name}_confidences.json")) as f:
                plddts = json.load(f)["atom_plddts"]
            mean_plddt = sum(plddts) / len(plddts)

            yield {
                "protein": tuple_["protein"],
                "accession": tuple_["accession"],
                "residues": len(sequence),
                "ranking_score": float(summary["ranking_score"]),
                "ptm": float(summary["ptm"]),
                "mean_plddt": round(mean_plddt, 2),
                "seconds": round(seconds, 1),
                "structure_cif": cif,
                "structure_html": self._viewer(tuple_, cif, summary, mean_plddt),
            }
        finally:
            shutil.rmtree(work, ignore_errors=True)

    @staticmethod
    def _viewer(tuple_: Tuple, cif: str, summary: dict, mean_plddt: float) -> str:
        # The outer div comes first on purpose: Texera's visualization frame stretches
        # the page's first div to full height, so that div has to be the one holding
        # both the title and the canvas. Were the title bar first, it would fill the
        # frame and push the structure out of view.
        #
        # The inline script must not contain <, > or &. The frame re-serializes the page
        # with XMLSerializer, which escapes them to entities, and the iframe then reads
        # the script as raw text -- so `b > 90` arrives as `b &gt; 90`, render() throws,
        # and the canvas stays black. Hence the mmCIF travels as base64 (which also
        # keeps it from breaking out of the script), and the confidence band is chosen
        # arithmetically rather than with comparisons.
        #
        # AlphaFold writes per-atom pLDDT into the B-factor column, so colouring by
        # B-factor is colouring by confidence.
        cif_b64 = base64.b64encode(cif.encode()).decode()
        return f"""<!doctype html>
<html><head><meta charset="utf-8">
<script src="https://cdn.jsdelivr.net/npm/3dmol@2.4.0/build/3Dmol-min.js"></script>
<style>
  html, body {{ height:100%; }}
  body {{ margin:0; font-family:system-ui,-apple-system,sans-serif; background:#0f1115; color:#e8eaed; }}
  .page {{ display:flex; flex-direction:column; height:100%; }}
  .bar {{ flex:none; padding:10px 14px; border-bottom:1px solid #262a33; }}
  .name {{ font-size:15px; font-weight:600; }}
  .meta {{ font-size:12px; color:#9aa3b2; margin-top:3px; line-height:1.5; }}
  #v {{ position:relative; flex:1; min-height:360px; width:100%; }}
</style></head>
<body>
<div class="page">
  <div class="bar">
    <div class="name">{tuple_["protein"]} &middot; {tuple_["accession"]}</div>
    <div class="meta">
      AlphaFold&nbsp;3 prediction &middot; ranking score {summary["ranking_score"]:.2f}
      &middot; pTM {summary["ptm"]:.2f} &middot; mean pLDDT {mean_plddt:.1f}<br>
      coloured by pLDDT: orange &lt; 50, yellow 50&ndash;70, light blue 70&ndash;90, blue &gt; 90
    </div>
  </div>
  <div id="v"></div>
</div>
<script>
  var viewer = $3Dmol.createViewer(document.getElementById("v"), {{backgroundColor:"#0f1115"}});
  viewer.addModel(atob("{cif_b64}"), "cif");
  // Bands: under 50, 50-70, 70-90, over 90. ceil((b - 50) / 20), clamped to 0..3.
  var bands = ["#ff7d45", "#ffdb13", "#65cbf3", "#0053d6"];
  viewer.setStyle({{}}, {{cartoon:{{colorfunc: function(atom) {{
    return bands[Math.min(3, Math.max(0, Math.ceil((atom.b - 50) / 20)))];
  }}}}}});
  viewer.zoomTo();
  viewer.render();
</script>
</body></html>"""
