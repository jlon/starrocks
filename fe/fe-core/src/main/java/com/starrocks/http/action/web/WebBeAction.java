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

import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxies the BE's HTML-only diagnostics pages and exposes the data as JSON for the
 * web console. The BE renders these pages from stable handlers; the raw view is
 * parsed here so the SPA never embeds BE HTML.
 */
public class WebBeAction extends WebApiAction {
    private static final Logger LOG = LogManager.getLogger(WebBeAction.class);

    private static final String BASE = "/api/web/v1/be";
    private static final Pattern PRE = Pattern.compile("<pre>(.*?)</pre>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    public WebBeAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, BASE + "/varz", new WebBeAction(controller));
        controller.registerHandler(HttpMethod.GET, BASE + "/memz", new WebBeAction(controller));
        controller.registerHandler(HttpMethod.GET, BASE + "/mem-tracker", new WebBeAction(controller));
        controller.registerHandler(HttpMethod.GET, BASE + "/logs", new WebBeAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        String nodeValue = request.getSingleParameter("node");
        BeNode node = BeNode.parse(nodeValue);
        if (node == null || !node.exists()) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid node"));
            return;
        }

        String page = path(request).substring(BASE.length());
        String raw;
        try {
            raw = fetch(node, page, request.getSingleParameter("upper_level"));
        } catch (IOException e) {
            LOG.warn("failed to fetch BE page {} from {}:{}", page, node.host(), node.port(), e);
            writeJson(request, response, HttpResponseStatus.BAD_GATEWAY,
                    Map.of("message", "failed to reach backend"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", nodeValue);
        switch (page) {
            case "/varz":
                body.put("configs", parseVarz(raw));
                break;
            case "/mem-tracker":
                Map<String, Object> table = parseTable(raw);
                body.put("headers", table.get("headers"));
                body.put("rows", table.get("rows"));
                break;
            default:
                body.put("text", htmlToText(raw));
        }
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private static String fetch(BeNode node, String page, String upperLevel) throws IOException {
        String query = "?raw";
        if ("/mem-tracker".equals(page)) {
            int level = 2;
            if (upperLevel != null) {
                try {
                    level = Math.max(1, Math.min(10, Integer.parseInt(upperLevel)));
                } catch (NumberFormatException ignored) {
                    level = 2;
                }
            }
            query += "&upper_level=" + level;
        }

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10000)
                .setSocketTimeout(30000)
                .setConnectionRequestTimeout(10000)
                .setRedirectsEnabled(false)
                .build();

        String url = "http://" + node.host() + ":" + node.port() + page + query;
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build();
                CloseableHttpResponse beResponse = client.execute(new HttpGet(url))) {
            int code = beResponse.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                throw new IOException("backend returned " + code);
            }
            return EntityUtils.toString(beResponse.getEntity(), StandardCharsets.UTF_8);
        }
    }

    static List<Map<String, String>> parseVarz(String html) {
        List<Map<String, String>> configs = new ArrayList<>();
        Matcher matcher = PRE.matcher(html);
        if (!matcher.find()) {
            return configs;
        }

        for (String line : htmlToText(matcher.group(1)).split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            Map<String, String> config = new LinkedHashMap<>();
            config.put("name", line.substring(0, separator).trim());
            config.put("value", line.substring(separator + 1).trim());
            configs.add(config);
        }
        return configs;
    }

    static Map<String, Object> parseTable(String html) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW.matcher(html);
        while (rowMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = CELL.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(htmlToText(cellMatcher.group(1)).trim());
            }
            if (cells.isEmpty()) {
                continue;
            }
            if (headers.isEmpty()) {
                headers = cells;
            } else {
                rows.add(cells);
            }
        }

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("headers", headers);
        table.put("rows", rows);
        return table;
    }

    static String htmlToText(String html) {
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|pre|tr|h[1-6]|div|li|table|tbody|thead)>", "\n");
        text = TAG.matcher(text).replaceAll("");
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        return text.replaceAll("\n{2,}", "\n").trim();
    }
}
