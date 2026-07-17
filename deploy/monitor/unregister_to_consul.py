# coding: utf-8
"""Deregister StarRocks metrics service from Consul on preStop."""
from __future__ import print_function

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
metrics_port = int(os.environ.get("METRICS_PORT", "8030"))
service_id = "{}-{}".format(ip_address, metrics_port)


def unregister():
    url = "http://{}/v1/agent/service/deregister/{}".format(consul_address, service_id)
    max_retries = 10
    for retries in range(max_retries):
        try:
            response = requests.put(url, timeout=10)
        except requests.RequestException as exc:
            seconds = retries + 1
            print("Unregistration request error: {} Retrying in {} second...".format(exc, seconds))
            time.sleep(seconds)
            continue
        if response.status_code == 200:
            print("Unregistration successful with url:" + url)
            return
        seconds = retries + 1
        print(response.text)
        print(
            "Unregistration failed with status code: {} Retrying in {} second...".format(
                response.status_code, seconds
            )
        )
        time.sleep(seconds)
    print("Maximum retries exceeded. Unregistration failed.")


if __name__ == "__main__":
    unregister()
