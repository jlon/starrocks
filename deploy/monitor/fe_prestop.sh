#!/bin/bash
# Wrap official fe_prestop.sh: deregister from Consul then stop FE.
set -euo pipefail
STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
export METRICS_PORT="${METRICS_PORT:-8030}"

if [[ -f "${STARROCKS_ROOT}/unregister_to_consul.py" ]]; then
  echo "[`date`] unregister metrics from consul (port=${METRICS_PORT})" >&2
  python3 "${STARROCKS_ROOT}/unregister_to_consul.py" || true
fi

exec "${STARROCKS_ROOT}/fe_prestop.orig.sh" "$@"
