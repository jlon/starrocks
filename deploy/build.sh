#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT_ID="${ARTIFACT_ID:-starrocks-fe}"
VERSION="${VERSION:-}"
IMAGE_TAG="${IMAGE_TAG:-}"
TARGET="${TARGET:-allin1-ubi}"
ARTIFACT_SOURCE="${ARTIFACT_SOURCE:-docker}"
DEV_ENV_IMAGE="${DEV_ENV_IMAGE:-}"
DEFAULT_IMAGE_NAME="devhub.baymax.oppoer.me/starrocks/starrocks-allin1"
IMAGE_NAME="${IMAGE_NAME:-${DEFAULT_IMAGE_NAME}}"
DOCKERFILE="${DOCKERFILE:-}"
PUSH="${PUSH:-false}"
DOCKERIGNORE_BACKUP=""

get_version_from_pom() {
    if [ -f "${PROJECT_ROOT}/fe/pom.xml" ]; then
        mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "${PROJECT_ROOT}/fe/pom.xml" 2>/dev/null || echo ""
    fi
}

get_artifact_id_from_pom() {
    if [ -f "${PROJECT_ROOT}/fe/pom.xml" ]; then
        mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout -f "${PROJECT_ROOT}/fe/pom.xml" 2>/dev/null || echo ""
    fi
}

usage() {
    cat <<'EOF'
Usage: deploy/build.sh [OPTIONS]

Build StarRocks Docker images.

Options:
  -v, --version VERSION       Version tag (default: read from fe/pom.xml)
  -a, --artifact-id ID        Artifact ID label (default: starrocks-fe)
  -i, --image-name NAME       Docker image repository (default: devhub.baymax.oppoer.me/starrocks/starrocks-allin1)
  -t, --tag TAG               Docker image tag (default: same as version)
      --target TARGET         Image type: allin1-ubi | k8s | k8s-ubi | fe | allin1 | artifacts | be (default: allin1-ubi)
      --artifact-source SRC   For allin1/be/k8s: local | docker (default: docker)
      --dev-env-image IMAGE   Builder image for artifacts (default: centos7 for ubi, ubuntu otherwise)
  -f, --dockerfile FILE       Custom Dockerfile path
      --push                  Push image to registry after build
  -h, --help                  Show this help message

Examples:
  ./deploy/build.sh
  ./deploy/build.sh --target allin1-ubi --artifact-source docker --push
  ./deploy/build.sh --target allin1-ubi --artifact-source local
  ./deploy/build.sh --target fe --version 3.4.0
  ./deploy/build.sh --target k8s --artifact-source local --image-name <registry>/starrocks/starrocks --tag 3.4.0-ubuntu-amd64-20260629
  ./deploy/build.sh --target k8s-ubi --artifact-source local   # requires centos7-built BE
EOF
}

while [ $# -gt 0 ]; do
    case $1 in
        --version|-v) VERSION="$2"; shift 2 ;;
        --artifact-id|-a) ARTIFACT_ID="$2"; shift 2 ;;
        --image-name|-i) IMAGE_NAME="$2"; shift 2 ;;
        --tag|-t) IMAGE_TAG="$2"; shift 2 ;;
        --target) TARGET="$2"; shift 2 ;;
        --artifact-source) ARTIFACT_SOURCE="$2"; shift 2 ;;
        --dev-env-image) DEV_ENV_IMAGE="$2"; shift 2 ;;
        --dockerfile|-f) DOCKERFILE="$2"; shift 2 ;;
        --push) PUSH=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

if [ -z "${VERSION}" ]; then
    VERSION="$(get_version_from_pom)"
    if [ -z "${VERSION}" ]; then
        echo "Error: version not specified and cannot be read from fe/pom.xml"
        exit 1
    fi
fi

if [ -z "${ARTIFACT_ID}" ] || [ "${ARTIFACT_ID}" = "starrocks-fe" ]; then
    POM_ARTIFACT_ID="$(get_artifact_id_from_pom)"
    if [ -n "${POM_ARTIFACT_ID}" ]; then
        ARTIFACT_ID="${POM_ARTIFACT_ID}"
    fi
