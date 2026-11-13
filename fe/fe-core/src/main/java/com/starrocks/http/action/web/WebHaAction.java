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

import com.starrocks.catalog.Database;
import com.starrocks.ha.HAProtocol;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.journal.Journal;
import com.starrocks.leader.MetaHelper;
import com.starrocks.persist.Storage;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Frontend;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebHaAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/ha";

    public WebHaAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebHaAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        GlobalStateMgr state = GlobalStateMgr.getCurrentState();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("feType", state.getFeType().toString());
        body.put("leader", state.isLeader());
        Journal journal = state.getJournal();
        body.put("journalId", journal == null ? 0
                : state.isLeader() ? state.getMaxJournalId() : state.getReplayedJournalId());
        body.put("canRead", state.canRead());
        body.put("databases", journal == null ? List.of() : databaseNames(journal.getDatabaseNames()));
        body.put("checkpoint", checkpoint());
        body.put("nodes", nodes(state.getHaProtocol()));
        body.put("frontends", frontends());
        body.put("removedFrontends", state.getNodeMgr().getRemovedFrontendNames());
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private static List<String> databaseNames(List<Long> dbIds) {
        List<String> names = new ArrayList<>();
        for (Long dbId : dbIds) {
            Database database = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
            names.add(database == null ? String.valueOf(dbId) : database.getFullName());
        }
        return names;
    }

    private static Map<String, Object> checkpoint() {
        File image;
        try {
            Storage storage = new Storage(MetaHelper.getImageFileDir(true));
            image = storage.getCurrentImageFile();
            if (image == null) {
                return null;
            }
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("version", storage.getImageJournalId());
            checkpoint.put("time", image.lastModified());
            return checkpoint;
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> nodes(HAProtocol protocol) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("electable", addresses(protocol == null ? null : protocol.getElectableNodes(true)));
        nodes.put("observer", addresses(protocol == null ? null : protocol.getObserverNodes()));
        return nodes;
    }

    private static List<Map<String, Object>> addresses(List<InetSocketAddress> addresses) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (addresses == null) {
            return result;
        }
        for (InetSocketAddress address : addresses) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("host", address.getHostString());
            node.put("port", address.getPort());
            result.add(node);
        }
        return result;
    }

    private static List<Map<String, Object>> frontends() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Frontend> frontends = GlobalStateMgr.getCurrentState().getNodeMgr().getFrontends(null);
        if (frontends == null) {
            return result;
        }
        for (Frontend frontend : frontends) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", frontend.getNodeName());
            item.put("host", frontend.getHost());
            item.put("role", frontend.getRole().toString());
            item.put("alive", frontend.isAlive());
            item.put("queryPort", frontend.getQueryPort());
            item.put("rpcPort", frontend.getRpcPort());
            item.put("editLogPort", frontend.getEditLogPort());
            item.put("replayedJournalId", frontend.getReplayedJournalId());
            item.put("version", frontend.getFeVersion());
            item.put("lastUpdateTime", frontend.getLastUpdateTime());
            item.put("startTime", frontend.getStartTime());
            item.put("heapUsedPercent", frontend.getHeapUsedPercent());
            item.put("cpuCores", frontend.getCpuCores());
            item.put("fid", frontend.getFid());
            result.add(item);
        }
        return result;
    }
}
