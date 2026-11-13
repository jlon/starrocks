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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starrocks.common.Config;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.HttpUtils;
import com.starrocks.http.IllegalArgException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebProcProfileAction extends WebApiAction {
    private static final Logger LOG = LogManager.getLogger(WebProcProfileAction.class);

    private static final String LIST_PATH = "/api/web/v1/proc-profiles";
    private static final String FILE_PATH = "/api/web/v1/proc-profiles/file";
    private static final String CPU_FILE_NAME_PREFIX = "cpu-profile-";
    private static final String MEM_FILE_NAME_PREFIX = "mem-profile-";
    private static final String TIME_FORMAT = "yyyyMMdd-HHmmss";

    public WebProcProfileAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, LIST_PATH, new WebProcProfileAction(controller));
        controller.registerHandler(HttpMethod.GET, FILE_PATH, new WebProcProfileAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }
        if (FILE_PATH.equals(path(request))) {
            serveFile(request, response);
        } else {
            list(request, response);
        }
    }

    private void list(BaseRequest request, BaseResponse response) {
        String node = request.getSingleParameter("node");
        if (node == null || node.isEmpty()) {
            node = "FE";
        }

        List<Map<String, Object>> files;
        if ("FE".equals(node)) {
            files = feFiles();
        } else if (node.startsWith("BE:")) {
            files = beFiles(node);
            if (files == null) {
                writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                        Map.of("message", "invalid node"));
                return;
            }
        } else {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid node"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", node);
        body.put("files", files);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private void serveFile(BaseRequest request, BaseResponse response) {
        String filename = request.getSingleParameter("filename");
        String node = request.getSingleParameter("node");
        if (node == null || node.isEmpty()) {
            node = "FE";
        }

        if ("FE".equals(node)) {
            serveFeFile(request, response, filename);
        } else if (node.startsWith("BE:")) {
            proxyFile(request, response, node, filename);
        } else {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid node"));
        }
    }

    private void serveFeFile(BaseRequest request, BaseResponse response, String filename) {
        File dir = new File(Config.sys_log_dir, "proc_profile");
        File file = new File(dir, filename == null ? "" : filename);
        if (!isValidFeFilename(filename) || !file.isFile()) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid filename"));
            return;
        }

        try {
            String html = extractHtml(file);
            if (html == null) {
                writeJson(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        Map.of("message", "no html in profile"));
                return;
            }
            response.updateHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/html; charset=utf-8");
            writeObjectResponse(request, response, HttpResponseStatus.OK,
                    html.getBytes(StandardCharsets.UTF_8), filename, false);
        } catch (IOException e) {
            LOG.warn("failed to read proc profile {}", filename, e);
            writeJson(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Map.of("message", "failed to read profile"));
        }
    }

    private void proxyFile(BaseRequest request, BaseResponse response, String node, String filename) {
        if (!isValidFeFilename(filename)) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid filename"));
            return;
        }

        BeNode beNode = BeNode.parse(node);
        if (beNode == null || !beNode.exists()) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid node"));
            return;
        }

        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8);
        String host = beNode.host();
        int port = beNode.port();
        String url = beNode.url("/proc_profile/file?filename=" + encoded);
        RequestConfig config = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .setExpectContinueEnabled(Boolean.TRUE)
                .setTargetPreferredAuthSchemes(Arrays.asList(AuthSchemes.NTLM, AuthSchemes.DIGEST, AuthSchemes.SPNEGO))
                .setProxyPreferredAuthSchemes(Arrays.asList(AuthSchemes.BASIC, AuthSchemes.SPNEGO))
                .setConnectTimeout(10000)
                .setSocketTimeout(60000)
                .setConnectionRequestTimeout(10000)
                .setRedirectsEnabled(false)
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build();
                CloseableHttpResponse beResponse = client.execute(new HttpGet(url))) {
            int code = beResponse.getStatusLine().getStatusCode();
            if (code != HttpStatus.SC_OK) {
                writeJson(request, response, HttpResponseStatus.BAD_GATEWAY,
                        Map.of("message", "backend returned " + code));
                return;
            }

            String contentType = beResponse.getEntity().getContentType() == null
                    ? "application/octet-stream"
                    : beResponse.getEntity().getContentType().getValue();
            byte[] bytes = EntityUtils.toByteArray(beResponse.getEntity());
            response.updateHeader(HttpHeaderNames.CONTENT_TYPE.toString(), contentType);
            writeObjectResponse(request, response, HttpResponseStatus.OK, bytes, filename, false);
        } catch (IOException e) {
            LOG.warn("failed to proxy proc profile from {}:{}, filename={}", host, port, filename, e);
            writeJson(request, response, HttpResponseStatus.BAD_GATEWAY,
                    Map.of("message", "failed to reach backend"));
        }
    }

    private List<Map<String, Object>> feFiles() {
        File dir = new File(Config.sys_log_dir, "proc_profile");
        File[] files = dir.listFiles();
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }

        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".tar.gz")) {
                continue;
            }
            String type = fileType(file.getName());
            String timestamp = timestamp(file.getName());
            if (type == null || timestamp == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("timestamp", timestamp);
            entry.put("size", file.length());
            entry.put("filename", file.getName());
            result.add(entry);
        }
        result.sort(Comparator.comparing((Map<String, Object> f) -> (String) f.get("timestamp")).reversed());
        return result;
    }

    private List<Map<String, Object>> beFiles(String node) {
        BeNode beNode = BeNode.parse(node);
        if (beNode == null || !beNode.exists()) {
            return null;
        }

        String host = beNode.host();
        int port = beNode.port();
        try {
            String url = beNode.url("/api/proc_profile/list");
            JsonObject json = JsonParser.parseString(HttpUtils.get(url, null)).getAsJsonObject();
            if (json.has("error")) {
                LOG.warn("backend {}:{} returned error: {}", host, port, json.get("error").getAsString());
                return List.of();
            }

            JsonArray profiles = json.getAsJsonArray("profiles");
            List<Map<String, Object>> result = new ArrayList<>();
            if (profiles == null) {
                return result;
            }

            for (JsonElement element : profiles) {
                JsonObject profile = element.getAsJsonObject();
                String filename = profile.get("filename").getAsString();
                String timestamp = profile.get("timestamp").getAsString();
                if (parseTime(timestamp) == null) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", profile.get("type").getAsString());
                entry.put("timestamp", timestamp);
                entry.put("size", profile.get("size").getAsLong());
                entry.put("filename", filename);
                result.add(entry);
            }
            result.sort(Comparator.comparing((Map<String, Object> f) -> (String) f.get("timestamp")).reversed());
            return result;
        } catch (Exception e) {
            LOG.warn("failed to list proc profiles from {}:{}", host, port, e);
            return List.of();
        }
    }

    private static boolean isValidFeFilename(String filename) {
        return filename != null
                && filename.matches("[A-Za-z0-9._-]+\\.tar\\.gz")
                && (filename.startsWith(CPU_FILE_NAME_PREFIX) || filename.startsWith(MEM_FILE_NAME_PREFIX))
                && !filename.contains("..");
    }

    private static String fileType(String filename) {
        if (filename.startsWith(CPU_FILE_NAME_PREFIX)) {
            return "CPU";
        }
        if (filename.startsWith(MEM_FILE_NAME_PREFIX)) {
            return "Memory";
        }
        return null;
    }

    private static String timestamp(String filename) {
        String prefix = filename.startsWith(CPU_FILE_NAME_PREFIX) ? CPU_FILE_NAME_PREFIX : MEM_FILE_NAME_PREFIX;
        int end = filename.indexOf(".html.tar.gz");
        if (!filename.startsWith(prefix) || end <= prefix.length()) {
            return null;
        }
        String value = filename.substring(prefix.length(), end);
        return parseTime(value) == null ? null : value;
    }

    private static Date parseTime(String value) {
        try {
            return new SimpleDateFormat(TIME_FORMAT).parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String extractHtml(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
                GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
                TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".html")) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = tar.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