fi

if [ -z "${IMAGE_TAG}" ]; then
    IMAGE_TAG="${VERSION}"
fi

case "${TARGET}" in
    fe)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${PROJECT_ROOT}/docker/dockerfiles/fe/fe-ubi.Dockerfile"
        if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
            IMAGE_NAME="devhub.baymax.oppoer.me/starrocks/starrocks-fe"
        fi
        ;;
    allin1-ubi)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${SCRIPT_DIR}/Dockerfile.allin1-ubi"
        ;;
    allin1)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${PROJECT_ROOT}/docker/dockerfiles/allin1/allin1-ubuntu.Dockerfile"
        ;;
    be)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${PROJECT_ROOT}/docker/dockerfiles/be/be-ubuntu.Dockerfile"
        if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
            IMAGE_NAME="devhub.baymax.oppoer.me/starrocks/starrocks-be"
        fi
        ;;
    k8s|starrocks)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${SCRIPT_DIR}/Dockerfile.starrocks-k8s"
        if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
            IMAGE_NAME="oppo-bigdata-registry-vpc.cn-beijing.cr.aliyuncs.com/ostream/starrocks/starrocks"
        fi
        if [ "${IMAGE_TAG}" = "${VERSION}" ]; then
            ARCH="$(uname -m)"
            case "${ARCH}" in
                x86_64) IMAGE_TAG="${VERSION}-ubuntu-amd64" ;;
                aarch64) IMAGE_TAG="${VERSION}-ubuntu-arm64" ;;
                *) IMAGE_TAG="${VERSION}-ubuntu-${ARCH}" ;;
            esac
        fi
        ARTIFACT_SOURCE="${ARTIFACT_SOURCE:-local}"
        ;;
    k8s-ubi)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${SCRIPT_DIR}/Dockerfile.starrocks-k8s-ubi"
        if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
            IMAGE_NAME="devhub.baymax.oppoer.me/starrocks/starrocks"
        fi
        if [ "${IMAGE_TAG}" = "${VERSION}" ]; then
            ARCH="$(uname -m)"
            case "${ARCH}" in
                x86_64) IMAGE_TAG="${VERSION}-centos-amd64" ;;
                aarch64) IMAGE_TAG="${VERSION}-centos-arm64" ;;
                *) IMAGE_TAG="${VERSION}-centos-${ARCH}" ;;
            esac
        fi
        ARTIFACT_SOURCE="${ARTIFACT_SOURCE:-local}"
        ;;
    artifacts)
        [ -z "${DOCKERFILE}" ] && DOCKERFILE="${PROJECT_ROOT}/docker/dockerfiles/artifacts/artifact.Dockerfile"
        if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
            IMAGE_NAME="devhub.baymax.oppoer.me/starrocks/starrocks-artifacts"
        fi
        ;;
    *)
        echo "Error: unsupported target '${TARGET}', expected allin1-ubi|k8s|k8s-ubi|fe|allin1|artifacts|be"
        exit 1
        ;;
esac

if [ -z "${DEV_ENV_IMAGE}" ]; then
    DEV_ENV_IMAGE="starrocks/dev-env-centos7:4.1-latest"
fi

if [ ! -f "${DOCKERFILE}" ]; then
    echo "Error: Dockerfile not found at ${DOCKERFILE}"
    exit 1
fi

