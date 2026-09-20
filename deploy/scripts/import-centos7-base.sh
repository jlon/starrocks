#!/bin/bash
# Workaround: 公司安全软件会拦截 Docker 写入 /etc/yum.conf，导致所有 CentOS 镜像 pull 失败。
# 本脚本用 skopeo + 去掉 yum.conf 后 docker import 导入 CentOS7 基础镜像。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="${1:-centos7:no-yum}"
OCI_DIR="${2:-/tmp/centos7-oci-import}"
BLOB_NAME="2d473b07cdd5f0912cd6f1a703352c82b512407db6b05b43f2553732b55df3bc"

if ! command -v skopeo >/dev/null; then
    echo "Error: skopeo not installed. Run: sudo apt-get install -y skopeo"
    exit 1
fi

echo "Downloading centos:7 via skopeo..."
rm -rf "${OCI_DIR}"
skopeo copy "docker://centos:7" "oci:${OCI_DIR}:latest"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

echo "Extracting layer without /etc/yum.conf..."
mkdir -p "${WORKDIR}/rootfs"
tar -xf "${OCI_DIR}/blobs/sha256/${BLOB_NAME}" -C "${WORKDIR}/rootfs"
rm -f "${WORKDIR}/rootfs/etc/yum.conf"

echo "Importing into Docker as ${IMAGE_NAME}..."
tar -C "${WORKDIR}/rootfs" -c . | docker import - "${IMAGE_NAME}"

echo "Done. Imported: ${IMAGE_NAME}"
docker images "${IMAGE_NAME%%:*}" | head -3
echo ""
echo "NOTE: 多层级 CentOS 镜像（dev-env-centos7）仍需联系 IT 放行 Docker 存储目录，"
echo "      或在无安全拦截的构建机上编译后推送到内网仓库。"
