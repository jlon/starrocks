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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiResourceActionTest {

    @TempDir
    Path root;

    @Test
    void resolvesIndexAndAssets() throws IOException {
        Path index = Files.writeString(root.resolve("index.html"), "index");
        Files.createDirectories(root.resolve("assets"));
        Path app = Files.writeString(root.resolve("assets/app.js"), "app");

        assertEquals(index, UiResourceAction.resolve(root, "/ui"));
        assertEquals(index, UiResourceAction.resolve(root, "/ui/"));
        assertEquals(app, UiResourceAction.resolve(root, "/ui/assets/app.js"));
        assertEquals(app, UiResourceAction.resolve(root, "/ui/assets/app.js?v=1"));
    }

    @Test
    void fallsBackToIndexForClientRoutes() throws IOException {
        Path index = Files.writeString(root.resolve("index.html"), "index");

        assertEquals(index, UiResourceAction.resolve(root, "/ui/backends"));
        assertEquals(index, UiResourceAction.resolve(root, "/ui/queries/123"));
        assertEquals(index, UiResourceAction.resolve(root, "/ui/system"));
    }

    @Test
    void keepsMissingAssetAsIs() {
        Path missing = root.resolve("assets/missing.js");

        assertEquals(missing, UiResourceAction.resolve(root, "/ui/assets/missing.js"));
        assertFalse(Files.isRegularFile(missing));
    }

    @Test
    void setsCacheControlForViteAssets() {
        assertEquals("public, max-age=31536000, immutable", UiResourceAction.cacheControlFor("index-C6tipSqc.js"));
        assertEquals("public, max-age=31536000, immutable", UiResourceAction.cacheControlFor("logo-B4Q-83u.svg"));
        assertEquals("no-cache", UiResourceAction.cacheControlFor("index.html"));
        assertNull(UiResourceAction.cacheControlFor("favicon.ico"));
    }

    @Test
    void rejectsTraversalAndForeignPaths() {
        assertNull(UiResourceAction.resolve(root, "/ui/../secret"));
        assertNull(UiResourceAction.resolve(root, "/ui/assets/../../secret"));
        assertNull(UiResourceAction.resolve(root, "/ui//etc/passwd"));
        assertNull(UiResourceAction.resolve(root, "/ui/assets\\..\\secret"));
        assertNull(UiResourceAction.resolve(root, "/other"));
        assertNotNull(UiResourceAction.resolve(root, "/ui"));
    }
}
