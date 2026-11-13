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

import com.starrocks.common.Log4jConfig;
import com.starrocks.http.StarRocksHttpTestCase;
import groovy.lang.Tuple3;
import mockit.Mock;
import mockit.MockUp;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSystemActionTest extends StarRocksHttpTestCase {
    @Test
    void systemTree() throws IOException {
        try (Response response = get("/api/web/v1/system", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertEquals("/", body.get("path"));
            assertEquals(true, body.get("isDir"));
            assertTrue(body.get("columns") instanceof List);
            assertTrue(body.get("rows") instanceof List);
        }
    }

    @Test
    void variables() throws IOException {
        try (Response response = get("/api/web/v1/variables", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertTrue(body.get("config") instanceof Map);
            assertTrue(body.get("variables") instanceof List);
        }
    }

    @Test
    void ha() throws IOException {
        try (Response response = get("/api/web/v1/ha", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertNotNull(body.get("feType"));
            assertNotNull(body.get("journalId"));
            assertTrue(body.get("frontends") instanceof List);
        }
    }

    @Test
    void logs() throws IOException {
        try (Response response = get("/api/web/v1/logs", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertNotNull(body.get("level"));
            assertTrue(body.get("verboseNames") instanceof List);
            assertTrue(body.get("files") instanceof List);
        }
    }

    @Test
    void invalidLogNames() throws IOException {
        try (Response response = get("/api/web/v1/logs/foo.txt", true)) {
            assertEquals(400, response.code());
        }
        try (Response response = get("/api/web/v1/logs/no-such.log", true)) {
            assertEquals(404, response.code());
        }
    }

    @Test
    void addAndRemoveVerboseName() throws IOException {
        new MockUp<Log4jConfig>() {
            @Mock
            public Tuple3<String, String[], String[]> updateLogging(
                    String level, String[] verboseNames, String[] auditNames) {
                return new Tuple3<>("INFO",
                        verboseNames == null ? new String[0] : verboseNames,
                        auditNames == null ? new String[0] : auditNames);
            }
        };

        String add = "{\"action\":\"add\",\"name\":\"com.starrocks.test\"}";
        try (Response response = post("/api/web/v1/logs/verbose", add, true)) {
            assertEquals(200, response.code());
            assertTrue(verboseNames(response).contains("com.starrocks.test"));
        }

        String remove = "{\"action\":\"remove\",\"name\":\"com.starrocks.test\"}";
        try (Response response = post("/api/web/v1/logs/verbose", remove, true)) {
            assertEquals(200, response.code());
            assertFalse(verboseNames(response).contains("com.starrocks.test"));
        }
    }

    @Test
    void requiresAuthentication() throws IOException {
        try (Response response = get("/api/web/v1/system", false)) {
            assertEquals(401, response.code());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> verboseNames(Response response) throws IOException {
        Map<String, Object> body = parseResponseBody(response);
        return (List<String>) body.get("verboseNames");
    }

    private Response get(String path, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder().url(BASE_URL + path).get();
        if (authenticated) {
            builder.header("Authorization", rootAuth);
        }
        return networkClient.newCall(builder.build()).execute();
    }

    private Response post(String path, String body, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .post(RequestBody.create(body, JSON));
        if (authenticated) {
            builder.header("Authorization", rootAuth);
        }
        return networkClient.newCall(builder.build()).execute();
    }
}
