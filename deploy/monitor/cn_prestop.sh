#!/bin/bash
# Wrap official cn_prestop.sh: deregister from Consul then stop CN.
set -euo pipefail
STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
export METRICS_PORT="${METRICS_PORT:-8040}"

if [[ -f "${STARROCKS_ROOT}/unregister_to_consul.py" ]]; then
  echo "[`date`] unregister metrics from consul (port=${METRICS_PORT})" >&2
  python3 "${STARROCKS_ROOT}/unregister_to_consul.py" || true
fi

exec "${STARROCKS_ROOT}/cn_prestop.orig.sh" "$@"
