#!/bin/bash
set -euo pipefail

# Assemble FE runtime directory (same layout as build.sh --fe output).
STARROCKS_HOME="${STARROCKS_HOME:-/build}"
OUTPUT_DIR="${OUTPUT_DIR:-${STARROCKS_HOME}/output/fe}"

install -d "${OUTPUT_DIR}/bin" \
         "${OUTPUT_DIR}/conf" \
         "${OUTPUT_DIR}/webroot" \
         "${OUTPUT_DIR}/lib" \
         "${OUTPUT_DIR}/spark-dpp" \
         "${OUTPUT_DIR}/hive-udf" \
         "${OUTPUT_DIR}/oppo-hive-udf"

cp -r -p "${STARROCKS_HOME}/bin/"*_fe.sh "${OUTPUT_DIR}/bin/"
cp -r -p "${STARROCKS_HOME}/bin/show_fe_version.sh" "${OUTPUT_DIR}/bin/"
cp -r -p "${STARROCKS_HOME}/bin/common.sh" "${OUTPUT_DIR}/bin/"
cp -r -p "${STARROCKS_HOME}/conf/fe.conf" "${OUTPUT_DIR}/conf/"
cp -r -p "${STARROCKS_HOME}/conf/udf_security.policy" "${OUTPUT_DIR}/conf/"
cp -r -p "${STARROCKS_HOME}/conf/hadoop_env.sh" "${OUTPUT_DIR}/conf/"
cp -r -p "${STARROCKS_HOME}/conf/core-site.xml" "${OUTPUT_DIR}/conf/"
cp -r -p "${STARROCKS_HOME}/conf/cluster_snapshot.yaml" "${OUTPUT_DIR}/conf/"

rm -rf "${OUTPUT_DIR}/lib/"*
cp -r -p "${STARROCKS_HOME}/fe/fe-server/target/lib/"* "${OUTPUT_DIR}/lib/"
cp -r -p "${STARROCKS_HOME}/fe/fe-server/target/starrocks-fe.jar" "${OUTPUT_DIR}/lib/"
cp -r -p "${STARROCKS_HOME}/java-extensions/hadoop-ext/target/starrocks-hadoop-ext.jar" "${OUTPUT_DIR}/lib/"
cp -r -p "${STARROCKS_HOME}/fe/fe-plugin-shield/target/fe-plugin-shield-"*.jar "${OUTPUT_DIR}/lib/"
cp -r -p "${STARROCKS_HOME}/webroot/"* "${OUTPUT_DIR}/webroot/"
cp -r -p "${STARROCKS_HOME}/fe/plugin/spark-dpp/target/"spark-dpp-*-jar-with-dependencies.jar "${OUTPUT_DIR}/spark-dpp/"
cp -r -p "${STARROCKS_HOME}/fe/plugin/hive-udf/target/"hive-udf-*.jar "${OUTPUT_DIR}/hive-udf/"
if [ -f "${STARROCKS_HOME}/fe/oppo-hive-udf/target/oppo-hive-udf-1.0.0.jar" ]; then
    cp -r -p "${STARROCKS_HOME}/fe/oppo-hive-udf/target/oppo-hive-udf-1.0.0.jar" "${OUTPUT_DIR}/oppo-hive-udf/"
fi

if [ -d "${STARROCKS_HOME}/thirdparty/installed/async-profiler" ]; then
    cp -r -p "${STARROCKS_HOME}/thirdparty/installed/async-profiler" "${OUTPUT_DIR}/bin/"
fi

echo "FE artifacts assembled at ${OUTPUT_DIR}"
