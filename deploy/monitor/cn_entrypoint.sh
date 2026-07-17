#!/bin/bash
# Wrap official cn_entrypoint.sh: register /metrics to Consul before start.
set -euo pipefail
STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
export METRICS_PORT="${METRICS_PORT:-8040}"

if [[ -f "${STARROCKS_ROOT}/register_to_consul.py" ]]; then
  echo "[`date`] register metrics to consul (port=${METRICS_PORT})" >&2
  python3 "${STARROCKS_ROOT}/register_to_consul.py" || \
    echo "[`date`] WARNING: consul register failed, continue starting CN" >&2
fi

exec "${STARROCKS_ROOT}/cn_entrypoint.orig.sh" "$@"
