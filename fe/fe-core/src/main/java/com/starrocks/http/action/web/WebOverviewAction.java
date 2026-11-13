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

import com.starrocks.common.Version;
import com.starrocks.common.util.ProfileManager;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ConnectScheduler;
import com.starrocks.qe.QeProcessorImpl;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.service.ExecuteEnv;
import com.starrocks.system.SystemInfoService;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.LinkedHashMap;
import java.util.Map;

public class WebOverviewAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/overview";

    public WebOverviewAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebOverviewAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        GlobalStateMgr state = GlobalStateMgr.getCurrentState();
        SystemInfoService cluster = state.getNodeMgr().getClusterInfo();

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version", Version.STARROCKS_VERSION);
        version.put("commit", Version.STARROCKS_COMMIT_HASH);
        version.put("buildTime", Version.STARROCKS_BUILD_TIME);

        Map<String, Object> fe = new LinkedHashMap<>();
        fe.put("type", state.getFeType().name());
        fe.put("leader", state.isLeader());

        Map<String, Object> backends = new LinkedHashMap<>();
        backends.put("total", cluster.getTotalBackendNumber());
        backends.put("alive", cluster.getAliveBackendNumber());

        Map<String, Object> computeNodes = new LinkedHashMap<>();
        computeNodes.put("total", cluster.getTotalComputeNodeNumber());
        computeNodes.put("alive", cluster.getAliveComputeNodeNumber());

        Map<String, Object> queries = new LinkedHashMap<>();
        queries.put("running", QeProcessorImpl.INSTANCE.getQueryStatistics().size());
        queries.put("finished", ProfileManager.getInstance().getAllQueries().size());

        Map<String, Object> sessions = new LinkedHashMap<>();
        ConnectScheduler scheduler = ExecuteEnv.getInstance().getScheduler();
        sessions.put("total", scheduler == null ? 0 : scheduler.listConnection(ConnectContext.get(), null).size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("fe", fe);
        body.put("backends", backends);
        body.put("computeNodes", computeNodes);
        body.put("queries", queries);
        body.put("sessions", sessions);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }
}
