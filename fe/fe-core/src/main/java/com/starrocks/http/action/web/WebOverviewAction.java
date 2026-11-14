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

import com.google.common.base.Suppliers;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.common.Config;
import com.starrocks.common.Version;
import com.starrocks.common.util.ProfileManager;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.qe.ConnectScheduler;
import com.starrocks.qe.QeProcessorImpl;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.service.ExecuteEnv;
import com.starrocks.system.SystemInfoService;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;

public class WebOverviewAction extends WebApiAction {
    private static final String PATH = "/api/web/v1/overview";
    private static final long HARDWARE_CACHE_MILLIS = 60_000;
    private static volatile Map<String, Object> hardwareCache;
    private static volatile long hardwareCacheAt;

    public WebOverviewAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        controller.registerHandler(HttpMethod.GET, PATH, new WebOverviewAction(controller));
    }

    @Override
    public void executeGet(BaseRequest request, BaseResponse response) {
        if (authenticate(request, response) == null) {
            return;
        }

        GlobalStateMgr state = GlobalStateMgr.getCurrentState();
        SystemInfoService cluster = state.getNodeMgr().getClusterInfo();

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version", Version.STARROCKS_VERSION);
        version.put("commit", Version.STARROCKS_COMMIT_HASH);
        version.put("buildTime", Version.STARROCKS_BUILD_TIME);

        Map<String, Object> fe = new LinkedHashMap<>();
        fe.put("type", state.getFeType().name());
        fe.put("leader", state.isLeader());

        Map<String, Object> backends = new LinkedHashMap<>();
        backends.put("total", cluster.getTotalBackendNumber());
        backends.put("alive", cluster.getAliveBackendNumber());

        Map<String, Object> computeNodes = new LinkedHashMap<>();
        computeNodes.put("total", cluster.getTotalComputeNodeNumber());
        computeNodes.put("alive", cluster.getAliveComputeNodeNumber());

        Map<String, Object> queries = new LinkedHashMap<>();
        queries.put("running", QeProcessorImpl.INSTANCE.getQueryStatistics().size());
        ConnectContext ctx = ConnectContext.get();
        boolean checkAccess = Config.authorization_enable_query_profile_access_check;
        Supplier<Boolean> hasOperate =
                Suppliers.memoize(() -> Authorizer.hasSystemAction(ctx, PrivilegeType.OPERATE));
        queries.put("finished", ProfileManager.getInstance().getAllQueries(
                element -> !checkAccess || Authorizer.canReadQueryProfile(ctx, element, hasOperate)).size());

        Map<String, Object> sessions = new LinkedHashMap<>();
        ConnectScheduler scheduler = ExecuteEnv.getInstance().getScheduler();
        sessions.put("total", scheduler == null ? 0 : scheduler.listConnection(ConnectContext.get(), null).size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("fe", fe);
        body.put("backends", backends);
        body.put("computeNodes", computeNodes);
        body.put("queries", queries);
        body.put("sessions", sessions);
        body.put("hardware", hardware());
        writeJson(request, response, HttpResponseStatus.OK, body);
    }

    private static Map<String, Object> hardware() {
        Map<String, Object> cached = hardwareCache;
        if (cached != null && System.currentTimeMillis() - hardwareCacheAt < HARDWARE_CACHE_MILLIS) {
            return cached;
        }
        synchronized (WebOverviewAction.class) {
            if (hardwareCache != null && System.currentTimeMillis() - hardwareCacheAt < HARDWARE_CACHE_MILLIS) {
                return hardwareCache;
            }
            hardwareCache = loadHardware();
            hardwareCacheAt = System.currentTimeMillis();
            return hardwareCache;
        }
    }

    private static Map<String, Object> loadHardware() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", Config.http_web_page_display_hardware);
        if (!Config.http_web_page_display_hardware) {
            return result;
        }
        try {
            SystemInfo systemInfo = new SystemInfo();
            OperatingSystem os = systemInfo.getOperatingSystem();
            HardwareAbstractionLayer hal = systemInfo.getHardware();
            CentralProcessor cpu = hal.getProcessor();
            GlobalMemory memory = hal.getMemory();

            Map<String, Object> osInfo = new LinkedHashMap<>();
            osInfo.put("family", os.getFamily());
            osInfo.put("version", os.getVersionInfo().getVersion());
            osInfo.put("manufacturer", os.getManufacturer());
            result.put("os", osInfo);

            Map<String, Object> cpuInfo = new LinkedHashMap<>();
            cpuInfo.put("name", cpu.getProcessorIdentifier().getName());
            cpuInfo.put("physicalCores", cpu.getPhysicalProcessorCount());
            cpuInfo.put("logicalCores", cpu.getLogicalProcessorCount());
            result.put("cpu", cpuInfo);

            Map<String, Object> memoryInfo = new LinkedHashMap<>();
            memoryInfo.put("total", memory.getTotal());
            memoryInfo.put("available", memory.getAvailable());
            result.put("memory", memoryInfo);

            List<Map<String, Object>> disks = new ArrayList<>();
            for (OSFileStore store : os.getFileSystem().getFileStores()) {
                Map<String, Object> disk = new LinkedHashMap<>();
                disk.put("name", store.getName());
                disk.put("mount", store.getMount());
                disk.put("total", store.getTotalSpace());
                disk.put("usable", store.getUsableSpace());
                disks.add(disk);
            }
            result.put("disks", disks);

            List<Map<String, Object>> networks = new ArrayList<>();
            hal.getNetworkIFs().forEach(network -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", network.getDisplayName());
                item.put("ipv4", network.getIPv4addr());
                item.put("speed", network.getSpeed());
                networks.add(item);
            });
            result.put("networks", networks);
        } catch (Exception | LinkageError t) {
            result.put("error", t.getMessage());
        }
        return result;
    }
}
