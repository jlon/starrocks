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

import com.google.gson.JsonSyntaxException;
import com.starrocks.authentication.AuthenticationException;
import com.starrocks.authentication.AuthenticationHandler;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.HttpAuthManager;
import com.starrocks.http.IllegalArgException;
import com.starrocks.http.action.WebBaseAction;
import com.starrocks.qe.ConnectContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class WebAuthAction extends WebApiAction {
    private static final String LOGIN_PATH = "/api/web/v1/login";
    private static final String LOGOUT_PATH = "/api/web/v1/logout";
    private static final String SESSION_PATH = "/api/web/v1/session";

    public WebAuthAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.POST, LOGIN_PATH, new WebAuthAction(controller));
        controller.registerHandler(HttpMethod.POST, LOGOUT_PATH, new WebAuthAction(controller));
        controller.registerHandler(HttpMethod.GET, SESSION_PATH, new WebAuthAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (path(request).equals(SESSION_PATH)) {
            getSession(request, response);
        } else {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "not found"));
        }
    }

    @Override
    public void executePost(BaseRequest request, BaseResponse response) {
        String uri = path(request);
        if (uri.equals(LOGIN_PATH)) {
            login(request, response);
        } else if (uri.equals(LOGOUT_PATH)) {
            logout(request, response);
        } else {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND, Map.of("message", "not found"));
        }
    }

    private void login(BaseRequest request, BaseResponse response) {
        if (!checkOrigin(request)) {
            writeJson(request, response, HttpResponseStatus.FORBIDDEN, Map.of("message", "invalid origin"));
            return;
        }

        LoginRequest login;
        try {
            login = GSON.fromJson(request.getContent(), LoginRequest.class);
        } catch (DdlException | JsonSyntaxException e) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST, Map.of("message", "invalid json"));
            return;
        }
        if (login == null || login.username == null || login.password == null) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "username and password are required"));
            return;
        }

        UserIdentity user;
        try {
            user = AuthenticationHandler.authenticate(new ConnectContext(), login.username,
                    request.getHostString(), login.password.getBytes(StandardCharsets.UTF_8));
        } catch (AuthenticationException e) {
            writeJson(request, response, HttpResponseStatus.UNAUTHORIZED,
                    Map.of("message", "invalid username or password"));
            return;
        }

        if (!hasNodePrivilege(user)) {
            writeJson(request, response, HttpResponseStatus.FORBIDDEN,
                    Map.of("message", "NODE privilege required"));
            return;
        }

        HttpAuthManager.SessionValue value = new HttpAuthManager.SessionValue();
        value.currentUser = user;
        String key = UUID.randomUUID().toString();
        HttpAuthManager.getInstance().addSessionValue(key, value);
        response.addCookie(cookie(key, WebBaseAction.STARROCKS_SESSION_EXPIRED_TIME));

        writeJson(request, response, HttpResponseStatus.OK,
                Map.of("user", user.getUser(), "canAdmin", true));
    }

    private void logout(BaseRequest request, BaseResponse response) {
        if (!checkOrigin(request)) {
            writeJson(request, response, HttpResponseStatus.FORBIDDEN, Map.of("message", "invalid origin"));
            return;
        }

        String sessionId = request.getCookieValue(WebBaseAction.STARROCKS_SESSION_ID);
        HttpAuthManager.getInstance().removeSessionValue(sessionId);
        response.addCookie(cookie("", 0));
        writeJson(request, response, HttpResponseStatus.OK, Map.of("message", "logged out"));
    }

    private void getSession(BaseRequest request, BaseResponse response) {
        String sessionId = request.getCookieValue(WebBaseAction.STARROCKS_SESSION_ID);
        HttpAuthManager.SessionValue value = HttpAuthManager.getInstance().getSessionValue(sessionId);
        if (value == null || !hasNodePrivilege(value.currentUser)) {
            writeJson(request, response, HttpResponseStatus.UNAUTHORIZED,
                    Map.of("message", "not logged in"));
            return;
        }
        writeJson(request, response, HttpResponseStatus.OK,
                Map.of("user", value.currentUser.getUser(), "canAdmin", true));
    }

    private DefaultCookie cookie(String value, long maxAge) {
        DefaultCookie cookie = new DefaultCookie(WebBaseAction.STARROCKS_SESSION_ID, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        cookie.setSameSite(CookieHeaderNames.SameSite.Lax);
        if (Config.enable_https) {
            cookie.setSecure(true);
        }
        return cookie;
    }

    private static class LoginRequest {
        String username;
        String password;
    }
}
