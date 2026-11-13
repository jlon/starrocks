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

public class WebBeActionTest extends StarRocksHttpTestCase {

    @Test
    public void testParseVarz() {
        String html = "<h2>Configurations</h2><pre>be_port=9060\n"
                + "mem_limit=80%\nexternal_scan_thread_pool_size = 48\n"
                + "html_value=a&amp;b</pre>";
        List<Map<String, String>> configs = WebBeAction.parseVarz(html);
        assertEquals(4, configs.size());
        assertEquals("be_port", configs.get(0).get("name"));
        assertEquals("9060", configs.get(0).get("value"));
        assertEquals("48", configs.get(2).get("value"));
        assertEquals("a&b", configs.get(3).get("value"));
    }

    @Test
    public void testParseTable() {
        String html = "<table><thead><tr><th>Level</th><th>Label</th></tr></thead>"
                + "<tbody><tr><td>1</td><td>root</td></tr>"
                + "<tr><td>2</td><td>query &lt;pool&gt;</td></tr></tbody></table>";
        Map<String, Object> table = WebBeAction.parseTable(html);
        assertEquals(List.of("Level", "Label"), table.get("headers"));
        @SuppressWarnings("unchecked")
        List<List<String>> rows = (List<List<String>>) table.get("rows");
        assertEquals(2, rows.size());
        assertEquals("query <pool>", rows.get(1).get(1));
    }

    @Test
    public void testHtmlToText() {
        assertEquals("line1\nline2", WebBeAction.htmlToText("line1<br/>line2"));
        assertEquals("a & b", WebBeAction.htmlToText("<p>a &amp; b</p>"));
    }

    @Test
    public void testInvalidNode() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/web/v1/be/varz?node=BE:127.0.0.1:1")
                .header("Authorization", rootAuth)
                .get()
                .build();
        try (Response response = networkClient.newCall(request).execute()) {
            assertEquals(400, response.code());
        }
    }

    @Test
    public void testRequiresAuth() throws IOException {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/web/v1/be/varz?node=BE:127.0.0.1:1")
                .get()
                .build();
        try (Response response = networkClient.newCall(request).execute()) {
            assertEquals(401, response.code());
        }
    }
}
