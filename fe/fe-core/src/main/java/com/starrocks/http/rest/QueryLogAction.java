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

import com.google.common.base.Strings;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.common.Config;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.Authorizer;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists and searches FE audit logs.
 *
 * <p>Only regular, uncompressed {@code fe.audit.log*} files are exposed. The search is a literal
 * match, so the endpoint does not execute caller-provided regular expressions.
 */
public class QueryLogAction extends RestBaseAction {
    private static final String AUDIT_LOG_PREFIX = "fe.audit.log";
    private static final String FILE_PARAM = "file";
    private static final String KEYWORD_PARAM = "keyword";
    private static final String PAGE_NUM_PARAM = "page_num";
    private static final String PAGE_SIZE_PARAM = "page_size";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_NUM = 10000;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_KEYWORD_LENGTH = 4096;
    private static final Set<OpenOption> READ_OPTIONS = Set.of(StandardOpenOption.READ);
    private static final int O_RDONLY = 0;
    private static final int O_NONBLOCK = 0x800;
    private static final int O_DIRECTORY = 0x10000;
    private static final int O_NOFOLLOW = 0x20000;
    private static final int O_CLOEXEC = 0x80000;
    private static final NativeLibrary LIBC = NativeLibrary.getInstance("c");
    private static final Function OPEN = LIBC.getFunction("open");
    private static final Function OPENAT = LIBC.getFunction("openat");
    private static final Function CLOSE = LIBC.getFunction("close");

