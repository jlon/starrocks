#!/bin/bash
# Extract libhadoop.so / libhdfs.so from Apache Hadoop 3.4.0 into deploy/thirdparty/hadoop-native.
# Download happens at most once per machine; later builds reuse .cache/ tarball + hadoop-native/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIRDPARTY="${SCRIPT_DIR}/../thirdparty"
DEST="${THIRDPARTY}/hadoop-native"
CACHE_DIR="${THIRDPARTY}/.cache"
HADOOP_VERSION="3.4.0"
TARBALL="hadoop-${HADOOP_VERSION}.tar.gz"
TARBALL_URL="https://archive.apache.org/dist/hadoop/common/hadoop-${HADOOP_VERSION}/${TARBALL}"
CACHE="${CACHE_DIR}/${TARBALL}"

hadoop_native_ready() {
    [ -e "${DEST}/libhdfs.so" ] || [ -L "${DEST}/libhdfs.so" ]
}

if hadoop_native_ready; then
    echo "hadoop-native already present: ${DEST} (skip fetch)"
    exit 0
fi

mkdir -p "${CACHE_DIR}" "${DEST}"

if [ ! -f "${CACHE}" ]; then
    if [ -f "/tmp/${TARBALL}" ]; then
        echo "Using existing /tmp/${TARBALL} as cache ..."
        cp -f "/tmp/${TARBALL}" "${CACHE}"
    else
        echo "Downloading Hadoop ${HADOOP_VERSION} (${TARBALL}, one-time) ..."
        curl -fsSL -o "${CACHE}.partial" "${TARBALL_URL}"
        mv -f "${CACHE}.partial" "${CACHE}"
    fi
else
    echo "Using cached tarball: ${CACHE}"
fi

echo "Extracting lib/native into ${DEST} ..."
rm -rf "${DEST}"
mkdir -p "${DEST}"
tar -xzf "${CACHE}" -C "${CACHE_DIR}" "hadoop-${HADOOP_VERSION}/lib/native"
cp -a "${CACHE_DIR}/hadoop-${HADOOP_VERSION}/lib/native/." "${DEST}/"
rm -rf "${CACHE_DIR}/hadoop-${HADOOP_VERSION}"
echo "Done: hadoop-native ready ($(du -sh "${DEST}" | cut -f1))"
