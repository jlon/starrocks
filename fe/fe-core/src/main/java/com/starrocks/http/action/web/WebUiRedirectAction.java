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

/**
 * Sends browsers from the legacy landing page to the SPA. Requests that carry an
 * Authorization header keep seeing the legacy page for one release.
 */
public class WebUiRedirectAction extends BaseAction {
    private final String location;

    public WebUiRedirectAction(ActionController controller, String location) {
        super(controller);
        this.location = location;
    }

    @Override
    public void execute(BaseRequest request, BaseResponse response) {
        response.updateHeader("Location", location);
        response.updateHeader("Cache-Control", "no-store");
        writeResponse(request, response, HttpResponseStatus.FOUND);
    }
}
