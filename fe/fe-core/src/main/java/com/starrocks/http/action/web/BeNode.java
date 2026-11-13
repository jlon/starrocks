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
        String[] parts = value.substring(3).split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new BeNode(parts[0], Integer.parseInt(parts[1]));
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

    String host() {
        return host;
    }

    int port() {
        return port;
    }
}
