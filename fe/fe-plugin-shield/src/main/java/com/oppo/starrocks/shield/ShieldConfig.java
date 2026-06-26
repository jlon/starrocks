package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ShieldConfig {
    public static final String DOMAIN = "shield.api.domain";
    public static final String APP_KEY = "shield.api.app_key";
    public static final String OPERATOR = "shield.api.operator";
    public static final String SYS_ID = "shield.api.sys_id";
    public static final String AREA_CODE = "shield.api.area_code";
    public static final String USER_APP_GROUP_PATH = "shield.api.user_app_group_path";
    public static final String GROUP_PERMISSIONS_PATH = "shield.api.group_permissions_path";
    public static final String SUPER_ADMIN_USERS = "shield.super_admin_users";
    public static final String CACHE_TTL_SECONDS = "shield.permission.cache.ttl.seconds";
    public static final String RPD_AREA_FILTER = "shield.rpd.area_filter";
    public static final String SLOW_THRESHOLD_MS = "shield.api.slow.threshold.ms";

    private final String domain;
    private final String appKey;
    private final String operator;
    private final String sysId;
    private final String areaCode;
    private final String userAppGroupPath;
    private final String groupPermissionsPath;
    private final Set<String> superAdminUsers;
    private final long cacheTtlSeconds;
    private final String rpdAreaFilter;
    private final long slowThresholdMs;

    public ShieldConfig(Map<String, String> properties) {
        this.domain = getRequired(properties, DOMAIN);
        this.appKey = getRequired(properties, APP_KEY);
        this.operator = properties.getOrDefault(OPERATOR, "");
        this.sysId = properties.getOrDefault(SYS_ID, "starrocks");
        this.areaCode = properties.getOrDefault(AREA_CODE, "china1");
        this.userAppGroupPath = properties.getOrDefault(
                USER_APP_GROUP_PATH, "/oauthority/api/getUserAppGroup");
        this.groupPermissionsPath = properties.getOrDefault(
                GROUP_PERMISSIONS_PATH, "/oauthority/api/getResourcesByGroupID");
        this.superAdminUsers = parseSuperAdminUsers(properties.get(SUPER_ADMIN_USERS));
        this.cacheTtlSeconds = Long.parseLong(properties.getOrDefault(CACHE_TTL_SECONDS, "60"));
        this.rpdAreaFilter = properties.getOrDefault(RPD_AREA_FILTER, areaCode + "/");
        this.slowThresholdMs = Long.parseLong(properties.getOrDefault(SLOW_THRESHOLD_MS, "500"));
    }

    private static String getRequired(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required catalog property: " + key);
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
}
