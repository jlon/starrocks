package com.oppo.starrocks.shield;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTP client for Shield (神盾) authorization APIs.
 * Ported from metastore ShieldApiClientServiceImpl.
 */
public class ShieldApiClient {
    private static final Logger LOG = LogManager.getLogger(ShieldApiClient.class);
    private static final Gson GSON = new Gson();

    private final ShieldConfig config;

    public ShieldApiClient(ShieldConfig config) {
        this.config = config;
    }

    public List<UserGroupInfo> fetchUserGroups(String username) {
        long start = ShieldTimingLog.startNanos();
        try {
            Map<String, Object> params = buildUserGroupParams(username);
            String content = executePost(config.getUserAppGroupPath(), params);
            List<UserGroupInfo> groups = parseUserGroups(username, content);
            long costMs = ShieldTimingLog.elapsedMs(start);
            logApiResult("getUserAppGroup", config.getUserAppGroupPath(),
                    "user=" + username + ", groupCount=" + groups.size(), costMs);
            return groups;
        } catch (RuntimeException e) {
            logApiFailure(config.getUserAppGroupPath(), ShieldTimingLog.elapsedMs(start), e);
            throw e;
        }
    }

    public List<ResourcePermission> fetchGroupPermissions(String operator, String groupId) {
        long start = ShieldTimingLog.startNanos();
        try {
            Map<String, Object> params = buildGroupPermissionParams(operator, groupId);
            String content = executePost(config.getGroupPermissionsPath(), params);
            List<ResourcePermission> permissions = parsePermissions(content);
            long costMs = ShieldTimingLog.elapsedMs(start);
            logApiResult("getResourcesByGroupID", config.getGroupPermissionsPath(),
                    "operator=" + operator + ", groupId=" + groupId + ", permissionCount=" + permissions.size(),
                    costMs);
            return permissions;
        } catch (RuntimeException e) {
            logApiFailure(config.getGroupPermissionsPath(), ShieldTimingLog.elapsedMs(start), e);
            throw e;
        }
    }

    private void logApiResult(String apiName, String path, String detail, long costMs) {
        if (costMs >= config.getSlowThresholdMs()) {
            LOG.warn("Shield API slow, api={}, path={}, {}, costMs={}, thresholdMs={}",
                    apiName, path, detail, costMs, config.getSlowThresholdMs());
        } else {
            LOG.info("Shield API done, api={}, path={}, {}, costMs={}", apiName, path, detail, costMs);
        }
    }

    private void logApiFailure(String path, long costMs, RuntimeException e) {
        LOG.warn("Shield API failed, path={}, costMs={}, error={}", path, costMs, e.getMessage());
    }

