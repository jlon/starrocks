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

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.google.common.base.Suppliers;
import com.starrocks.authorization.PrivilegeType;
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
import com.starrocks.sql.ExplainAnalyzer;
import com.starrocks.sql.analyzer.Authorizer;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.vertical_blank.sqlformatter.languages.Dialect.MySql;
import static com.github.vertical_blank.sqlformatter.languages.Dialect.StandardSql;

public class WebQueryAction extends WebApiAction {
    private static final Logger LOG = LogManager.getLogger(WebQueryAction.class);
    private static final String ANSI_REGEX = "\\u001B\\[[;?0-9]*[a-zA-Z]";
    private static final String LIST_PATH = "/api/web/v1/queries";
    private static final String PROFILE_PATH = "/api/web/v1/queries/{query_id}/profile";
    private static final SqlFormatter.Formatter MYSQL_FORMATTER = SqlFormatter.of(MySql);
    private static final SqlFormatter.Formatter STANDARDSQL_FORMATTER = SqlFormatter.of(StandardSql);

    public WebQueryAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, LIST_PATH, new WebQueryAction(controller));
        controller.registerHandler(HttpMethod.GET, PROFILE_PATH, new WebQueryAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (request.getParams().containsKey("query_id")) {
            getProfile(request, response);
        } else {
            listQueries(request, response);
        }
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
            row.put("sql", truncate(item.getSql()));
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
        ConnectContext ctx = ConnectContext.get();
        boolean checkAccess = Config.authorization_enable_query_profile_access_check;
        Supplier<Boolean> hasOperate =
                Suppliers.memoize(() -> Authorizer.hasSystemAction(ctx, PrivilegeType.OPERATE));
        for (List<String> values : ProfileManager.getInstance().getAllQueries(
                element -> !checkAccess || Authorizer.canReadQueryProfile(ctx, element, hasOperate))) {
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
        UserIdentity user = authenticate(request, response);
        if (user == null || !requireOperate(request, response, user)) {
            return;
        }

        String queryId = request.getParams().get("query_id");
        String type = request.getSingleParameter("type");
        if (type == null || type.isEmpty()) {
            type = "profile";
        }
        if (!"profile".equals(type) && !"sql".equals(type) && !"analyze".equals(type)) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "type must be profile, sql or analyze"));
            return;
        }

        ProfileManager.ProfileElement element = ProfileManager.getInstance().getProfileElement(queryId);
        String content;
        if ("profile".equals(type)) {
            content = ProfileManager.getInstance().getProfile(queryId);
            if (content == null) {
                QueryStatisticsItem item = QeProcessorImpl.INSTANCE.getQueryStatistics().get(queryId);
                if (item != null && item.getQueryProfile() != null) {
                    content = item.getQueryProfile().toString();
                }
            }
        } else if (element == null) {
            content = null;
        } else if ("sql".equals(type)) {
            content = formattedSql(element);
        } else {
            content = analyze(element, queryId);
        }

        if (content == null) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "profile not found"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("type", type);
        body.put("content", content);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private static String formattedSql(ProfileManager.ProfileElement element) {
        String sql = element.infoStrings.getOrDefault(ProfileManager.SQL_STATEMENT, "");
        try {
            if ("StarRocks".equalsIgnoreCase(element.infoStrings.get(ProfileManager.SQL_DIALECT))) {
                return MYSQL_FORMATTER.format(sql);
            }
            return STANDARDSQL_FORMATTER.format(sql);
        } catch (RuntimeException e) {
            LOG.warn("Failed to format SQL for query profile", e);
            return sql;
        }
    }

    private static String analyze(ProfileManager.ProfileElement element, String queryId) {
        try {
            String analysis = ExplainAnalyzer.analyze(element.plan, element.getRuntimeProfile(),
                    Collections.emptyList(), false);
            return analysis.replaceAll(ANSI_REGEX, "");
        } catch (Exception e) {
            LOG.warn("Failed to analyze profile of {}", queryId, e);
            return String.format("Failed to analyze profile of %s%n%s%n", queryId, e);
        }
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