check_centos_image_pull() {
    if [[ "${DEV_ENV_IMAGE}" != *centos* ]]; then
        return 0
    fi
    echo "Preflight: checking CentOS image layer compatibility..."
    if docker pull centos:7 >/tmp/starrocks-centos-preflight.log 2>&1; then
        echo "Preflight OK: CentOS images can be pulled"
        return 0
    fi
    if grep -q 'write /etc/yum.conf: bad file descriptor' /tmp/starrocks-centos-preflight.log; then
        cat <<'EOF'

ERROR: 无法拉取 CentOS 基础镜像（/etc/yum.conf: bad file descriptor）

根因：本机安全软件拦截了 Docker 在解压镜像层时写入 /etc/yum.conf。
这与 StarRocks 代码无关，Ubuntu 镜像可正常拉取，CentOS 镜像均会失败。

可选方案：
  1. [推荐-生产UBI] 联系 IT 放行 Docker 存储目录（/var/lib/docker）的镜像解压
  2. 在无安全拦截的构建机编译后推送到 devhub.baymax.oppoer.me
  3. [临时-开发测试] 使用 Ubuntu 路线：
       ./deploy/build.sh --target allin1 --artifact-source docker \\
         --dev-env-image starrocks/dev-env-ubuntu:latest \\
         -f docker/dockerfiles/allin1/allin1-ubuntu.Dockerfile

EOF
        exit 1
    fi
    echo "Preflight failed, see /tmp/starrocks-centos-preflight.log"
    cat /tmp/starrocks-centos-preflight.log
    exit 1
}

prepare_dockerignore() {
    if [ -f "${PROJECT_ROOT}/.dockerignore" ]; then
        DOCKERIGNORE_BACKUP="$(mktemp)"
        cp "${PROJECT_ROOT}/.dockerignore" "${DOCKERIGNORE_BACKUP}"
    fi
    cat "${SCRIPT_DIR}/.dockerignore" > "${PROJECT_ROOT}/.dockerignore"
}

restore_dockerignore() {
    if [ -n "${DOCKERIGNORE_BACKUP}" ] && [ -f "${DOCKERIGNORE_BACKUP}" ]; then
        mv "${DOCKERIGNORE_BACKUP}" "${PROJECT_ROOT}/.dockerignore"
    elif [ -f "${PROJECT_ROOT}/.dockerignore" ]; then
        rm -f "${PROJECT_ROOT}/.dockerignore"
    fi
}

trap restore_dockerignore EXIT
prepare_dockerignore

if [ "${TARGET}" = "allin1-ubi" ] || [ "${TARGET}" = "artifacts" ]; then
    check_centos_image_pull
fi

echo "=========================================="
echo "Building StarRocks Docker Image"
echo "=========================================="
echo "Target:      ${TARGET}"
echo "Artifact ID: ${ARTIFACT_ID}"
echo "Version:     ${VERSION}"
echo "Image Name:  ${IMAGE_NAME}"
echo "Image Tag:   ${IMAGE_TAG}"
echo "Dockerfile:  ${DOCKERFILE}"
echo "Dev Env:     ${DEV_ENV_IMAGE}"
echo "Artifact Src:${ARTIFACT_SOURCE}"
echo "=========================================="

cd "${PROJECT_ROOT}"
export DOCKER_BUILDKIT=1

build_fe_image() {
    if [ "${ARTIFACT_SOURCE}" = "docker" ]; then
        build_runtime_from_artifacts "${DOCKERFILE}" "$(get_artifact_image_name)"
        return
    fi
    if [ ! -d "${PROJECT_ROOT}/output/fe" ]; then
        echo "Error: ${PROJECT_ROOT}/output/fe not found."
        echo "Run './build.sh --fe --clean' first, or use --artifact-source docker."
        exit 1
    fi
    docker build \
        -f "${DOCKERFILE}" \
        --build-arg ARTIFACT_SOURCE=local \
        --build-arg LOCAL_REPO_PATH=. \
        -t "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
}

build_artifacts_image() {
    docker build \
        -f "${DOCKERFILE}" \
        --build-arg builder="${DEV_ENV_IMAGE}" \
        --build-arg RELEASE_VERSION="${VERSION}" \
        -t "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
}

build_runtime_from_artifacts() {
    local runtime_dockerfile="$1"
    local artifact_image="$2"
    docker build \
        -f "${runtime_dockerfile}" \
        --build-arg ARTIFACT_SOURCE=image \
        --build-arg ARTIFACTIMAGE="${artifact_image}" \
        -t "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
}

