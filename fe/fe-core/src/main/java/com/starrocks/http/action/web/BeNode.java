// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.http.action.web;

import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;

final class BeNode {
    private final String host;
    private final int port;

    private BeNode(String host, int port) {
        this.host = host;
        this.port = port;
    }

    static BeNode parse(String value) {
        if (value == null || !value.startsWith("BE:")) {
            return null;
        }
        int separator = value.lastIndexOf(':');
        if (separator <= 3 || separator == value.length() - 1) {
            return null;
        }
        String host = value.substring(3, separator);
        try {
            return new BeNode(host, Integer.parseInt(value.substring(separator + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean exists() {
        for (Backend backend : GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackends()) {
            if (backend.getHost().equals(host) && backend.getHttpPort() == port) {
                return true;
            }
        }
        for (ComputeNode node : GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getComputeNodes()) {
            if (node.getHost().equals(host) && node.getHttpPort() == port) {
                return true;
            }
        }
        return false;
    }

    String url(String path) {
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "http://" + authority + ":" + port + path;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }
}
