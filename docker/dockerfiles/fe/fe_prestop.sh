#!/bin/bash

STARROCKS_ROOT=${STARROCKS_ROOT:-"/opt/starrocks"}
STARROCKS_HOME=${STARROCKS_ROOT}/fe
$STARROCKS_HOME/bin/stop_fe.sh
stop_status=$?

if [[ "${CFS_MOUNT_ENABLED:-false}" =~ ^(1|true|TRUE|yes|YES)$ ]]; then
    CFS_MOUNT_PATH=${CFS_MOUNT_PATH:-/home/service/var/starrocks}
    if mountpoint -q "$CFS_MOUNT_PATH"; then
        umount "$CFS_MOUNT_PATH" || echo "[$(date)] Failed to unmount CFS at $CFS_MOUNT_PATH" >&2
    fi
fi

exit "$stop_status"
