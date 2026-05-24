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

FROM sbtscala/scala-sbt:eclipse-temurin-jammy-11.0.17_8_1.9.3_2.13.11 AS build

# Set working directory
WORKDIR /texera

# Copy modules for building the service
COPY common/ common/
COPY amber/ amber/
COPY project/ project/
COPY build.sbt build.sbt

# Update system and install dependencies
RUN apt-get update && apt-get install -y \
    netcat \
    unzip \
    libpq-dev \
    && apt-get clean

# Add .git for runtime calls to jgit from OPversion
COPY .git .git
COPY LICENSE NOTICE DISCLAIMER-WIP ./

RUN sbt clean WorkflowExecutionService/dist

# Unzip the texera binary
RUN unzip amber/target/universal/amber-*.zip -d amber/target/

FROM eclipse-temurin:11-jdk-jammy AS runtime

# Build argument to enable/disable R support (default: false)
ARG WITH_R_SUPPORT=true

WORKDIR /texera/amber

COPY --from=build /texera/amber/requirements.txt /tmp/requirements.txt
COPY --from=build /texera/amber/operator-requirements.txt /tmp/operator-requirements.txt

# Install Python runtime dependencies
RUN apt-get update && apt-get install -y \
    python3-pip \
    python3-dev \
    python3-venv \
    libpq-dev \
    curl \
    unzip \
    ttyd \
    ca-certificates \
    tmux \
    $(if [ "$WITH_R_SUPPORT" = "true" ]; then echo "\
    gfortran \
    build-essential \
    libreadline-dev \
    libncurses-dev \
    libssl-dev \
    libxml2-dev \
    xorg-dev \
    libbz2-dev \
    liblzma-dev \
    libpcre++-dev \
    libpango1.0-dev \
    libcurl4-openssl-dev \
    libicu-dev \
    cmake \
    libuv1-dev \
    libfontconfig1-dev \
    libharfbuzz-dev \
    libfribidi-dev \
    libfreetype6-dev \
    libpng-dev \
    libtiff5-dev \
    libjpeg-dev \
    libwebp-dev \
    unzip \
    openssh-client \
    gnupg \
    software-properties-common \
    dirmngr \
    git"; fi) \
    && apt-get clean

RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    apt-get update && \
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Install R 4.3.3 from source to pin the exact version (before conda to avoid PATH interference)
RUN if [ "$WITH_R_SUPPORT" = "true" ]; then \
        curl -fsSL https://cran.r-project.org/src/base/R-4/R-4.3.3.tar.gz -o /tmp/R-4.3.3.tar.gz && \
        tar -xzf /tmp/R-4.3.3.tar.gz -C /tmp && \
        cd /tmp/R-4.3.3 && \
        ./configure --with-blas --with-lapack --enable-R-shlib && \
        make -j$(nproc) && make install && \
        rm -rf /tmp/R-4.3.3* && \
        R --version; \
    fi

ENV CONDA_DIR /opt/conda
RUN mkdir -p /tmp/miniconda3 && \
    curl -fsSL https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh -o /tmp/miniconda.sh && \
    bash /tmp/miniconda.sh -b -u -p $CONDA_DIR && \
    rm /tmp/miniconda.sh

RUN /opt/conda/bin/conda config --add channels conda-forge && \
    /opt/conda/bin/conda config --set channel_priority strict && \
    /opt/conda/bin/conda config --remove channels defaults && \
    /opt/conda/bin/conda init bash

# Install Python packages
RUN pip3 install --upgrade pip setuptools wheel && \
    pip3 install -r /tmp/requirements.txt && \
    pip3 install -r /tmp/operator-requirements.txt && \
    pip3 install biopython scanpy==1.11.5

# Install texera-rudf and its dependencies (conditional)
RUN if [ "$WITH_R_SUPPORT" = "true" ]; then \
        pip3 install git+https://github.com/Texera/texera-rudf.git; \
    fi

