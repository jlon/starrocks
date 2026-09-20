#!/usr/bin/env bash
# Copyright 2021-present StarRocks, Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

STARROCKS_ROOT="${STARROCKS_ROOT:-/opt/starrocks}"
SCRIPT_NAME="${0##*/}"

case "${SCRIPT_NAME}" in
    fe_entrypoint.sh) DEFAULT_METRICS_PORT=8030 ;;
    cn_entrypoint.sh|be_entrypoint.sh) DEFAULT_METRICS_PORT=8040 ;;
    *)
        echo "unexpected monitoring entrypoint: ${SCRIPT_NAME}" >&2
        exit 2
        ;;
esac

export METRICS_PORT="${METRICS_PORT:-${DEFAULT_METRICS_PORT}}"
if ! python3 "${STARROCKS_ROOT}/consul_service.py" register; then
    echo "[$(date)] WARNING: Consul registration failed; continuing startup" >&2
fi

exec "${STARROCKS_ROOT}/${SCRIPT_NAME%.sh}.orig.sh" "$@"
