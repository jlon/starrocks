#!/bin/bash
# India BE entrypoint: mount ChubaoFS, then register Consul, then start BE.
set -euo pipefail
STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
export METRICS_PORT="${METRICS_PORT:-8040}"

if [[ -f "${STARROCKS_ROOT}/india/mount-chubaofs.sh" ]]; then
  echo "[$(date)] India CFS: mounting ChubaoFS before BE start" >&2
  bash "${STARROCKS_ROOT}/india/mount-chubaofs.sh"
fi

if [[ -f "${STARROCKS_ROOT}/register_to_consul.py" ]]; then
  echo "[$(date)] register metrics to consul (port=${METRICS_PORT})" >&2
  python3 "${STARROCKS_ROOT}/register_to_consul.py" || \
    echo "[$(date)] WARNING: consul register failed, continue starting BE" >&2
fi

exec "${STARROCKS_ROOT}/be_entrypoint.orig.sh" "$@"
