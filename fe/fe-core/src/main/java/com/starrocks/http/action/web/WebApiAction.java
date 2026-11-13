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
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.HttpAuthManager;
import com.starrocks.http.action.WebBaseAction;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Authorizer;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.net.URI;
import java.util.Map;

public class WebApiAction extends WebBaseAction {
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

    /**
     * Authenticate a web API request with the session cookie, falling back to HTTP Basic auth.
     * Returns null after writing a 401 response when authentication or NODE privilege fails.
     */
    protected UserIdentity authenticate(BaseRequest request, BaseResponse response) {
        String sessionId = request.getCookieValue(STARROCKS_SESSION_ID);
        HttpAuthManager.SessionValue session = HttpAuthManager.getInstance().getSessionValue(sessionId);
        UserIdentity user = session == null ? basicAuthUser(request) : session.currentUser;
        if (user == null || !hasNodePrivilege(user)) {
            writeJson(request, response, HttpResponseStatus.UNAUTHORIZED,
                    Map.of("message", "not logged in"));
            return null;
        }

        request.setAuthorized(true);
        setThreadLocalContext(user, request.getHostString());
        return user;
    }

    protected boolean requireOperate(BaseRequest request, BaseResponse response, UserIdentity user) {
        if (hasOperatePrivilege(user)) {
            return true;
        }
        writeJson(request, response, HttpResponseStatus.FORBIDDEN,
                Map.of("message", "OPERATE privilege required"));
        return false;
    }

    protected boolean hasNodePrivilege(UserIdentity user) {
        return hasSystemPrivilege(user, PrivilegeType.NODE, true);
    }

    protected boolean hasOperatePrivilege(UserIdentity user) {
        return hasSystemPrivilege(user, PrivilegeType.OPERATE, false);
    }

    private boolean hasSystemPrivilege(UserIdentity user, PrivilegeType privilege, boolean allowAdminRole) {
        try {
            ConnectContext context = new ConnectContext();
            context.setCurrentUserIdentity(user);
            context.setCurrentRoleIds(user);
            Authorizer.checkSystemAction(context, privilege);
            return true;
        } catch (AccessDeniedException e) {
            if (!allowAdminRole) {
                return false;
            }
            try {
                checkUserOwnsAdminRole(user);
                return true;
            } catch (AccessDeniedException ignored) {
                return false;
            }
        }
    }

    private UserIdentity basicAuthUser(BaseRequest request) {
        String authorization = request.getAuthorizationHeader();
        if (authorization == null || authorization.isEmpty()) {
            return null;
        }
        try {
            return checkPassword(getAuthorizationInfo(request));
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    private void setThreadLocalContext(UserIdentity user, String remoteIp) {
        ConnectContext context = new ConnectContext(null);
        context.setQualifiedUser(user.getUser());
        context.setQueryId(UUIDUtil.genUUID());
        context.setRemoteIP(remoteIp);
        context.setCurrentUserIdentity(user);
        context.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        context.setCurrentRoleIds(user);
        context.setThreadLocalInfo();
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

    protected static String path(BaseRequest request) {
        return request.getRequest().uri().split("\\?", 2)[0];
    }
}
