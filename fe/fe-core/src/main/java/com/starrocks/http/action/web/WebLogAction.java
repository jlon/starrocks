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
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.Log4jConfig;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import groovy.lang.Tuple3;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class WebLogAction extends WebApiAction {
    private static final String LOGS_PATH = "/api/web/v1/logs";
    private static final String LOG_CONFIG_PATH = "/api/web/v1/log-config";
    private static final String VERBOSE_PATH = "/api/web/v1/log-config/verbose";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;
    private static final Pattern LOG_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.log");
    private static final Pattern VERBOSE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public WebLogAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, LOGS_PATH, new WebLogAction(controller));
        controller.registerHandler(HttpMethod.GET, LOGS_PATH + "/{name}", new WebLogAction(controller));
        controller.registerHandler(HttpMethod.GET, LOG_CONFIG_PATH, new WebLogAction(controller));
        controller.registerHandler(HttpMethod.POST, VERBOSE_PATH, new WebLogAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }
        if (path(request).equals(LOG_CONFIG_PATH)) {
            writeJson(request, response, HttpResponseStatus.OK, config());
            return;
        }
        String name = request.getParams().get("name");
        if (name == null) {
            name = request.getSingleParameter("type");
        }
        if (name == null) {
            list(request, response);
        } else {
            content(request, response, name);
        }
    }

    @Override
    public void executePost(BaseRequest request, BaseResponse response) {
        if (!checkOrigin(request)) {
            writeJson(request, response, HttpResponseStatus.FORBIDDEN,
                    Map.of("message", "invalid origin"));
            return;
        }
        UserIdentity user = authenticate(request, response);
        if (user == null || !requireAdmin(request, response, user)) {
            return;
        }
        if (!path(request).equals(VERBOSE_PATH)) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND,
                    Map.of("message", "not found"));
            return;
        }
        updateVerbose(request, response);
    }

    private static Map<String, Object> config() {
        Tuple3<String, String[], String[]> logging = readLogging();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", logging.getV1() == null ? "UNKNOWN" : logging.getV1());
        body.put("verboseNames", logging.getV2() == null ? new String[0] : logging.getV2());
        body.put("auditNames", logging.getV3() == null ? new String[0] : logging.getV3());
        return body;
    }

    private static Tuple3<String, String[], String[]> readLogging() {
        try {
            return Log4jConfig.updateLogging(null, null, null);
        } catch (IOException e) {
            return new Tuple3<>("UNKNOWN", new String[0], new String[0]);
        }
    }

    private void list(BaseRequest request, BaseResponse response) {
        File dir = new File(Config.sys_log_dir);
        File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".log"));
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (File file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", file.getName());
            entry.put("size", file.length());
            entry.put("modified", file.lastModified());
            entries.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>(config());
        body.put("dir", Config.sys_log_dir);
        body.put("files", entries);
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private void content(BaseRequest request, BaseResponse response, String name) {
        if (!LOG_NAME.matcher(name).matches()) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid log name"));
            return;
        }

        File file = new File(Config.sys_log_dir, name);
        if (!file.isFile()) {
            writeJson(request, response, HttpResponseStatus.NOT_FOUND,
                    Map.of("message", "log not found"));
            return;
        }

        long size = file.length();
        long start = Math.max(0, size - MAX_LOG_BYTES);
        byte[] buffer = new byte[(int) (size - start)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(buffer);
        } catch (IOException e) {
            writeJson(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Map.of("message", "failed to read log file"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("size", size);
        body.put("truncated", start > 0);
        body.put("content", new String(buffer, StandardCharsets.UTF_8));
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private void updateVerbose(BaseRequest request, BaseResponse response) {
        VerboseUpdate update;
        try {
            update = GSON.fromJson(request.getContent(), VerboseUpdate.class);
        } catch (DdlException | JsonSyntaxException e) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid json"));
            return;
        }
        if (update == null) {
            writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                    Map.of("message", "invalid json"));
            return;
        }

        String[] currentNames = readLogging().getV2();
        Set<String> names = new LinkedHashSet<>(Arrays.asList(
                currentNames == null ? new String[0] : currentNames));
        for (String name : update.add == null ? List.<String>of() : update.add) {
            if (name == null || !VERBOSE_NAME.matcher(name).matches()) {
                writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                        Map.of("message", "invalid verbose name"));
                return;
            }
            names.add(name);
        }
        for (String name : update.remove == null ? List.<String>of() : update.remove) {
            if (name == null || !VERBOSE_NAME.matcher(name).matches()) {
                writeJson(request, response, HttpResponseStatus.BAD_REQUEST,
                        Map.of("message", "invalid verbose name"));
                return;
            }
            names.remove(name);
        }

        Tuple3<String, String[], String[]> updated;
        try {
            updated = Log4jConfig.updateLogging(null, names.toArray(new String[0]), null);
        } catch (IOException e) {
            writeJson(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Map.of("message", "failed to update log configuration"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", updated.getV1() == null ? "UNKNOWN" : updated.getV1());
        body.put("verboseNames", updated.getV2() == null ? new String[0] : updated.getV2());
        body.put("auditNames", updated.getV3() == null ? new String[0] : updated.getV3());
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private static class VerboseUpdate {
        private List<String> add;
        private List<String> remove;
    }
}
