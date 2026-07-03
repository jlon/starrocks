# StarRocks FE Docker image (multi-stage build)
#
# Build from repository root:
#   DOCKER_BUILDKIT=1 ./deploy/build.sh --target fe
#
ARG VERSION=3.4.0

# ---------------------------------------------------------------------------
# Stage 1: compile FE
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

ARG VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        patch \
        wget \
        make \
        g++ \
        libboost-dev \
        libevent-dev \
        libssl-dev \
        flex \
        bison \
        pkg-config \
        protobuf-compiler \
        && rm -rf /var/lib/apt/lists/*

# Thrift 0.13.0 (compatible with StarRocks FE)
RUN cd /tmp && \
    wget -q https://archive.apache.org/dist/thrift/0.13.0/thrift-0.13.0.tar.gz && \
    tar -xzf thrift-0.13.0.tar.gz && \
    cd thrift-0.13.0 && \
    ./configure --without-python --without-ruby --without-perl --without-php \
        --without-erlang --without-go --without-d --without-qt4 --without-qt5 \
        --without-csharp --without-haskell --without-rs --without-lua \
        --without-nodejs --without-nodets --without-py3 --without-dotnetcore --without-haxe && \
    make -j"$(nproc)" -C compiler/cpp && \
    make -j"$(nproc)" -C compiler/cpp install && \
    cd / && rm -rf /tmp/thrift-0.13.0* && \
    ldconfig

WORKDIR /build
ENV STARROCKS_HOME=/build
ENV PYTHON=python3

COPY deploy/maven-settings.xml /root/.m2/settings.xml

# Copy build descriptors first for better layer caching
COPY fe/pom.xml fe/
COPY fe/fe-common/pom.xml fe/fe-common/
COPY fe/fe-core/pom.xml fe/fe-core/
COPY fe/spark-dpp/pom.xml fe/spark-dpp/
COPY fe/hive-udf/pom.xml fe/hive-udf/
COPY fe/plugin-common/pom.xml fe/plugin-common/
COPY fe/fe-plugin-shield/pom.xml fe/fe-plugin-shield/
COPY java-extensions/pom.xml java-extensions/
COPY java-extensions/hadoop-ext/pom.xml java-extensions/hadoop-ext/

COPY . .

RUN cd gensrc && make clean && make

RUN set -e; \
    for attempt in 1 2 3; do \
        echo "Maven FE build attempt ${attempt}/3..."; \
        cd /build/fe && \
        mvn package -am -pl hive-udf,fe-common,spark-dpp,fe-core \
            -DskipTests \
            -Dcheckstyle.skip=true \
            -Drat.skip=true && \
        cd /build/java-extensions && \
        mvn package -am -pl hadoop-ext \
            -DskipTests \
            -Dcheckstyle.skip=true \
            -Drat.skip=true && break; \
        if [ "${attempt}" -eq 3 ]; then exit 1; fi; \
        echo "Build failed, retrying in 10s..."; \
        sleep 10; \
    done

RUN chmod +x deploy/assemble-fe-output.sh && deploy/assemble-fe-output.sh

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM ubuntu:22.04

ARG VERSION
ARG USER=starrocks
ARG GROUP=starrocks
ARG STARROCKS_ROOT=/opt/starrocks

RUN apt-get update -y && apt-get install -y --no-install-recommends \
        openjdk-17-jdk \
        mysql-client \
        curl \
        vim \
        less \
        tzdata \
        locales \
        netcat \
        && ln -fs /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
        && dpkg-reconfigure -f noninteractive tzdata \
        && locale-gen en_US.UTF-8 \
        && rm -rf /var/lib/apt/lists/*

RUN ARCH="$(uname -m)" && cd /lib/jvm && \
    if [ "$ARCH" = "aarch64" ]; then \
        ln -sf java-17-openjdk-arm64 java-17-openjdk; \
    else \
        ln -sf java-17-openjdk-amd64 java-17-openjdk; \
    fi

ENV JAVA_HOME=/lib/jvm/java-17-openjdk
ENV STARROCKS_ROOT=${STARROCKS_ROOT}
ENV TZ=Asia/Shanghai
ENV VERSION=${VERSION}

WORKDIR ${STARROCKS_ROOT}

RUN groupadd --gid 1000 ${GROUP} && \
    useradd --no-create-home --uid 1000 --gid 1000 --shell /usr/sbin/nologin ${USER} && \
    mkdir -p ${STARROCKS_ROOT}/fe/meta ${STARROCKS_ROOT}/fe/log && \
    chown -R ${USER}:${GROUP} ${STARROCKS_ROOT}

COPY --from=builder --chown=${USER}:${GROUP} /build/output/fe ${STARROCKS_ROOT}/fe
COPY --chown=${USER}:${GROUP} deploy/docker-entrypoint-fe.sh ${STARROCKS_ROOT}/docker-entrypoint.sh
COPY --chown=${USER}:${GROUP} docker/dockerfiles/fe/fe_entrypoint.sh ${STARROCKS_ROOT}/
COPY --chown=${USER}:${GROUP} docker/dockerfiles/fe/fe_prestop.sh ${STARROCKS_ROOT}/

RUN chmod +x ${STARROCKS_ROOT}/docker-entrypoint.sh \
             ${STARROCKS_ROOT}/fe_entrypoint.sh \
             ${STARROCKS_ROOT}/fe_prestop.sh \
             ${STARROCKS_ROOT}/fe/bin/*.sh

USER ${USER}

VOLUME ["${STARROCKS_ROOT}/fe/meta", "${STARROCKS_ROOT}/fe/log"]

# query_port default 9030, http_port default 8030, edit_log_port default 9010
EXPOSE 8030 9020 9030 9010

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD nc -z localhost 9030 || exit 1

ENTRYPOINT ["/opt/starrocks/docker-entrypoint.sh"]
CMD ["foreground"]

LABEL org.opencontainers.image.title="StarRocks FE" \
      org.opencontainers.image.version="${VERSION}"
