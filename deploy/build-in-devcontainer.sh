#!/bin/bash
# 使用 StarRocks 官方 dev-env 镜像在容器内编译，再打包 Ubuntu allin1 运行时镜像。
# 文档: docs/zh/developers/build-starrocks/Build_in_docker.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# sudo 下 HOME 会变成 /root，Maven 缓存应挂真实用户目录
resolve_user_home() {
    if [ -n "${SUDO_USER:-}" ] && [ "${SUDO_USER}" != "root" ]; then
        getent passwd "${SUDO_USER}" | cut -d: -f6
    else
        echo "${HOME}"
    fi
}
USER_HOME="$(resolve_user_home)"

BRANCH_NAME="${BRANCH_NAME:-$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD)}"
CONTAINER_NAME="${CONTAINER_NAME:-sr-build-${BRANCH_NAME}}"

# 按分支选 dev-env 镜像。4.1 使用与生产 BE 兼容的 CentOS 7 工具链。
default_dev_env_image() {
    case "${BRANCH_NAME}" in
        branch-4.1*|4.1*) echo "starrocks/dev-env-centos7:4.1-latest" ;;
        main|master) echo "starrocks/dev-env-ubuntu:latest" ;;
        branch-3.5|3.5) echo "starrocks/dev-env-ubuntu:3.5-latest" ;;
        branch-3.4|3.4) echo "starrocks/dev-env-ubuntu:3.4-latest" ;;
        branch-3.3|3.3) echo "starrocks/dev-env-ubuntu:3.3-latest" ;;
        branch-3.2|3.2) echo "starrocks/dev-env-ubuntu:3.2-latest" ;;
        branch-3.1|3.1) echo "starrocks/dev-env-ubuntu:3.1-latest" ;;
        branch-3.0|3.0) echo "starrocks/dev-env-ubuntu:3.0-latest" ;;
        branch-2.5|2.5) echo "starrocks/dev-env-ubuntu:2.5-latest" ;;
        *) echo "starrocks/dev-env-ubuntu:latest" ;;
    esac
}
DEV_ENV_IMAGE="${DEV_ENV_IMAGE:-$(default_dev_env_image)}"
M2_DIR="${M2_DIR:-${USER_HOME}/.m2}"
RECREATE_CONTAINER="${RECREATE_CONTAINER:-false}"
ENABLE_SHARED_DATA="${ENABLE_SHARED_DATA:-true}"
BUILD_LOG="${BUILD_LOG:-${PROJECT_ROOT}/build-devcontainer.log}"
IMAGE_NAME="${IMAGE_NAME:-devhub.baymax.oppoer.me/starrocks/starrocks-allin1}"
IMAGE_TAG="${IMAGE_TAG:-$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f "${PROJECT_ROOT}/fe/pom.xml" 2>/dev/null || echo latest)}"
PACKAGE_ONLY="${PACKAGE_ONLY:-false}"
BUILD_PARALLEL="${BUILD_PARALLEL:-28}"
CONTAINER_MEMORY="${CONTAINER_MEMORY:-24g}"
CONTAINER_MEMORY_SWAP="${CONTAINER_MEMORY_SWAP:-32g}"

usage() {
    cat <<EOF
Usage: deploy/build-in-devcontainer.sh [OPTIONS]

在 dev-env 容器内编译 StarRocks，并打包 Ubuntu allin1 镜像（x86/amd64）。

Options:
  --image IMAGE          dev-env 镜像 (branch-4.1*: starrocks/dev-env-centos7:4.1-latest)
  --container NAME       容器名 (default: sr-build-<branch>)
  --tag TAG              输出镜像 tag (default: fe/pom.xml 版本)
  --package-only         跳过编译，仅用已有 output/ 打运行时镜像
  --no-shared-data       编译 BE 时不加 --enable-shared-data
  --recreate             删除并重建 dev-env 容器（挂载或镜像变更时用）
  -h, --help             显示帮助

挂载说明（与官方文档一致，在项目根目录执行）:
  -v \$(pwd):/root/starrocks          # 当前 StarRocks 源码目录
  -v ~/.m2:/root/.m2                  # Maven 本地仓库（sudo 时自动解析为真实用户）

示例:
  ./deploy/build-in-devcontainer.sh
  ./deploy/build-in-devcontainer.sh --recreate
  ./deploy/build-in-devcontainer.sh --package-only
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --image) DEV_ENV_IMAGE="$2"; shift 2 ;;
        --container) CONTAINER_NAME="$2"; shift 2 ;;
        --tag) IMAGE_TAG="$2"; shift 2 ;;
        --package-only) PACKAGE_ONLY=true; shift ;;
        --no-shared-data) ENABLE_SHARED_DATA=false; shift ;;
        --recreate) RECREATE_CONTAINER=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

