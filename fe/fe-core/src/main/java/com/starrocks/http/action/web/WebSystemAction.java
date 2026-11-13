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

import com.google.common.base.Strings;
import com.starrocks.common.proc.ProcDirInterface;
import com.starrocks.common.proc.ProcNodeInterface;
import com.starrocks.common.proc.ProcResult;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.LeaderOpExecutor;
import com.starrocks.qe.RedirectStatus;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.OriginStatement;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WebSystemAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/system";

    public WebSystemAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebSystemAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        String path = normalizePath(request.getSingleParameter("path"));
        if (path == null) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid proc path"));
            return;
        }
        ProcNodeInterface node = getProcNode(path);
        if (node == null) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND,
                    Map.of("message", "no such proc path: " + path));
            return;
        }

        ProcData data;
        try {
            data = fetch(node, path);
        } catch (Exception e) {
            writeJson(request, response, HttpResponseStatus.BAD_GATEWAY,
                    Map.of("message", "failed to fetch system info: " + e.getMessage()));
            return;
        }
        if (data == null) {
            writeJson(request, response, HttpResponseStatus.BAD_GATEWAY,
                    Map.of("message", "failed to fetch system info from leader"));
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("path", path);
        body.put("parent", parentPath(path));
        body.put("isDir", node instanceof ProcDirInterface);
        body.put("columns", data.columns);
        body.put("rows", data.rows);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private ProcData fetch(ProcNodeInterface node, String path) throws Exception {
        if (GlobalStateMgr.getCurrentState().isLeader()) {
            ProcResult result = node.fetchResult();
            return new ProcData(result.getColumnNames(), result.getRows());
        }

        String statement = "SHOW PROC \"" + path.replace("\"", "\\\"") + "\"";
        LeaderOpExecutor executor = new LeaderOpExecutor(new OriginStatement(statement, 0),
                ConnectContext.get(), RedirectStatus.FORWARD_NO_SYNC);
        executor.execute();
        ShowResultSet resultSet = executor.getProxyResultSet();
        if (resultSet == null) {
            return null;
        }
        List<String> columns = resultSet.getMetaData().getColumns().stream()
                .map(column -> column.getName())
                .collect(Collectors.toList());
        return new ProcData(columns, resultSet.getResultRows());
    }

    private static String normalizePath(String raw) {
        if (Strings.isNullOrEmpty(raw)) {
            return "/";
        }
        if (raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
            return null;
        }
        String value = raw.startsWith("/") ? raw : "/" + raw;
        StringBuilder normalized = new StringBuilder();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            normalized.append('/').append(segment);
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static class ProcData {
        private final List<String> columns;
        private final List<List<String>> rows;

        private ProcData(List<String> columns, List<List<String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }
}
