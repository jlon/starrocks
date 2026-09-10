#!/bin/bash
# India-only ChubaoFS (CubeFS) mount. Do not include this in non-India images.
set -euo pipefail

CFS_MOUNT_POINT="${CFS_MOUNT_POINT:-/home/service/var/chubaofs}"
CFS_VOL_NAME="${CFS_VOL_NAME:-ostream-flink}"
CFS_OWNER="${CFS_OWNER:-prod_UK79U81r}"
CFS_ACCESS_KEY="${CFS_ACCESS_KEY:-nFZ902AODDe079yO}"
CFS_SECRET_KEY="${CFS_SECRET_KEY:-Dx02wvK6HtSsjxr38n29Yo4FCfN8j36W}"
CFS_MASTER_ADDR="${CFS_MASTER_ADDR:-cfs-india-ctrls.oppo.local}"
CFS_LOG_DIR="${CFS_LOG_DIR:-/home/service/var/logs/cfs/log}"
CFS_APP_DIR="${CFS_APP_DIR:-/home/service/app/cfs/${CFS_VOL_NAME}}"
CFS_CLIENT="${CFS_CLIENT:-${CFS_APP_DIR}/cfs-client}"
CFS_CONFIG="${CFS_CONFIG:-${CFS_APP_DIR}/client.conf}"
CFS_RDONLY="${CFS_RDONLY:-false}"
CFS_LOG_LEVEL="${CFS_LOG_LEVEL:-warn}"
CFS_SUBDIR="${CFS_SUBDIR:-/}"

log() {
  echo "[$(date)] [india-cfs] $*" >&2
}

if [[ "${SKIP_CHUBAOFS_MOUNT:-0}" == "1" ]]; then
  log "SKIP_CHUBAOFS_MOUNT=1, skip mount"
  return 0 2>/dev/null || exit 0
fi

if [[ ! -x "${CFS_CLIENT}" ]]; then
  log "ERROR: cfs-client not found: ${CFS_CLIENT}"
  exit 1
fi

if mountpoint -q "${CFS_MOUNT_POINT}" 2>/dev/null; then
  log "already mounted at ${CFS_MOUNT_POINT}"
  return 0 2>/dev/null || exit 0
fi

umount "${CFS_MOUNT_POINT}" 2>/dev/null || true
fusermount -u "${CFS_MOUNT_POINT}" 2>/dev/null || true

mkdir -p "${CFS_MOUNT_POINT}" "${CFS_LOG_DIR}" "${CFS_APP_DIR}"

if [[ -f /etc/fuse.conf ]]; then
  sed -i '/user_allow_other/s/^#\s*//' /etc/fuse.conf || true
  grep -q '^user_allow_other' /etc/fuse.conf || echo 'user_allow_other' >> /etc/fuse.conf
fi

cat > "${CFS_CONFIG}" <<EOF
{
    "mountPoint":"${CFS_MOUNT_POINT}",
    "volName":"${CFS_VOL_NAME}",
    "owner":"${CFS_OWNER}",
    "accessKey":"${CFS_ACCESS_KEY}",
    "secretKey":"${CFS_SECRET_KEY}",
    "masterAddr":"${CFS_MASTER_ADDR}",
    "logDir":"${CFS_LOG_DIR}",
    "rdonly":"${CFS_RDONLY}",
    "logLevel":"${CFS_LOG_LEVEL}",
    "subdir":"${CFS_SUBDIR}"
}
EOF

log "starting cfs-client vol=${CFS_VOL_NAME} master=${CFS_MASTER_ADDR} mount=${CFS_MOUNT_POINT}"
"${CFS_CLIENT}" -c "${CFS_CONFIG}"

sleep 3
if mountpoint -q "${CFS_MOUNT_POINT}" 2>/dev/null || mount | grep -q " ${CFS_MOUNT_POINT} "; then
  log "mounted ${CFS_MOUNT_POINT}"
  return 0 2>/dev/null || exit 0
fi

log "ERROR: mount failed, last cfs log:"
tail -n 50 "${CFS_LOG_DIR}"/* 2>/dev/null || true
exit 1