build_runtime_from_local() {
    local runtime_dockerfile="$1"
    if [ ! -d "${PROJECT_ROOT}/output/fe" ]; then
        echo "Error: ${PROJECT_ROOT}/output/fe not found."
        echo "Run './build.sh --fe --clean' first, or use --artifact-source docker."
        exit 1
    fi
    if [ "${TARGET}" = "allin1" ] || [ "${TARGET}" = "allin1-ubi" ]; then
        if [ ! -d "${PROJECT_ROOT}/output/be" ]; then
            echo "Error: ${PROJECT_ROOT}/output/be not found."
            echo "Run './build.sh --fe --be --clean' first, or use --artifact-source docker."
            exit 1
        fi
    fi
    if [ -d "${PROJECT_ROOT}/output/be/lib/hive-reader-lib" ]; then
        install_jindo_for_be_output_optional
    fi
    docker build \
        -f "${runtime_dockerfile}" \
        --build-arg ARTIFACT_SOURCE=local \
        --build-arg LOCAL_REPO_PATH=. \
        -t "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
}

get_artifact_image_name() {
    if [ "${IMAGE_NAME}" = "${DEFAULT_IMAGE_NAME}" ]; then
        echo "devhub.baymax.oppoer.me/starrocks/starrocks-artifacts:${IMAGE_TAG}"
    else
        local base="${IMAGE_NAME%/*}/starrocks-artifacts"
        echo "${base}:${IMAGE_TAG}"
    fi
}

build_allin1_image() {
    if [ "${ARTIFACT_SOURCE}" = "docker" ]; then
        ARTIFACT_IMAGE="$(get_artifact_image_name)"
        echo "Building artifacts image ${ARTIFACT_IMAGE} ..."
        echo "Builder image: ${DEV_ENV_IMAGE}"
        docker build \
            -f "${PROJECT_ROOT}/docker/dockerfiles/artifacts/artifact.Dockerfile" \
            --build-arg builder="${DEV_ENV_IMAGE}" \
            --build-arg RELEASE_VERSION="${VERSION}" \
            -t "${ARTIFACT_IMAGE}" \
            .
        build_runtime_from_artifacts "${DOCKERFILE}" "${ARTIFACT_IMAGE}"
    else
        build_runtime_from_local "${DOCKERFILE}"
    fi
}

copy_hadoop_native_libs() {
    local native_dir="${SCRIPT_DIR}/thirdparty/hadoop-native"
    for target in \
        "${PROJECT_ROOT}/output/be/lib/hadoop/native" \
        "${PROJECT_ROOT}/output/fe/lib/hadoop/native"; do
        if [ ! -d "${native_dir}" ] || [ -z "$(ls -A "${native_dir}" 2>/dev/null)" ]; then
            echo "Warning: ${native_dir} empty, skip Hadoop native libs (libhadoop.so)"
            return 0
        fi
        echo "Installing Hadoop native libs into ${target} ..."
        mkdir -p "${target}"
        if ! cp -a "${native_dir}/." "${target}/" 2>/dev/null; then
            sudo mkdir -p "${target}"
            sudo cp -a "${native_dir}/." "${target}/"
        fi
    done
}

