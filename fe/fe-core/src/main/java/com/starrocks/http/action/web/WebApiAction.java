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

import com.google.gson.Gson;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseAction;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.net.URI;
import java.util.Map;

public class WebApiAction extends BaseAction {
    protected static final Gson GSON = new Gson();

    public WebApiAction(ActionController controller) {
        super(controller);
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) {
        HttpMethod method = request.getRequest().method();
        if (method.equals(HttpMethod.GET)) {
            executeGet(request, response);
        } else if (method.equals(HttpMethod.POST)) {
            executePost(request, response);
        } else if (method.equals(HttpMethod.OPTIONS)) {
            writeResponse(request, response, HttpResponseStatus.OK);
        } else {
            writeJson(request, response, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    Map.of("message", "method not allowed"));
        }
    }

    public void executeGet(BaseRequest request, BaseResponse response) {
        writeJson(request, response, HttpResponseStatus.NOT_IMPLEMENTED,
                Map.of("message", "not implemented"));
    }

    public void executePost(BaseRequest request, BaseResponse response) {
        writeJson(request, response, HttpResponseStatus.NOT_IMPLEMENTED,
                Map.of("message", "not implemented"));
    }

    protected void writeJson(BaseRequest request, BaseResponse response, HttpResponseStatus status, Object body) {
        response.setContentType("application/json");
        response.appendContent(GSON.toJson(body));
        writeResponse(request, response, status);
    }

    protected boolean checkOrigin(BaseRequest request) {
        String origin = request.getRequest().headers().get("Origin");
        if (origin == null) {
            return true;
        }
        String host = request.getRequest().headers().get("Host");
        if (host == null) {
            return false;
        }
        try {
            return host.equalsIgnoreCase(URI.create(origin).getAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
