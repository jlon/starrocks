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

import com.starrocks.common.Config;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.SetType;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class WebVariableAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/variables";

    public WebVariableAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebVariableAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        List<Map<String, String>> variables = new ArrayList<>();
        for (List<String> row : GlobalStateMgr.getCurrentState().getVariableMgr()
                .dump(SetType.GLOBAL, null, null)) {
            Map<String, String> variable = new LinkedHashMap<>();
            variable.put("name", row.get(0));
            variable.put("value", row.get(1));
            variables.add(variable);
        }

        Map<String, String> config = Map.of();
        String configError = null;
        try {
            config = new TreeMap<>(Config.dump());
        } catch (Exception e) {
            configError = "failed to read configuration";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", config);
        if (configError != null) {
            body.put("configError", configError);
        }
        body.put("variables", variables);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }
}
