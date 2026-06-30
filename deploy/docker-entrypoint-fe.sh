#!/bin/bash
set -e

STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
export STARROCKS_HOME="${STARROCKS_HOME:-${STARROCKS_ROOT}/fe}"
export PID_DIR="${PID_DIR:-${STARROCKS_HOME}/bin}"
export LOG_DIR="${LOG_DIR:-${STARROCKS_HOME}/log}"
export TZ="${TZ:-Asia/Shanghai}"

mkdir -p "${LOG_DIR}" "${STARROCKS_HOME}/meta"

case "${1:-foreground}" in
    foreground)
        exec "${STARROCKS_HOME}/bin/start_fe.sh" --logconsole
        ;;
    daemon)
        exec "${STARROCKS_HOME}/bin/start_fe.sh" --daemon
        ;;
    *)
        exec "${STARROCKS_HOME}/bin/start_fe.sh" "$@"
        ;;
esac
