# coding: utf-8
"""Register StarRocks /metrics endpoint to Consul for UMS/ostream monitoring.

Env:
  CONSUL_ADDRESS        default: consul-ums-test.wanyol.com
  CONSUL_REGISTER_META  optional comma pairs, e.g. zonecode:BJHT,dataset:starrocks
  METRICS_PORT          default: 8030 (FE http); CN/BE usually 8040
"""
from __future__ import print_function

import json
import os
import socket
import time

import requests

host_name = socket.gethostname()
try:
    ip_address = socket.gethostbyname(host_name)
except socket.gaierror:
    ip_address = socket.gethostbyname(socket.getfqdn())

consul_address = os.environ.get("CONSUL_ADDRESS", "consul-ums-test.wanyol.com")
consul_register_meta = os.environ.get("CONSUL_REGISTER_META", None)
metrics_port = int(os.environ.get("METRICS_PORT", "8030"))
service_id = "{}-{}".format(ip_address, metrics_port)

default_meta = {
    "category": "bigdata",
    "dataset": "starrocks",
    "zonecode": "BJHT",
    "instance": host_name,
    "metric_path": "/metrics",
}


def convert_to_map(input_string):
    result_map = {}
    for pair in input_string.split(","):
        pair = pair.strip()
        if not pair or ":" not in pair:
            continue
        key, value = pair.split(":", 1)
        result_map[key.strip()] = value.strip()
    return result_map


if consul_register_meta:
    default_meta.update(convert_to_map(consul_register_meta))


def make_data(ip, port):
    return {
        "ID": service_id,
        "Name": service_id + "-starrocks-self-monitor",
        "Address": ip,
        "Port": int(port),
        "Meta": default_meta,
        "EnableTagOverride": True,
    }


def register():
    url = "http://{}/v1/agent/service/register".format(consul_address)
    print(url)
    headers = {"content-type": "application/json"}
    body = make_data(ip_address, metrics_port)

    max_retries = 10
    for retries in range(max_retries):
        try:
            response = requests.put(url, data=json.dumps(body), headers=headers, timeout=10)
        except requests.RequestException as exc:
            seconds = retries + 1
            print("Registration request error: {} Retrying in {} second...".format(exc, seconds))
            time.sleep(seconds)
            continue
        if response.status_code == 200:
            print("Registration successful with body:" + json.dumps(body))
            return
        seconds = retries + 1
        print(response.text)
        print(
            "Registration failed with status code: {} Retrying in {} second...".format(
                response.status_code, seconds
            )
        )
        time.sleep(seconds)
    print("Maximum retries exceeded. Registration failed.")


if __name__ == "__main__":
    register()
