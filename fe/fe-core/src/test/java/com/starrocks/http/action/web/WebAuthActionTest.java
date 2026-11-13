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

import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.AuthorizationMgr;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.http.ActionController;
import com.starrocks.http.StarRocksHttpTestCase;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.Authorizer;
import mockit.Mock;
import mockit.MockUp;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAuthActionTest extends StarRocksHttpTestCase {
    private static final String LOGIN = "/api/web/v1/login";
    private static final String LOGOUT = "/api/web/v1/logout";
    private static final String SESSION = "/api/web/v1/session";

    @Test
    void loginSessionLogout() throws IOException {
        Response login = post(LOGIN, "{\"username\":\"root\",\"password\":\"\"}", null, BASE_URL);
        assertEquals(200, login.code());
        assertTrue(login.header("Content-Type").startsWith("application/json"));

        Map<String, Object> body = parseResponseBody(login);
        assertEquals("root", body.get("user"));
        assertEquals(true, body.get("canAdmin"));

        String setCookie = login.header("Set-Cookie");
        assertNotNull(setCookie);
        String lower = setCookie.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("starrocks_session_id="));
        assertTrue(lower.contains("path=/"));
        assertTrue(lower.contains("httponly"));
        assertTrue(lower.contains("samesite=lax"));
        assertTrue(lower.contains("max-age=86400"));

        String cookie = setCookie.split(";", 2)[0];
        Response session = get(SESSION, cookie);
        assertEquals(200, session.code());
        assertEquals("root", parseResponseBody(session).get("user"));

        Response logout = post(LOGOUT, "", cookie, BASE_URL);
        assertEquals(200, logout.code());
        assertTrue(logout.header("Set-Cookie").toLowerCase(Locale.ROOT).contains("max-age=0"));

        Response afterLogout = get(SESSION, cookie);
        assertEquals(401, afterLogout.code());
    }

    @Test
    void rejectsInvalidCredentials() throws IOException {
        try (Response response = post(LOGIN, "{\"username\":\"root\",\"password\":\"wrong\"}", null, null)) {
            assertEquals(401, response.code());
        }
    }

    @Test
    void rejectsInvalidJsonAndMissingFields() throws IOException {
        try (Response response = post(LOGIN, "{bad", null, null)) {
            assertEquals(400, response.code());
        }
        try (Response response = post(LOGIN, "{\"username\":\"root\"}", null, null)) {
            assertEquals(400, response.code());
        }
    }

    @Test
    void rejectsForeignOrigin() throws IOException {
        try (Response response = post(LOGIN, "{\"username\":\"root\",\"password\":\"\"}",
                null, "http://evil.example")) {
            assertEquals(403, response.code());
        }
    }

    @Test
    void sessionRequiresCookie() throws IOException {
        try (Response response = get(SESSION, null)) {
            assertEquals(401, response.code());
        }
    }

    @Test
    void nonNodeUserIsRejected() {
        new MockUp<Authorizer>() {
            @Mock
            public void checkSystemAction(ConnectContext context, PrivilegeType privilegeType)
                    throws AccessDeniedException {
                throw new AccessDeniedException();
            }
        };
        new MockUp<AuthorizationMgr>() {
            @Mock
            public Set<Long> getOwnedRolesByUser(UserIdentity user) {
                return Collections.emptySet();
            }
        };

        WebAuthAction action = new WebAuthAction(new ActionController());
        UserIdentity nobody = UserIdentity.createAnalyzedUserIdentWithIp("nobody", "%");
        assertFalse(action.hasNodePrivilege(nobody));
    }

    private Response post(String path, String json, String cookie, String origin) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .post(RequestBody.create(json, JSON));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return networkClient.newCall(builder.build()).execute();
    }

    private Response get(String path, String cookie) throws IOException {
        Request.Builder builder = new Request.Builder().url(BASE_URL + path).get();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return networkClient.newCall(builder.build()).execute();
    }
}
