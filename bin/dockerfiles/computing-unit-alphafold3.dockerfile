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

# A computing-unit image carrying AlphaFold 3, registered as a curated image.
#
# A curated image is just a computing-unit image an administrator registers by
# reference. The platform validates it -- the start command has to name
# `computing-unit-master`, and it has to declare a non-root USER -- pins it to the
# digest that passed, and offers it in the dialog that creates a computing unit.
# There is no build step inside the cluster and no environment mechanism involved:
# whatever a unit needs is already in the image, identically for every unit started
# from it, surviving every restart.
#
# What has to be added here rather than installed at run time:
#
#   * jackhmmer and nhmmer are executables from the `hmmer` package. AlphaFold's
#     data pipeline shells out to them, and no Python-level mechanism can install
#     a system binary.
#   * AlphaFold 3 requires Python >= 3.12, and the engine image is Ubuntu jammy,
#     whose python3 is 3.10.
#   * AlphaFold 3 is not on PyPI and compiles a C++ extension from source.
#
# The Python version is settled by installing 3.12 alongside the system 3.10 and
# pointing the engine at it with UDF_PYTHON_PATH, which is the supported way to
# name the interpreter that runs UDFs (common/config/src/main/resources/udf.conf).
# The system python3 stays 3.10 so apt and the distribution's own tooling keep
# working; only the UDF worker moves.
#
# Model parameters are deliberately absent. They are not redistributable -- they
# come from DeepMind under a separate licence granted per user -- so this image
# carries the AlphaFold code and its data pipeline, which is what the MSA-search
# demo runs. Folding with weights needs those parameters mounted separately.
#
# Build (context is this directory; nothing from the repository tree is needed):
#   docker build -f computing-unit-alphafold3.dockerfile \
#     --build-arg BASE_IMAGE=texera-local/computing-unit-master:staging \
#     -t texera/computing-unit-master:alpha3-amd64 .

ARG BASE_IMAGE=texera-local/computing-unit-master:staging
FROM ${BASE_IMAGE}

USER root
ENV DEBIAN_FRONTEND=noninteractive

# hmmer supplies jackhmmer and nhmmer; build-essential and zlib build AlphaFold's
# C++ extension. software-properties-common is only here for add-apt-repository.
ARG PYTHON_VERSION=3.12
RUN apt-get update && apt-get install -y --no-install-recommends \
      software-properties-common \
      ca-certificates \
      curl \
      git \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update && apt-get install -y --no-install-recommends \
      hmmer \
      build-essential \
      zlib1g-dev \
      python${PYTHON_VERSION} \
      python${PYTHON_VERSION}-dev \
      python${PYTHON_VERSION}-venv \
    && apt-get purge -y software-properties-common \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Fail the build here rather than three layers later if the PPA served nothing.
RUN jackhmmer -h > /dev/null && nhmmer -h > /dev/null && echo "hmmer OK" \
    && python${PYTHON_VERSION} --version

ENV AF3_PYTHON=/usr/bin/python${PYTHON_VERSION}

RUN curl -sS https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py \
    && ${AF3_PYTHON} /tmp/get-pip.py \
    && rm /tmp/get-pip.py \
    && ${AF3_PYTHON} -m pip install --no-cache-dir --upgrade pip setuptools wheel

# The engine launches its Python worker with this interpreter, so the worker's own
# dependencies have to be installed into it too -- an interpreter holding only
# AlphaFold would fail before any user code ran. Both requirement files are already
# in the base image, left at /tmp by the stage that installed them for 3.10.
RUN ${AF3_PYTHON} -m pip install --no-cache-dir -r /tmp/requirements.txt \
    && ${AF3_PYTHON} -m pip install --no-cache-dir -r /tmp/operator-requirements.txt

ARG AF3_REF=main
RUN git clone --depth 1 --branch ${AF3_REF} \
      https://github.com/google-deepmind/alphafold3.git /opt/alphafold3 \
    && git -C /opt/alphafold3 rev-parse HEAD > /opt/alphafold3-commit.txt \
    && cat /opt/alphafold3-commit.txt

# --no-deps keeps out `jax[cuda12]`: roughly 3 GB of NVIDIA wheels that a cluster
# without an NVIDIA GPU cannot use. The C++ extension is still compiled. AlphaFold's
# own dependencies follow, with plain CPU jax substituted for the CUDA build.
RUN cd /opt/alphafold3 && ${AF3_PYTHON} -m pip install --no-cache-dir --no-deps .

RUN ${AF3_PYTHON} -m pip install --no-cache-dir \
      "absl-py>=2.3.1" \
      "dm-haiku==0.0.17" \
      "etils[epath]" \
      "jax==0.10.2" \
      "rdkit==2025.9.4" \
      "tokamax==0.0.12" \
      "tqdm" \
      "zstandard"

# Each import stands for one thing that could not have been arranged after the
# build. pyarrow and pandas are the engine's, and are checked here because the
# worker imports them before it ever reaches user code.
RUN ${AF3_PYTHON} -c "\
import sys, shutil, alphafold3, jax, pyarrow, numpy, pandas; \
from alphafold3.data.tools import jackhmmer; \
assert sys.version_info[:2] >= (3, 12), sys.version; \
assert shutil.which('jackhmmer'), 'jackhmmer not on PATH'; \
print('python', sys.version.split()[0]); \
print('alphafold3', alphafold3.__file__); \
print('jax', jax.__version__, 'numpy', numpy.__version__); \
print('pyarrow', pyarrow.__version__, 'pandas', pandas.__version__); \
print('jackhmmer', shutil.which('jackhmmer'))"

# What actually moves the UDF worker onto 3.12. Read by UdfConfig; an empty value
# means `python3`, which would be the system 3.10.
ENV UDF_PYTHON_PATH=${AF3_PYTHON}

# The engine runs unprivileged, so everything it reads has to be readable by it.
RUN chmod -R a+rX /opt/alphafold3

USER texera

# Inherited from the base image, restated because the platform reads it: an image
# whose start command does not name `computing-unit-master` is refused at
# registration as not being a computing-unit image.
CMD ["bin/computing-unit-master"]
