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

package com.starrocks.http;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class WebLegacyRedirectTest extends StarRocksHttpTestCase {

    @Test
    public void testBrowserLegacyPathRedirectsToUi() throws IOException {
        Request request = new Request.Builder()
                .get()
                .addHeader("Accept", "text/html")
                .url(BASE_URL + "/system")
                .build();
        OkHttpClient client = networkClient.newBuilder().followRedirects(false).build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(302, response.code());
            Assertions.assertEquals("/ui/system", response.header("Location"));
        }
    }

    @Test
    public void testAuthorizedLegacyPathKeepsOldPage() throws IOException {
        Request request = new Request.Builder()
                .get()
                .addHeader("Accept", "text/html")
                .addHeader("Authorization", rootAuth)
                .url(BASE_URL + "/system")
                .build();
        try (Response response = networkClient.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertNotNull(response.body());
            Assertions.assertTrue(response.body().string().contains("System"));
        }
    }
}
