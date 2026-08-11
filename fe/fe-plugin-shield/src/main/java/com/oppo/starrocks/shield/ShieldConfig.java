package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ShieldConfig {
    public static final String DOMAIN = "shield.api.domain";
    public static final String APP_KEY = "shield.api.app_key";
    public static final String OPERATOR = "shield.api.operator";
    public static final String SYS_ID = "shield.api.sys_id";
    public static final String AREA_CODE = "shield.api.area_code";
    public static final String USER_APP_GROUP_PATH = "shield.api.user_app_group_path";
    public static final String GROUP_PERMISSIONS_PATH = "shield.api.group_permissions_path";
    public static final String USER_PERMISSIONS_PATH = "shield.api.user_permissions_path";
    public static final String SUPER_ADMIN_USERS = "shield.super_admin_users";
    public static final String CACHE_TTL_SECONDS = "shield.permission.cache.ttl.seconds";
    public static final String RPD_AREA_FILTER = "shield.rpd.area_filter";
    public static final String SLOW_THRESHOLD_MS = "shield.api.slow.threshold.ms";
    public static final String CONNECT_TIMEOUT_MS = "shield.api.connect.timeout.ms";
    public static final String READ_TIMEOUT_MS = "shield.api.read.timeout.ms";
    public static final String RETRY_COUNT = "shield.api.retry.count";
    public static final String RETRY_DELAY_MS = "shield.api.retry.delay.ms";
    public static final String REQUEST_AUTHORITIES = "shield.api.request_authorities";
    public static final String AUTH_ENABLED = "shield.auth.enabled";

    private static final String DEFAULT_REQUEST_AUTHORITIES = "select,create,admin";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    private static final int DEFAULT_RETRY_COUNT = 3;
    private static final int DEFAULT_RETRY_DELAY_MS = 200;

    private final String domain;
    private final String appKey;
    private final String operator;
    private final String sysId;
    private final String areaCode;
    private final String userAppGroupPath;
    private final String groupPermissionsPath;
    private final String userPermissionsPath;
    private final Set<String> superAdminUsers;
    private final long cacheTtlSeconds;
    private final String rpdAreaFilter;
    private final long slowThresholdMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int retryCount;
    private final int retryDelayMs;
    private final String requestAuthorities;
    private final boolean authEnabled;

    public ShieldConfig(Map<String, String> properties) {
        this.authEnabled = Boolean.parseBoolean(properties.getOrDefault(AUTH_ENABLED, "true"));
        if (authEnabled) {
            this.domain = getRequired(properties, DOMAIN);
            this.appKey = getRequired(properties, APP_KEY);
        } else {
            this.domain = trimOrEmpty(properties.get(DOMAIN));
            this.appKey = trimOrEmpty(properties.get(APP_KEY));
        }
        this.operator = properties.getOrDefault(OPERATOR, "");
        this.sysId = properties.getOrDefault(SYS_ID, "starrocks");
        this.areaCode = properties.getOrDefault(AREA_CODE, "china1");
        this.userAppGroupPath = properties.getOrDefault(
                USER_APP_GROUP_PATH, "/oauthority/api/getUserAppGroup");
        this.groupPermissionsPath = properties.getOrDefault(
                GROUP_PERMISSIONS_PATH, "/oauthority/api/getResourcesByGroupID");
        this.userPermissionsPath = properties.getOrDefault(
                USER_PERMISSIONS_PATH, "/oauthority/api/getResourcesByUser");
        this.superAdminUsers = parseSuperAdminUsers(properties.get(SUPER_ADMIN_USERS));
        this.cacheTtlSeconds = Long.parseLong(properties.getOrDefault(CACHE_TTL_SECONDS, "60"));
        this.rpdAreaFilter = properties.getOrDefault(RPD_AREA_FILTER, areaCode + "/");
        this.slowThresholdMs = Long.parseLong(properties.getOrDefault(SLOW_THRESHOLD_MS, "500"));
        this.connectTimeoutMs = parseNonNegativeInt(properties, CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
        this.readTimeoutMs = parseNonNegativeInt(properties, READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        this.retryCount = parseNonNegativeInt(properties, RETRY_COUNT, DEFAULT_RETRY_COUNT);
        this.retryDelayMs = parseNonNegativeInt(properties, RETRY_DELAY_MS, DEFAULT_RETRY_DELAY_MS);
        this.requestAuthorities = properties.getOrDefault(REQUEST_AUTHORITIES, DEFAULT_REQUEST_AUTHORITIES);
    }

    private static int parseNonNegativeInt(Map<String, String> properties, String key, int defaultValue) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0) {
            throw new IllegalArgumentException("Catalog property must be non-negative: " + key);
        }
        return parsed;
    }

    private static String getRequired(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required catalog property: " + key);
        }
        return value.trim();
    }

    private static String trimOrEmpty(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return value.trim();
    }

    private static Set<String> parseSuperAdminUsers(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> users = new HashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(users::add);
        return users;
    }

    public String getDomain() {
        return domain;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getOperator() {
        return operator;
    }

    public String getSysId() {
        return sysId;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public String getUserAppGroupPath() {
        return userAppGroupPath;
    }

    public String getGroupPermissionsPath() {
        return groupPermissionsPath;
    }

    public String getUserPermissionsPath() {
        return userPermissionsPath;
    }

    public Set<String> getSuperAdminUsers() {
        return superAdminUsers;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public String getRpdAreaFilter() {
        return rpdAreaFilter;
    }

    public long getSlowThresholdMs() {
        return slowThresholdMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    public String getRequestAuthorities() {
        return requestAuthorities;
    }

    public List<String> getRequestAuthorityList() {
        return Arrays.stream(requestAuthorities.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