mkdir -p "${M2_DIR}"

start_container() {
    if [ "${RECREATE_CONTAINER}" = "true" ]; then
        docker rm -f "${CONTAINER_NAME}" 2>/dev/null || true
    fi

    if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
        local src_mount m2_mount
        src_mount="$(docker inspect "${CONTAINER_NAME}" --format '{{range .Mounts}}{{if eq .Destination "/root/starrocks"}}{{.Source}}{{end}}{{end}}')"
        m2_mount="$(docker inspect "${CONTAINER_NAME}" --format '{{range .Mounts}}{{if eq .Destination "/root/.m2"}}{{.Source}}{{end}}{{end}}')"
        if [ "${src_mount}" != "${PROJECT_ROOT}" ] || [ "${m2_mount}" != "${M2_DIR}" ]; then
            echo "Warning: existing container mounts differ from expected:"
            echo "  starrocks: ${src_mount:-<missing>} (expected ${PROJECT_ROOT})"
            echo "  .m2:       ${m2_mount:-<missing>} (expected ${M2_DIR})"
            echo "Re-run with --recreate to fix."
        fi
        echo "Container ${CONTAINER_NAME} already exists, starting..."
        docker start "${CONTAINER_NAME}" >/dev/null
    else
        echo "Starting dev-env container: ${CONTAINER_NAME}"
        echo "  Image:  ${DEV_ENV_IMAGE}"
        echo "  Source: ${PROJECT_ROOT} -> /root/starrocks"
        echo "  Maven:  ${M2_DIR} -> /root/.m2"
        docker run -d \
            --name "${CONTAINER_NAME}" \
            --memory="${CONTAINER_MEMORY}" \
            --memory-swap="${CONTAINER_MEMORY_SWAP}" \
            -v "${M2_DIR}:/root/.m2" \
            -v "${PROJECT_ROOT}:/root/starrocks" \
            "${DEV_ENV_IMAGE}" \
            sleep infinity
    fi
}

compile_in_container() {
  local build_args="--fe --be --clean"
  if [ "${ENABLE_SHARED_DATA}" = "true" ]; then
      build_args="${build_args} --enable-shared-data"
  fi

  echo "Compiling inside container (log: ${BUILD_LOG})..."
  echo "  ./build.sh ${build_args} -j ${BUILD_PARALLEL}"
  docker exec "${CONTAINER_NAME}" bash -lc "
        set -e
        cd /root/starrocks
        ./build.sh ${build_args} -j ${BUILD_PARALLEL}
    " 2>&1 | tee "${BUILD_LOG}"
}

package_runtime_image() {
    if [ ! -d "${PROJECT_ROOT}/output/fe" ] || [ ! -d "${PROJECT_ROOT}/output/be" ]; then
        echo "Error: output/fe or output/be not found. Compile first."
        exit 1
    fi

    echo "Packaging allin1 Ubuntu runtime image..."
    DOCKER_BUILDKIT=1 "${SCRIPT_DIR}/build.sh" \
        --target allin1 \
        --artifact-source local \
        -f "${PROJECT_ROOT}/docker/dockerfiles/allin1/allin1-ubuntu.Dockerfile" \
        --image-name "${IMAGE_NAME}" \
        --tag "${IMAGE_TAG}"
}

echo "=========================================="
echo "StarRocks dev-env build"
echo "=========================================="
echo "Branch:     ${BRANCH_NAME}"
echo "Container:  ${CONTAINER_NAME}"
echo "Dev image:  ${DEV_ENV_IMAGE}"
echo "Output:     ${IMAGE_NAME}:${IMAGE_TAG}"
echo "=========================================="

start_container

if [ "${PACKAGE_ONLY}" = "false" ]; then
    compile_in_container
fi

package_runtime_image

echo ""
echo "Done: ${IMAGE_NAME}:${IMAGE_TAG}"
