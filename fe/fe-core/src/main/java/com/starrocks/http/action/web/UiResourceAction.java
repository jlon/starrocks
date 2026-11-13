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
import com.starrocks.http.BaseAction;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves the built SPA from {@code webroot/ui}. All unknown extension-less paths fall back to
 * {@code index.html} so client-side routes survive a page refresh.
 */
public class UiResourceAction extends BaseAction {
    private static final String DEFAULT_ROOT = "webroot/ui";
    private final Path rootDir;

    public UiResourceAction(ActionController controller) {
        this(controller, Paths.get(System.getProperty("starrocks.home", "."), DEFAULT_ROOT));
    }

    UiResourceAction(ActionController controller, Path rootDir) {
        super(controller);
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) {
        Path file = resolve(rootDir, request.getRequest().uri());
        if (file == null) {
            writeText(request, response, HttpResponseStatus.BAD_REQUEST, "bad path");
            return;
        }
        if (!Files.isRegularFile(file)) {
            writeText(request, response, HttpResponseStatus.NOT_FOUND, "not found");
            return;
        }

        String name = file.getFileName().toString();
        response.updateHeader("Content-Type", mimeFor(name));
        response.updateHeader("X-Content-Type-Options", "nosniff");
        if ("index.html".equals(name)) {
            response.updateHeader("Cache-Control", "no-cache");
        } else if (name.matches(".*\\.[0-9a-f]{8,}\\.(js|css|woff2|png|svg|ico)$")) {
            response.updateHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
        writeObjectResponse(request, response, HttpResponseStatus.OK, file.toFile(), name, false);
    }

    static Path resolve(Path root, String uri) {
        String path = uri.split("\\?", 2)[0];
        if (!path.equals("/ui") && !path.startsWith("/ui/")) {
            return null;
        }
        String rel = path.equals("/ui") || path.equals("/ui/") ? "index.html" : path.substring(4);
        if (rel.isEmpty()) {
            rel = "index.html";
        }
        if (rel.indexOf('\0') >= 0 || rel.indexOf('\\') >= 0 || rel.startsWith("/")) {
            return null;
        }

        Path base = root.toAbsolutePath().normalize();
        Path candidate = base.resolve(rel).normalize();
        if (!candidate.startsWith(base)) {
            return null;
        }
        if (!Files.isRegularFile(candidate) && !rel.contains(".")) {
            candidate = base.resolve("index.html");
        }
        return candidate;
    }

    private void writeText(BaseRequest request, BaseResponse response, HttpResponseStatus status, String message) {
        response.setContentType("text/plain");
        response.appendContent(message);
        writeResponse(request, response, status);
    }

    private static String mimeFor(String name) {
        if (name.endsWith(".js")) {
            return "application/javascript";
        }
        if (name.endsWith(".css")) {
            return "text/css";
        }
        if (name.endsWith(".html")) {
            return "text/html";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (name.endsWith(".woff")) {
            return "font/woff";
        }
        if (name.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (name.endsWith(".json") || name.endsWith(".map")) {
            return "application/json";
        }
        if (name.endsWith(".wasm")) {
            return "application/wasm";
        }
        return "application/octet-stream";
    }
}