    public QueryLogAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, "/api/query_logs", new QueryLogAction(controller));
        controller.registerHandler(HttpMethod.GET, "/api/query_logs/search", new QueryLogAction(controller));
    }

    @Override
    protected void executeWithoutPassword(BaseRequest request, BaseResponse response) throws AccessDeniedException {
        Authorizer.checkSystemAction(ConnectContext.get(), PrivilegeType.OPERATE);

        if (request.getRequest().uri().startsWith("/api/query_logs/search")) {
            search(request, response);
        } else {
            list(response, request);
        }
    }

    private void list(BaseResponse response, BaseRequest request) {
        try {
            List<Map<String, Object>> logs = new ArrayList<>();
            for (AuditLogFile log : listAuditLogs()) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", log.name);
                file.put("size", log.size);
                logs.add(file);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("logs", logs);
            sendJson(request, response, HttpResponseStatus.OK, result);
        } catch (IOException e) {
            sendError(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to list audit logs");
        }
    }

    private void search(BaseRequest request, BaseResponse response) {
        String fileName = request.getSingleParameter(FILE_PARAM);
        String keyword = request.getSingleParameter(KEYWORD_PARAM);
        if (Strings.isNullOrEmpty(fileName) || Strings.isNullOrEmpty(keyword)) {
            sendError(request, response, HttpResponseStatus.BAD_REQUEST, "Missing file or keyword parameter");
            return;
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            sendError(request, response, HttpResponseStatus.BAD_REQUEST, "Keyword parameter is too long");
            return;
        }

        Integer pageNum = parsePageParameter(request, PAGE_NUM_PARAM, 0, 0, MAX_PAGE_NUM);
        Integer pageSize = parsePageParameter(request, PAGE_SIZE_PARAM, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        if (pageNum == null || pageSize == null) {
            sendError(request, response, HttpResponseStatus.BAD_REQUEST, "Invalid page_num or page_size parameter");
            return;
        }

        AuditLogFile log;
        try {
            log = findAuditLog(fileName);
        } catch (IOException e) {
            sendError(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to list audit logs");
            return;
        }
        if (log == null) {
            sendError(request, response, HttpResponseStatus.NOT_FOUND, "Audit log file not found");
            return;
        }

        long startNanos = System.nanoTime();
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            long skippedMatches = (long) pageNum * pageSize;
            long matched = 0;
            boolean hasMore = false;
            long lineNumber = 0;
            SeekableByteChannel channel = openAuditLog(log.name);
            if (channel == null) {
                sendError(request, response, HttpResponseStatus.NOT_FOUND, "Audit log file not found");
                return;
            }
            try (channel; BufferedReader reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (!line.contains(keyword)) {
                        continue;
                    }
                    if (matched++ < skippedMatches) {
                        continue;
                    }
                    if (results.size() == pageSize) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("lineNumber", lineNumber);
                    result.put("matchedLine", line);
                    result.put("isMatch", true);
                    results.add(result);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("results", results);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("hasMore", hasMore);
            result.put("executionTimeMs", (System.nanoTime() - startNanos) / 1_000_000);
            sendJson(request, response, HttpResponseStatus.OK, result);
        } catch (NoSuchFileException e) {
            sendError(request, response, HttpResponseStatus.NOT_FOUND, "Audit log file not found");
        } catch (IOException e) {
            sendError(request, response, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to search audit log");
        }
    }

    private static Integer parsePageParameter(BaseRequest request, String name, int defaultValue, int minValue,
                                              int maxValue) {
        String value = request.getSingleParameter(name);
        if (Strings.isNullOrEmpty(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= minValue && parsed <= maxValue ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<AuditLogFile> listAuditLogs() throws IOException {
        try (SecureDirectoryStream<Path> logDirectory = openSecureAuditLogDirectory()) {
            List<AuditLogFile> logs = new ArrayList<>();
            for (Path entry : logDirectory) {
                try {
                    AuditLogFile log = getAuditLogFile(logDirectory, entry.getFileName());
                    if (log != null) {
                        logs.add(log);
                    }
                } catch (NoSuchFileException ignored) {
                    // A log can be rotated while the directory is being listed.
                }
            }
            logs.sort(Comparator.comparing(log -> log.name, Comparator.reverseOrder()));
            return logs;
        }
    }

    private static AuditLogFile findAuditLog(String fileName) throws IOException {
        for (AuditLogFile log : listAuditLogs()) {
            if (log.name.equals(fileName)) {
                return log;
            }
        }
        return null;
    }

    private static SecureDirectoryStream<Path> openSecureAuditLogDirectory() throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(Config.audit_log_dir));
        if (!(stream instanceof SecureDirectoryStream<?>)) {
            stream.close();
            throw new IOException("Audit log directory does not support secure access");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secureStream = (SecureDirectoryStream<Path>) stream;
        return secureStream;
    }

    private static AuditLogFile getAuditLogFile(SecureDirectoryStream<Path> directory, Path fileName) throws IOException {
        if (fileName == null || !isAuditLogName(fileName.toString())) {
            return null;
        }
        BasicFileAttributeView attributeView = directory.getFileAttributeView(
                fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributeView == null) {
            throw new IOException("Audit log directory does not support basic file attributes");
        }
        BasicFileAttributes attributes = attributeView.readAttributes();
        return attributes.isRegularFile() ? new AuditLogFile(fileName.toString(), attributes.size()) : null;
    }

    static SeekableByteChannel openAuditLog(String fileName) throws IOException {
        if (!isAuditLogName(fileName)) {
            return null;
        }

        int directoryFd = OPEN.invokeInt(
                new Object[] {Config.audit_log_dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC});
        if (directoryFd < 0) {
            throw new IOException("Failed to open audit log directory");
        }
        try {
            int fileFd = OPENAT.invokeInt(new Object[] {directoryFd, fileName,
                    O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW});
            if (fileFd < 0) {
                return null;
            }
            try {
                // The procfs descriptor path resolves to this already-open file, not the mutable directory entry.
                Path descriptorPath = Path.of("/proc/self/fd/" + fileFd);
                if (!Files.readAttributes(descriptorPath, BasicFileAttributes.class).isRegularFile()) {
                    return null;
                }
                return Files.newByteChannel(descriptorPath, READ_OPTIONS);
            } finally {
                CLOSE.invokeInt(new Object[] {fileFd});
            }
        } finally {
            CLOSE.invokeInt(new Object[] {directoryFd});
        }
    }

    private static boolean isAuditLogName(String fileName) {
        if (Path.of(fileName).getNameCount() != 1) {
            return false;
        }
        return (fileName.equals(AUDIT_LOG_PREFIX) || fileName.startsWith(AUDIT_LOG_PREFIX + "."))
                && !fileName.endsWith(".gz");
    }

    private static class AuditLogFile {
        private final String name;
        private final long size;

        private AuditLogFile(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private void sendError(BaseRequest request, BaseResponse response, HttpResponseStatus status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("errorMessage", message);
        sendJson(request, response, status, result);
    }

    private void sendJson(BaseRequest request, BaseResponse response, HttpResponseStatus status, Object result) {
        try {
            response.setContentType(JSON_CONTENT_TYPE);
            response.appendContent(mapper.writeValueAsString(result));
            writeResponse(request, response, status);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize query log response", e);
        }
    }
}
