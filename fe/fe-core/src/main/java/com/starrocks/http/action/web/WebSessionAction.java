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

import com.starrocks.catalog.UserIdentity;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ConnectScheduler;
import com.starrocks.service.ExecuteEnv;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebSessionAction extends WebApiAction {
    private static final String LIST_PATH = "/api/web/v1/sessions";
    private static final String KILL_PATH = "/api/web/v1/sessions/{connection_id}/kill";

    public WebSessionAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, LIST_PATH, new WebSessionAction(controller));
        controller.registerHandler(HttpMethod.POST, KILL_PATH, new WebSessionAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        ConnectScheduler scheduler = ExecuteEnv.getInstance().getScheduler();
        if (scheduler == null) {
            writeJson(request, response, HttpResponseStatus.OK, Map.of("sessions", List.of()));
            return;
        }

        long now = System.currentTimeMillis();
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (ConnectContext.ThreadInfo info : scheduler.listConnection(ConnectContext.get(), null)) {
            List<String> values = info.toRow(now, true);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", value(values, 0));
            row.put("user", value(values, 1));
            row.put("host", value(values, 2));
            row.put("db", value(values, 3));
            row.put("command", value(values, 4));
            row.put("connectionStartTime", value(values, 5));
            row.put("time", value(values, 6));
            row.put("state", value(values, 7));
            row.put("info", value(values, 8));
            row.put("isPending", value(values, 9));
            row.put("warehouse", value(values, 10));
            row.put("cnGroup", value(values, 11));
            row.put("catalog", value(values, 12));
            row.put("queryId", value(values, 13));
            row.put("running", info.isRunning());
            sessions.add(row);
        }

        writeJson(request, response, HttpResponseStatus.OK, Map.of("sessions", sessions));
    }

    @Override
    public void executePost(BaseRequest request, BaseResponse response) {
        UserIdentity user = authenticate(request, response);
        if (user == null || !requireOperate(request, response, user)) {
            return;
        }

        long connectionId;
        try {
            connectionId = Long.parseLong(request.getParams().get("connection_id"));
        } catch (NumberFormatException e) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST, Map.of("message", "invalid connection id"));
            return;
        }

        ConnectScheduler scheduler = ExecuteEnv.getInstance().getScheduler();
        ConnectContext target = scheduler == null ? null : scheduler.getContext(connectionId);
        if (target == null) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "session not found"));
            return;
        }

        target.kill(true, "Killed by web console");
        writeJson(request, response, HttpResponseStatus.OK, Map.of("message", "killed"));
    }

    private static String value(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "";
    }
}