    private Map<String, Object> buildBaseParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("operator", config.getOperator());
        params.put("sysID", config.getSysId());
        params.put("reqID", System.currentTimeMillis() / 1000);
        return params;
    }

    private Map<String, Object> buildUserGroupParams(String username) {
        Map<String, Object> params = buildBaseParams();
        params.put("user", username);
        params.put("signature", SignatureUtil.sign(params, config.getAppKey()));
        return params;
    }

    private Map<String, Object> buildGroupPermissionParams(String operator, String groupId) {
        Map<String, Object> params = buildBaseParams();
        params.put("operator", operator);
        params.put("groupID", groupId);
        params.put("resType", "hive");
        params.put("authority", config.getRequestAuthorities());
        params.put("signature", SignatureUtil.sign(params, config.getAppKey()));
        return params;
    }

    private String executePost(String path, Map<String, Object> params) {
        int maxAttempts = config.getRetryCount() + 1;
        ShieldApiException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doExecutePost(path, params, attempt, maxAttempts);
            } catch (ShieldApiException e) {
                lastFailure = e;
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    throw e;
                }
                LOG.warn("Shield API retryable failure, path={}, attempt={}/{}, error={}",
                        path, attempt, maxAttempts, e.getMessage());
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    private String doExecutePost(String path, Map<String, Object> params, int attempt, int maxAttempts) {
        String url = config.getDomain() + path;
        long start = ShieldTimingLog.startNanos();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(config.getConnectTimeoutMs());
            connection.setReadTimeout(config.getReadTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] body = GSON.toJson(params).getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int statusCode = connection.getResponseCode();
            String content = readResponse(connection);
            long costMs = ShieldTimingLog.elapsedMs(start);
            if (statusCode != HttpURLConnection.HTTP_OK) {
                LOG.warn("Shield API HTTP error, path={}, statusCode={}, attempt={}/{}, costMs={}, responseSize={}",
                        path, statusCode, attempt, maxAttempts, costMs, content.length());
                boolean retryable = statusCode >= 500 || statusCode == 429;
                throw new ShieldApiException("Shield API HTTP " + statusCode + " for " + path + ": " + content,
                        null, retryable);
            }
            LOG.debug("Shield API HTTP ok, path={}, attempt={}/{}, costMs={}, responseSize={}",
                    path, attempt, maxAttempts, costMs, content.length());
            return content;
        } catch (IOException e) {
            long costMs = ShieldTimingLog.elapsedMs(start);
            LOG.warn("Shield API IO error, path={}, attempt={}/{}, costMs={}",
                    path, attempt, maxAttempts, costMs, e);
            throw new ShieldApiException("Shield API request failed for " + path, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void sleepBeforeRetry() {
        int delayMs = config.getRetryDelayMs();
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShieldApiException("Shield API retry interrupted for " + config.getDomain(), e);
        }
    }

    private String readResponse(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private List<UserGroupInfo> parseUserGroups(String username, String content) {
        JsonObject response = parseApiResponse(content);
        JsonArray dataArray = response.getAsJsonArray("data");
        if (dataArray == null || dataArray.isEmpty()) {
            return Collections.emptyList();
        }

        List<UserGroupInfo> groups = new ArrayList<>();
        for (JsonElement element : dataArray) {
            JsonObject item = element.getAsJsonObject();
            String psaId = getAsString(item, "psaId");
            String groupId = getAsString(item, "groupID");
            if (groupId != null && psaId != null && !".".equals(groupId)) {
                groups.add(new UserGroupInfo(username, psaId, groupId));
            }
        }
        return groups;
    }

    private List<ResourcePermission> parsePermissions(String content) {
        JsonObject response = parseApiResponse(content);
        JsonArray dataArray = response.getAsJsonArray("data");
        if (dataArray == null || dataArray.isEmpty()) {
            return Collections.emptyList();
        }

        List<ResourcePermission> permissions = new ArrayList<>();
        for (JsonElement element : dataArray) {
            JsonObject item = element.getAsJsonObject();
            String rpd = getAsString(item, "rpd");
            String authority = getAsString(item, "authority");
            if (rpd != null) {
                permissions.add(new ResourcePermission(rpd, authority));
            }
        }
        return permissions;
    }

    private JsonObject parseApiResponse(String content) {
        JsonObject response = JsonParser.parseString(content).getAsJsonObject();
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            String errorMsg = getAsString(response, "desc");
            throw new ShieldApiException("Shield API error: " + errorMsg);
        }
        return response;
    }

    private String getAsString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    List<ShieldPermission> loadPermissions(String username, String psaId) {
        long start = ShieldTimingLog.startNanos();
        List<UserGroupInfo> groups = fetchUserGroups(username).stream()
                .filter(group -> Objects.equals(psaId, group.getPsaId()))
                .collect(Collectors.toList());
        if (groups.isEmpty()) {
            long costMs = ShieldTimingLog.elapsedMs(start);
            LOG.info("Shield loadPermissions, user={}, psaId={}, matchedGroupCount=0, permissionCount=0, costMs={}",
                    username, psaId, costMs);
            return Collections.emptyList();
        }

        RpdParser parser = new RpdParser(config.getRpdAreaFilter());
        List<ShieldPermission> permissions = new ArrayList<>();
        for (UserGroupInfo group : groups) {
            List<ResourcePermission> groupPermissions = fetchGroupPermissions(username, group.getGroupId());
            permissions.addAll(parser.parsePermissions(groupPermissions));
        }
        long costMs = ShieldTimingLog.elapsedMs(start);
        if (costMs >= config.getSlowThresholdMs()) {
            LOG.warn("Shield loadPermissions slow, user={}, psaId={}, matchedGroupCount={}, permissionCount={}, "
                            + "costMs={}, thresholdMs={}",
                    username, psaId, groups.size(), permissions.size(), costMs, config.getSlowThresholdMs());
        } else {
            LOG.info("Shield loadPermissions, user={}, psaId={}, matchedGroupCount={}, permissionCount={}, costMs={}",
                    username, psaId, groups.size(), permissions.size(), costMs);
        }
        return permissions;
    }
}
