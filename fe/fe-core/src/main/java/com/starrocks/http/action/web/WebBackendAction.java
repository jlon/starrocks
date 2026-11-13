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

import com.starrocks.common.util.TimeUtils;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebBackendAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/backends";

    public WebBackendAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebBackendAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        List<Backend> backends = new ArrayList<>(
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getIdToBackend().values());
        backends.sort(Comparator.comparingLong(Backend::getId));

        List<Map<String, Object>> rows = new ArrayList<>(backends.size());
        for (Backend backend : backends) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", backend.getId());
            row.put("host", backend.getHost());
            row.put("alive", backend.isAlive());
            row.put("state", backend.getBackendState().toString());
            row.put("version", backend.getVersion());
            row.put("heartbeatPort", backend.getHeartbeatPort());
            row.put("bePort", backend.getBePort());
            row.put("httpPort", backend.getHttpPort());
            row.put("brpcPort", backend.getBrpcPort());
            row.put("startTime", TimeUtils.longToTimeString(backend.getLastStartTime()));
            row.put("lastSuccessReportTabletsTime",
                    backend.getBackendStatus().getLastSuccessReportTabletsTimeStr());
            row.put("cpuCores", backend.getCpuCores());
            row.put("cpuUsedPermille", backend.getCpuUsedPermille());
            row.put("memUsedBytes", backend.getMemUsedBytes());
            row.put("memLimitBytes", backend.getMemLimitBytes());
            row.put("memUsedPct", backend.getMemUsedPct());
            row.put("totalCapacityB", backend.getTotalCapacityB());
            row.put("availableCapacityB", backend.getAvailableCapacityB());
            row.put("dataUsedCapacityB", backend.getDataUsedCapacityB());
            row.put("maxDiskUsedPct", backend.getMaxDiskUsedPct());
            row.put("tabletMaxCompactionScore", backend.getTabletMaxCompactionScore());
            rows.add(row);
        }

        writeJson(request, response, HttpResponseStatus.OK, Map.of("backends", rows));
    }
}
