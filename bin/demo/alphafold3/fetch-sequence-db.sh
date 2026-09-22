#!/usr/bin/env bash
#
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
#
# Builds the small sequence database this demo searches.
#
# AlphaFold 3's real genetic databases are 630 GB unpacked, which is neither
# necessary nor possible for a proof of concept. This fetches a few hundred real,
# reviewed ubiquitin-family sequences from UniProt instead -- enough that a
# jackhmmer search over them returns a genuine alignment with real homologs, and
# small enough to commit as a model version in seconds.
#
# Nothing is checked into the repository: UniProt data carries its own licence
# (CC BY 4.0), so the demo fetches it on the spot rather than vendoring it.

set -euo pipefail

OUT_DIR="${1:-$(dirname "$0")/build}"
mkdir -p "$OUT_DIR"

UNIPROT_STREAM="https://rest.uniprot.org/uniprotkb/stream"
QUERY='family:"ubiquitin family" AND reviewed:true'

echo "Fetching reviewed ubiquitin-family sequences from UniProt..."
curl -sS --get --max-time 120 \
  --data-urlencode "query=${QUERY}" \
  --data-urlencode "format=fasta" \
  "$UNIPROT_STREAM" \
  -o "$OUT_DIR/ubiquitin_family.fasta"

COUNT=$(grep -c '^>' "$OUT_DIR/ubiquitin_family.fasta")
BYTES=$(wc -c < "$OUT_DIR/ubiquitin_family.fasta")

if [ "$COUNT" -lt 50 ]; then
  echo "Only $COUNT sequences came back; UniProt may be rate-limiting. Retry in a minute." >&2
  exit 1
fi

echo "Wrote $OUT_DIR/ubiquitin_family.fasta -- $COUNT sequences, $BYTES bytes."
echo
echo "Upload it as a Texera model version, e.g. over the MCP server:"
echo "  model_create(name='alphafold3-seqdb', framework='other', format='other')"
echo "  model_upload_local_file(.../ubiquitin_family.fasta)"
echo "  model_create_version(...)"
