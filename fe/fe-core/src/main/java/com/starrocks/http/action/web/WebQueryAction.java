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
import com.starrocks.common.Config;
import com.starrocks.common.util.ProfileManager;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QeProcessorImpl;
import com.starrocks.qe.QueryStatisticsItem;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebQueryAction extends WebApiAction {
    private static final String LIST_PATH = "/api/web/v1/queries";
    private static final String PROFILE_PATH = "/api/web/v1/queries/{query_id}/profile";
    private static final String CANCEL_PATH = "/api/web/v1/queries/{query_id}/cancel";

    public WebQueryAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, LIST_PATH, new WebQueryAction(controller));
        controller.registerHandler(HttpMethod.GET, PROFILE_PATH, new WebQueryAction(controller));
        controller.registerHandler(HttpMethod.POST, CANCEL_PATH, new WebQueryAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (request.getParams().containsKey("query_id")) {
            getProfile(request, response);
        } else {
            listQueries(request, response);
        }
    }

    @Override
    public void executePost(BaseRequest request, BaseResponse response) {
        cancelQuery(request, response);
    }

    private void listQueries(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        List<Map<String, Object>> running = new ArrayList<>();
        for (QueryStatisticsItem item : QeProcessorImpl.INSTANCE.getQueryStatistics().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queryId", item.getQueryId());
            row.put("user", item.getUser());
            row.put("db", item.getDb());
            row.put("sql", item.getSql());
            row.put("queryType", item.getQueryType());
            row.put("startTime", item.getQueryStartTime());
            row.put("execTime", item.getQueryExecTime());
            row.put("state", item.getExecState());
            row.put("warehouse", item.getWarehouseName());
            running.add(row);
        }

        List<String> headers = ProfileManager.PROFILE_HEADERS;
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            index.put(headers.get(i), i);
        }

        List<Map<String, Object>> finished = new ArrayList<>();
        for (List<String> values : ProfileManager.getInstance().getAllQueries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("queryId", value(values, index, "Query ID"));
            row.put("user", value(values, index, "User"));
            row.put("db", value(values, index, "Default Db"));
            row.put("sql", truncate(value(values, index, "Sql Statement")));
            row.put("queryType", value(values, index, "Query Type"));
            row.put("startTime", value(values, index, "Start Time"));
            row.put("endTime", value(values, index, "End Time"));
            row.put("totalTime", value(values, index, "Total"));
            row.put("state", value(values, index, "Query State"));
            row.put("warehouse", value(values, index, "Warehouse"));
            row.put("dialect", value(values, index, "Sql Dialect"));
            finished.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running);
        body.put("finished", finished);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private void getProfile(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        String queryId = request.getParams().get("query_id");
        String profile = ProfileManager.getInstance().getProfile(queryId);
        if (profile == null) {
            QueryStatisticsItem item = QeProcessorImpl.INSTANCE.getQueryStatistics().get(queryId);
            if (item != null && item.getQueryProfile() != null) {
                profile = item.getQueryProfile().toString();
            }
        }
        if (profile == null) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "profile not found"));
            return;
        }

        writeJson(request, response, HttpResponseStatus.OK, Map.of("queryId", queryId, "profile", profile));
    }

    private void cancelQuery(BaseRequest request, BaseResponse response) {
        UserIdentity user = authenticate(request, response);
        if (user == null || !requireOperate(request, response, user)) {
            return;
        }

        String queryId = request.getParams().get("query_id");
        ConnectContext target = QeProcessorImpl.INSTANCE.getConnectContextByQueryId(queryId);
        if (target == null) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "query not found"));
            return;
        }

        target.kill(true, "Cancelled by web console");
        writeJson(request, response, HttpResponseStatus.OK, Map.of("message", "cancelled"));
    }

    private static String value(List<String> values, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        return i == null || i >= values.size() ? "" : values.get(i);
    }

    private static String truncate(String sql) {
        int max = Config.ui_queries_sql_statement_max_length;
        if (sql == null || sql.length() <= max) {
            return sql;
        }
        return sql.substring(0, max) + "...";
    }
}
