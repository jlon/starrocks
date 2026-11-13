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

import com.starrocks.http.StarRocksHttpTestCase;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClusterActionTest extends StarRocksHttpTestCase {
    @Test
    void overview() throws IOException {
        try (Response response = get("/api/web/v1/overview", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertNotNull(body.get("version"));
            assertNotNull(body.get("fe"));
            assertTrue(body.get("backends") instanceof Map);
            assertTrue(body.get("queries") instanceof Map);
            assertTrue(body.get("sessions") instanceof Map);
        }
    }

    @Test
    void backends() throws IOException {
        try (Response response = get("/api/web/v1/backends", true)) {
            assertEquals(200, response.code());
            assertTrue(parseResponseBody(response).get("backends") instanceof List);
        }
    }

    @Test
    void queries() throws IOException {
        try (Response response = get("/api/web/v1/queries", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertTrue(body.get("running") instanceof List);
            assertTrue(body.get("finished") instanceof List);
        }
    }

    @Test
    void missingQueryProfileAndCancel() throws IOException {
        try (Response response = get("/api/web/v1/queries/no-such-query/profile", true)) {
            assertEquals(404, response.code());
        }
        try (Response response = post("/api/web/v1/queries/no-such-query/cancel", true)) {
            assertEquals(404, response.code());
        }
    }

    @Test
    void sessions() throws IOException {
        try (Response response = get("/api/web/v1/sessions", true)) {
            assertEquals(200, response.code());
            assertTrue(parseResponseBody(response).get("sessions") instanceof List);
        }
    }

    @Test
    void invalidConnectionId() throws IOException {
        try (Response response = post("/api/web/v1/sessions/not-a-number/kill", true)) {
            assertEquals(400, response.code());
        }
    }

    @Test
    void requiresAuthentication() throws IOException {
        try (Response response = get("/api/web/v1/overview", false)) {
            assertEquals(401, response.code());
        }
    }

    private Response get(String path, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder().url(BASE_URL + path).get();
        if (authenticated) {
            builder.header("Authorization", rootAuth);
        }
        return networkClient.newCall(builder.build()).execute();
    }

    private Response post(String path, boolean authenticated) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .post(RequestBody.create("", JSON));
        if (authenticated) {
            builder.header("Authorization", rootAuth);
        }
        return networkClient.newCall(builder.build()).execute();
    }
}