copy_jindo_to_hive_reader() {
    local jindo_dir="${SCRIPT_DIR}/thirdparty/jindo"
    local target="${PROJECT_ROOT}/output/be/lib/hive-reader-lib"
    if [ ! -d "${jindo_dir}" ] || ! ls "${jindo_dir}"/jindo-*.jar &>/dev/null; then
        echo "Warning: Jindo OSS jars missing in ${jindo_dir}/, skip hive-reader-lib install"
        return 0
    fi
    if [ ! -d "${target}" ]; then
        echo "Warning: ${target} not found, skip Jindo hive-reader-lib install"
        return 0
    fi
    echo "Installing Jindo OSS libs into output/be/lib/hive-reader-lib ..."
    rm -f "${target}"/jindo-*.jar
    if ! cp -f "${jindo_dir}"/*.jar "${target}/" 2>/dev/null; then
        sudo rm -f "${target}"/jindo-*.jar
        sudo cp -f "${jindo_dir}"/*.jar "${target}/"
    fi
}

install_jindo_for_be_output() {
    if [ ! -d "${PROJECT_ROOT}/output/be" ]; then
        return 0
    fi
    copy_jindo_libs
    copy_jindo_to_hive_reader
}

install_jindo_for_be_output_optional() {
    local jindo_dir="${SCRIPT_DIR}/thirdparty/jindo"
    if [ ! -d "${jindo_dir}" ] || ! ls "${jindo_dir}"/jindo-*.jar &>/dev/null; then
        echo "Warning: Jindo OSS jars missing in ${jindo_dir}/, skip Jindo install for BE output"
        return 0
    fi
    install_jindo_for_be_output
}

sync_fe_hadoop_libs() {
    local be_common="${PROJECT_ROOT}/output/be/lib/hadoop/common"
    local fe_common="${PROJECT_ROOT}/output/fe/lib/hadoop/common"
    if [ ! -d "${be_common}" ]; then
        return 0
    fi
    echo "Syncing Hadoop common libs (incl. Jindo) to output/fe/lib/hadoop/common ..."
    mkdir -p "${fe_common}"
    if ! cp -a "${be_common}/." "${fe_common}/" 2>/dev/null; then
        sudo mkdir -p "${fe_common}"
        sudo cp -a "${be_common}/." "${fe_common}/"
    fi
}

remove_stub_hadoop_xml() {
    # K8s mounts real oss/hdfs config via HADOOP_CONF_DIR; stub core-site.xml must not win.
    for f in \
        "${PROJECT_ROOT}/output/fe/conf/core-site.xml" \
        "${PROJECT_ROOT}/output/be/conf/core-site.xml"; do
        if [ -f "${f}" ]; then
            echo "Removing stub ${f} (use HADOOP_CONF_DIR ConfigMap instead)"
            rm -f "${f}" 2>/dev/null || sudo rm -f "${f}"
        fi
    done
}

sync_startup_scripts() {
    # Pick up classpath fixes without full FE/BE rebuild.
    cp -f "${PROJECT_ROOT}/bin/start_fe.sh" "${PROJECT_ROOT}/output/fe/bin/start_fe.sh"
    cp -f "${PROJECT_ROOT}/bin/start_backend.sh" "${PROJECT_ROOT}/output/be/bin/start_backend.sh"
    cp -f "${PROJECT_ROOT}/bin/start_cn.sh" "${PROJECT_ROOT}/output/be/bin/start_cn.sh"
}

prepare_k8s_hadoop_runtime() {
    ensure_jindo_libs
    ensure_hadoop_native
    install_jindo_for_be_output
    copy_hadoop_native_libs
    sync_fe_hadoop_libs
    remove_stub_hadoop_xml
    sync_startup_scripts
}

ensure_jindo_libs() {
    local jindo_dir="${SCRIPT_DIR}/thirdparty/jindo"
    if [ ! -d "${jindo_dir}" ] || ! ls "${jindo_dir}"/jindo-*.jar &>/dev/null; then
        echo "Error: Jindo OSS jars missing in ${jindo_dir}/"
        echo "  Ubuntu22 x86_64 CN needs:"
        echo "    jindo-core-6.8.0-nextarch.jar"
        echo "    jindo-core-linux-ubuntu22-x86_64-6.8.0-nextarch.jar"
        echo "    jindo-sdk-6.8.0-nextarch.jar"
        echo "  Copy from cloud-commons CN /opt/starrocks/be/lib/hadoop/common/ or internal artifact."
        exit 1
    fi
}

ensure_hadoop_native() {
    local native_dir="${SCRIPT_DIR}/thirdparty/hadoop-native"
    if [ ! -e "${native_dir}/libhdfs.so" ]; then
        echo "hadoop-native not found, running fetch-hadoop-native.sh ..."
        bash "${SCRIPT_DIR}/scripts/fetch-hadoop-native.sh"
    fi
}

copy_jindo_libs() {
    local jindo_dir="${SCRIPT_DIR}/thirdparty/jindo"
    local target="${PROJECT_ROOT}/output/be/lib/hadoop/common"
    ensure_jindo_libs
    if [ ! -d "${target}" ]; then
        echo "Error: ${target} not found."
        exit 1
    fi
    echo "Installing Jindo OSS libs into output/be/lib/hadoop/common ..."
    rm -f "${target}"/jindo-*.jar
    if ! cp -f "${jindo_dir}"/*.jar "${target}/" 2>/dev/null; then
        sudo rm -f "${target}"/jindo-*.jar
        sudo cp -f "${jindo_dir}"/*.jar "${target}/"
    fi
}

build_k8s_image() {
    if [ ! -d "${PROJECT_ROOT}/output/fe" ] || [ ! -d "${PROJECT_ROOT}/output/be" ]; then
        echo "Error: output/fe or output/be not found."
        echo "Build artifacts first, e.g. in dev-env: ./build.sh --fe --be --clean -j 4"
        exit 1
    fi
    prepare_k8s_hadoop_runtime
    DOCKER_BUILDKIT=0 docker build \
        -f "${DOCKERFILE}" \
        --build-arg LOCAL_REPO_PATH=. \
        -t "${IMAGE_NAME}:${IMAGE_TAG}" \
        .
}

case "${TARGET}" in
    fe)
        build_fe_image
        ;;
    artifacts)
        build_artifacts_image
        ;;
    allin1|allin1-ubi)
        build_allin1_image
        ;;
    k8s|starrocks|k8s-ubi)
        build_k8s_image
        ;;
    be)
        if [ "${ARTIFACT_SOURCE}" = "docker" ]; then
            ARTIFACT_IMAGE="$(get_artifact_image_name)"
            build_runtime_from_artifacts "${DOCKERFILE}" "${ARTIFACT_IMAGE}"
        else
            build_runtime_from_local "${DOCKERFILE}"
        fi
        ;;
esac

if [ "${IMAGE_TAG}" = "${VERSION}" ] && [ "${IMAGE_TAG}" != "latest" ]; then
    echo ""
    echo "Tagging as ${IMAGE_NAME}:latest ..."
    docker tag "${IMAGE_NAME}:${IMAGE_TAG}" "${IMAGE_NAME}:latest"
fi

if [ "${PUSH}" = "true" ]; then
    echo ""
    echo "=========================================="
    echo "Pushing Docker Images"
    echo "=========================================="
    docker push "${IMAGE_NAME}:${IMAGE_TAG}"
    if [ "${IMAGE_TAG}" = "${VERSION}" ] && [ "${IMAGE_TAG}" != "latest" ]; then
        docker push "${IMAGE_NAME}:latest"
    fi
fi

echo ""
echo "=========================================="
echo "Build completed successfully!"
echo "=========================================="
echo "Image: ${IMAGE_NAME}:${IMAGE_TAG}"
if [ "${IMAGE_TAG}" = "${VERSION}" ] && [ "${IMAGE_TAG}" != "latest" ]; then
    echo "Image: ${IMAGE_NAME}:latest"
fi
echo ""

case "${TARGET}" in
    fe)
        cat <<EOF
StarRocks FE container
  Query port: 9030
  HTTP port:  8030

Run:
  docker run -d --name starrocks-fe \\
    -p 8030:8030 -p 9030:9030 \\
    -v starrocks-fe-meta:/opt/starrocks/fe/meta \\
    -v starrocks-fe-log:/opt/starrocks/fe/log \\
    ${IMAGE_NAME}:${IMAGE_TAG}
EOF
        ;;
    allin1|allin1-ubi)
        cat <<EOF
StarRocks all-in-one container (FE + BE, UBI/CentOS compatible)

Run:
  docker run -d --name starrocks-allin1 \\
    -p 8030:8030 -p 9030:9030 -p 8040:8040 \\
    ${IMAGE_NAME}:${IMAGE_TAG}
EOF
        ;;
    k8s|starrocks|k8s-ubi)
        cat <<EOF
StarRocks unified K8s image (FE/CN share one image)

FE StatefulSet:
  command: ["/opt/starrocks/fe_entrypoint.sh"]

CN StatefulSet:
  command: ["/opt/starrocks/cn_entrypoint.sh"]

Image: ${IMAGE_NAME}:${IMAGE_TAG}
EOF
        ;;
esac
