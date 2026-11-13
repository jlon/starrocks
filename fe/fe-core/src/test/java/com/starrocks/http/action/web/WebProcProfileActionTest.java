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
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebProcProfileActionTest extends StarRocksHttpTestCase {
    @Test
    void listFeProfiles() throws IOException {
        try (Response response = get("/api/web/v1/proc-profiles", true)) {
            assertEquals(200, response.code());
            Map<String, Object> body = parseResponseBody(response);
            assertEquals("FE", body.get("node"));
            assertTrue(body.get("files") instanceof List);
        }
    }

    @Test
    void invalidNode() throws IOException {
        try (Response response = get("/api/web/v1/proc-profiles?node=invalid", true)) {
            assertEquals(400, response.code());
        }
        try (Response response = get("/api/web/v1/proc-profiles?node=BE:bad", true)) {
            assertEquals(400, response.code());
        }
    }

    @Test
    void invalidFile() throws IOException {
        try (Response response = get("/api/web/v1/proc-profiles/file?filename=bad", true)) {
            assertEquals(400, response.code());
        }
    }

    @Test
    void requiresAuthentication() throws IOException {
        try (Response response = get("/api/web/v1/proc-profiles", false)) {
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
}
