#!/usr/bin/env python3
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

"""Register and deregister a StarRocks metrics endpoint with Consul.

The script intentionally uses only the Python standard library so it is usable
by both K8s runtime images without a pip-installed dependency.

Environment:
  CONSUL_ADDRESS: Consul host and optional port.
  CONSUL_REGISTER_META: comma-separated key:value metadata overrides.
  CONSUL_HTTP_TIMEOUT_SECONDS: timeout per request, default 2.
  CONSUL_REGISTER_RETRY_COUNT / CONSUL_DEREGISTER_RETRY_COUNT: attempts, default 3.
  METRICS_PORT: FE defaults to 8030; CN and BE default to 8040.
"""

import json
import os
import socket
import sys
import time
from urllib import error
from urllib import parse
from urllib import request


DEFAULT_CONSUL_ADDRESS = "consul-ums-test.wanyol.com"
DEFAULT_TIMEOUT_SECONDS = 2
DEFAULT_RETRY_COUNT = 3


def positive_int_from_env(name, default):
    try:
        value = int(os.environ.get(name, default))
    except ValueError:
        return default
    return value if value > 0 else default


def resolve_address():
    pod_ip = os.environ.get("POD_IP")
    if pod_ip:
        return pod_ip

    host_name = socket.gethostname()
    try:
        return socket.gethostbyname(host_name)
    except socket.gaierror:
        return socket.gethostbyname(socket.getfqdn())


def parse_metadata(value):
    metadata = {
        "category": "bigdata",
        "dataset": "starrocks",
        "zonecode": "BJHT",
        "instance": socket.gethostname(),
        "metric_path": "/metrics",
    }
    if not value:
        return metadata

    for pair in value.split(","):
        key, separator, item = pair.strip().partition(":")
        if separator and key.strip():
            metadata[key.strip()] = item.strip()
    return metadata


def request_consul(method, url, body, timeout_seconds):
    headers = {"Content-Type": "application/json"} if body else {}
    payload = json.dumps(body).encode("utf-8") if body else None
    req = request.Request(url, data=payload, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=timeout_seconds) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except (error.URLError, OSError) as exc:
        return None, str(exc)


def execute(action):
    address = resolve_address()
    metrics_port = positive_int_from_env("METRICS_PORT", 8030)
    service_id = "{}-{}".format(address, metrics_port)
    consul_address = os.environ.get("CONSUL_ADDRESS", DEFAULT_CONSUL_ADDRESS)
    timeout_seconds = positive_int_from_env("CONSUL_HTTP_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS)
    retry_count = positive_int_from_env("CONSUL_{}_RETRY_COUNT".format(action.upper()), DEFAULT_RETRY_COUNT)

    if action == "register":
        url = "http://{}/v1/agent/service/register".format(consul_address)
        body = {
            "ID": service_id,
            "Name": service_id + "-starrocks-self-monitor",
            "Address": address,
            "Port": metrics_port,
            "Meta": parse_metadata(os.environ.get("CONSUL_REGISTER_META")),
            "EnableTagOverride": True,
        }
    else:
        url = "http://{}/v1/agent/service/deregister/{}".format(
            consul_address, parse.quote(service_id, safe=""))
        body = None

    for attempt in range(1, retry_count + 1):
        status, detail = request_consul("PUT", url, body, timeout_seconds)
        if status is not None and 200 <= status < 300:
            print("Consul {} succeeded for {}".format(action, service_id))
            return 0

        print("Consul {} attempt {}/{} failed: {}".format(action, attempt, retry_count, detail), file=sys.stderr)
        if attempt < retry_count:
            time.sleep(attempt)

    return 1


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("register", "deregister"):
        print("usage: consul_service.py {register|deregister}", file=sys.stderr)
        return 2
    return execute(sys.argv[1])


if __name__ == "__main__":
    sys.exit(main())
