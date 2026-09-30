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

package com.starrocks.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.starrocks.common.Config;
import com.starrocks.http.StarRocksHttpTestCase;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryLogActionTest extends StarRocksHttpTestCase {
    private static final String LOG_NAME = "fe.audit.log";
    private static final String ROLLED_LOG_NAME = "fe.audit.log.20260301.000000";
    private static final Function MKFIFO = NativeLibrary.getInstance("c").getFunction("mkfifo");

    @TempDir
    Path logDir;

    private String originalAuditLogDir;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        originalAuditLogDir = Config.audit_log_dir;
        Config.audit_log_dir = logDir.toString();
        Files.writeString(logDir.resolve(LOG_NAME), "first line\nneedle.one\nneedle.two\n");
        Files.writeString(logDir.resolve(ROLLED_LOG_NAME), "rolled query log\n");
        Files.writeString(logDir.resolve(ROLLED_LOG_NAME + ".gz"), "not searchable\n");
        Files.createSymbolicLink(logDir.resolve(LOG_NAME + ".link"), logDir.resolve(LOG_NAME));
        Files.writeString(logDir.resolve("unrelated.log"), "not searchable\n");
    }

    @AfterEach
    @Override
    public void tearDown() {
        Config.audit_log_dir = originalAuditLogDir;
        super.tearDown();
    }

    @Test
    public void testListAndSearchAuditLogs() throws IOException {
        JsonNode logs = getJson("/api/query_logs", 200);
        assertEquals(2, logs.path("logs").size());
        assertEquals(ROLLED_LOG_NAME, logs.path("logs").get(0).path("name").asText());
        assertEquals(LOG_NAME, logs.path("logs").get(1).path("name").asText());
        assertTrue(logs.path("logs").get(0).path("size").asLong() > 0);

        JsonNode firstPage = getJson("/api/query_logs/search?file=" + LOG_NAME
                + "&keyword=needle.&page_size=1", 200);
        assertTrue(firstPage.path("success").asBoolean());
        assertEquals(1, firstPage.path("results").size());
        assertEquals(2, firstPage.path("results").get(0).path("lineNumber").asInt());
        assertEquals("needle.one", firstPage.path("results").get(0).path("matchedLine").asText());
        assertTrue(firstPage.path("hasMore").asBoolean());

        JsonNode secondPage = getJson("/api/query_logs/search?file=" + LOG_NAME
                + "&keyword=needle.&page_num=1&page_size=1", 200);
        assertEquals(3, secondPage.path("results").get(0).path("lineNumber").asInt());
        assertFalse(secondPage.path("hasMore").asBoolean());
    }

    @Test
    public void testRejectsUnlistedFile() throws IOException {
        JsonNode result = getJson("/api/query_logs/search?file=../../etc/passwd&keyword=root", 404);
        assertFalse(result.path("success").asBoolean());
        assertNotNull(result.path("errorMessage").asText());

        result = getJson("/api/query_logs/search?file=" + LOG_NAME + "&keyword=needle&page_size=0", 400);
        assertFalse(result.path("success").asBoolean());
    }

    @Test
    public void testFinalOpenRejectsSymbolicLink() throws IOException {
        try (SeekableByteChannel channel = QueryLogAction.openAuditLog(LOG_NAME)) {
            assertNotNull(channel);
        }

        assertNull(QueryLogAction.openAuditLog(LOG_NAME + ".link"));
    }

    @Test
    public void testFinalOpenRejectsFifo() throws IOException {
        Path fifo = logDir.resolve(LOG_NAME + ".fifo");
        assertEquals(0, MKFIFO.invokeInt(new Object[] {fifo.toString(), 0600}));

        assertNull(QueryLogAction.openAuditLog(fifo.getFileName().toString()));
    }

    private JsonNode getJson(String path, int expectedStatus) throws IOException {
        Request request = new Request.Builder()
                .get()
                .addHeader("Authorization", rootAuth)
                .url(BASE_URL + path)
                .build();
        try (Response response = networkClient.newCall(request).execute()) {
            assertEquals(expectedStatus, response.code());
            assertNotNull(response.body());
            return objectMapper.readTree(response.body().string());
        }
    }
}
