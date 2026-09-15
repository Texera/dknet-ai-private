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

# Source operator for the AlphaFold 3 demo: three real human proteins to search with.
#
# All three are ubiquitin-like, at deliberately different distances from the
# sequence database the next operator searches, so the alignment depths that come
# back differ for a reason rather than by accident.
#
# Paste into a "Python UDF Source" operator.

from pytexera import *


class GenerateOperator(UDFSourceOperator):

    # Sequences are from UniProt (CC BY 4.0), by accession.
    QUERIES = [
        (
            "ubiquitin",
            "P62979",
            "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYN"
            "IQKESTLHLVLRLRGG",
        ),
        (
            "NEDD8",
            "Q15843",
            "MLIKVKTLTGKEIEIDIEPTDKVERIKERVEEKEGIPPQQQRLIYSGKQMNDEKTAADYK"
            "ILGGSVLHLVLALRGGGGLRQ",
        ),
        (
            "SUMO1",
            "P63165",
            "MSDQEAKPSTEDLGDKKEGEYIKLKVIGQDSSEIHFKVKMTTHLKKLKESYCQRQGVPMN"
            "SLRFLFEGQRIADNHTPKELGMEEEDVIEVYQEQTGGHSTV",
        ),
    ]

    @overrides
    def produce(self) -> Iterator[Union[TupleLike, TableLike, None]]:
        for name, accession, sequence in self.QUERIES:
            yield {
                "protein": name,
                "accession": accession,
                "sequence": sequence,
            }