# Install R packages with pinned versions for texera-rudf (conditional)
RUN if [ "$WITH_R_SUPPORT" = "true" ]; then \
        Rscript -e "options(repos = c(CRAN = 'https://cran.r-project.org')); \
                    if (!requireNamespace('remotes', quietly=TRUE)) \
                      install.packages('remotes', Ncpus = parallel::detectCores()); \
                    remotes::install_version('arrow', version='14.0.2.1', \
                      repos='https://cran.r-project.org', upgrade='never', \
                      Ncpus = parallel::detectCores()); \
                    remotes::install_version('coro', version='1.1.0', \
                      repos='https://cran.r-project.org', upgrade='never', \
                      Ncpus = parallel::detectCores()); \
                    remotes::install_version('aws.s3', version='0.3.22', \
                      repos='https://cran.r-project.org', upgrade='never', \
                      Ncpus = parallel::detectCores()); \
                    cat('R package versions:\n'); \
                    cat('  arrow: ', as.character(packageVersion('arrow')), '\n'); \
                    cat('  coro: ', as.character(packageVersion('coro')), '\n'); \
                    cat('  aws.s3: ', as.character(packageVersion('aws.s3')), '\n')" && \
        Rscript -e "options(repos = c(CRAN = 'https://cran.r-project.org')); \
                    install.packages(c('BiocManager', 'R.utils', 'ggplotify', 'bench', 'igraph', 'leiden'), \
                      Ncpus = parallel::detectCores()); \
                    remotes::install_version('reticulate', version='1.36.1', upgrade='never', repos='https://cran.r-project.org', Ncpus=parallel::detectCores()); \
                    remotes::install_github('satijalab/seurat', ref = 'v5.2.1', upgrade = 'never'); \
                    dir.create('~/.R', showWarnings = FALSE); \
                    writeLines('CXX11STD = -std=gnu++14', '~/.R/Makevars'); \
                    remotes::install_version('harmony', version = '0.1.1', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    file.remove('~/.R/Makevars'); \
                    remotes::install_version('ggplot2', version = '3.5.2', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('future', version = '1.34.0', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('jsonlite', version = '1.9.1', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('later', version = '1.4.1', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('cluster', version = '2.1.4', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('zoo', version = '1.8-13', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('httpuv', version = '1.6.15', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('Matrix', version = '1.6-4', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('miniUI', version = '0.1.1.1', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('lattice', version = '0.21-8', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('survival', version = '3.5-5', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('KernSmooth', version = '2.23-21', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('globals', version = '0.16.3', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('nlme', version = '3.1-162', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('MASS', version = '7.3-60', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('SeuratObject', version = '5.0.2', repos = 'https://cran.r-project.org'); \
                    remotes::install_version('scSorter', version = '0.0.2', upgrade = 'never', repos = 'https://cran.r-project.org'); \
                    BiocManager::install(c('SingleCellExperiment', 'scDblFinder', 'glmGamPoi'), \
                      Ncpus = parallel::detectCores())" ; \
    fi

ENV LD_LIBRARY_PATH=/usr/local/lib/R/lib:$LD_LIBRARY_PATH

# Copy the built texera binary from the build phase
COPY --from=build /texera/.git /texera/amber/.git
COPY --from=build /texera/amber/target/amber-* /texera/amber/
# Copy resources directories from build phase
COPY --from=build /texera/common/config/src/main/resources /texera/amber/common/config/src/main/resources
COPY --from=build /texera/amber/src/main/resources /texera/amber/src/main/resources
# Copy code for python UDF
COPY --from=build /texera/amber/src/main/python /texera/amber/src/main/python
# Copy ASF licensing files
COPY --from=build /texera/LICENSE /texera/NOTICE /texera/DISCLAIMER-WIP /texera/

# Remove application.ini if it exists — the SBT launcher doesn't recognize
# --add-opens as a JVM flag and passes it as an app arg, causing a crash.
# The --add-opens flags are instead passed via JAVA_OPTS env var.
RUN rm -f conf/application.ini

CMD ["bin/computing-unit-master"]
CMD ["/bin/bash","-lc", "\
  ttyd -p 7681 -t disableLeaveAlert=true /bin/bash & \
  exec bin/computing-unit-master"]

EXPOSE 8085
EXPOSE 7681
