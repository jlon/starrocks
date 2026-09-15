package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.starrocks.authorization.PrivilegeType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real-time Shield permission checker with short-lived cache.
 * Only non-empty permission lists are cached so a transient deny does not block
 * re-checks after Shield grants access on another timeline.
 */
public class ShieldPermissionChecker {
    private static final Logger LOG = LogManager.getLogger(ShieldPermissionChecker.class);

    private final ShieldConfig config;
    private final ShieldApiClient apiClient;
    private final Cache<String, List<ShieldPermission>> permissionCache;
    private final Cache<String, List<ShieldPermission>> selectedGroupPermissionCache;

    public ShieldPermissionChecker(Map<String, String> properties) {
        this(new ShieldConfig(properties), null);
    }

    ShieldPermissionChecker(ShieldConfig config, ShieldApiClient apiClient) {
        this.config = config;
        if (config.isAuthEnabled()) {
            this.apiClient = apiClient == null ? new ShieldApiClient(config) : apiClient;
            this.permissionCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(config.getCacheTtlSeconds(), TimeUnit.SECONDS)
                    .maximumSize(10000)
                    .build();
            this.selectedGroupPermissionCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(config.getCacheTtlSeconds(), TimeUnit.SECONDS)
                    .maximumSize(10000)
                    .build();
            LOG.info("Shield permission cache enabled, ttlSeconds={}, requestAuthorities={}, denyNotCached=true, "
                            + "slowThresholdMs={}, connectTimeoutMs={}, readTimeoutMs={}, retryCount={}, retryDelayMs={}",
                    config.getCacheTtlSeconds(), config.getRequestAuthorities(), config.getSlowThresholdMs(),
                    config.getConnectTimeoutMs(), config.getReadTimeoutMs(),
                    config.getRetryCount(), config.getRetryDelayMs());
        } else {
            this.apiClient = null;
            this.permissionCache = null;
            this.selectedGroupPermissionCache = null;
            LOG.warn("Shield auth disabled for catalog; all database/table permission checks are bypassed");
        }
    }

    public boolean isSuperAdmin(String starRocksUser) {
        return config.getSuperAdminUsers().contains(starRocksUser);
    }

    public boolean hasTablePermission(String starRocksUser, String database, String table,
                                      PrivilegeType privilegeType) {
        return hasTablePermission(starRocksUser, database, table, privilegeType, null);
    }

    public boolean hasTablePermission(String starRocksUser, String database, String table,
                                      PrivilegeType privilegeType, String selectedGroupId) {
        if (!config.isAuthEnabled()) {
            return true;
        }

        long start = ShieldTimingLog.startNanos();
        if (isSuperAdmin(starRocksUser)) {
            ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "TABLE", starRocksUser,
                    database + "." + table, true, ShieldTimingLog.elapsedMs(start));
            return true;
        }

        ShieldUserIdentity identity = ShieldUserIdentity.parse(starRocksUser);
        if (identity == null) {
            LOG.warn("Cannot parse Shield user identity from StarRocks user: {}", starRocksUser);
            ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "TABLE", starRocksUser,
                    database + "." + table, false, ShieldTimingLog.elapsedMs(start));
            return false;
        }

        Predicate<ShieldPermission> matcher = permission -> permission.matches(database, table)
                && permission.satisfiesTable(privilegeType);
        boolean allowed = hasPermissionWithFallback(identity, selectedGroupId, matcher);
        ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "TABLE", starRocksUser,
                database + "." + table, allowed, ShieldTimingLog.elapsedMs(start));
        return allowed;
    }

    public boolean hasDatabasePermission(String starRocksUser, String database, PrivilegeType privilegeType) {
        return hasDatabasePermission(starRocksUser, database, privilegeType, null);
    }

    public boolean hasDatabasePermission(String starRocksUser, String database, PrivilegeType privilegeType,
                                         String selectedGroupId) {
        if (!config.isAuthEnabled()) {
            return true;
        }

        long start = ShieldTimingLog.startNanos();
        if (isSuperAdmin(starRocksUser)) {
            ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "DATABASE", starRocksUser,
                    database, true, ShieldTimingLog.elapsedMs(start));
            return true;
        }

        ShieldUserIdentity identity = ShieldUserIdentity.parse(starRocksUser);
        if (identity == null) {
            ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "DATABASE", starRocksUser,
                    database, false, ShieldTimingLog.elapsedMs(start));
            return false;
        }

        Predicate<ShieldPermission> matcher = permission -> permission.matchesDatabase(database)
                && permission.satisfiesDatabase(privilegeType);
        boolean allowed = hasPermissionWithFallback(identity, selectedGroupId, matcher);
        ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "DATABASE", starRocksUser,
                database, allowed, ShieldTimingLog.elapsedMs(start));
        return allowed;
    }

    private boolean hasPermissionWithFallback(
            ShieldUserIdentity identity, String selectedGroupId, Predicate<ShieldPermission> matcher) {
        if (loadPermissions(identity).stream().anyMatch(matcher)) {
            return true;
        }

        String normalizedGroupId = normalizeGroupId(selectedGroupId);
        if (normalizedGroupId != null) {
            List<ShieldPermission> groupPermissions = loadSelectedGroupPermissions(normalizedGroupId);
            boolean allowed = groupPermissions.stream().anyMatch(matcher);
            LOG.info("Shield selected group fallback, user={}, psaId={}, groupId={}, permissionCount={}, allowed={}",
                    identity.getUsername(), identity.getPsaId(), normalizedGroupId, groupPermissions.size(), allowed);
            return allowed;
        }

        LOG.info("Shield permission denied without selected group fallback, user={}, psaId={}",
                identity.getUsername(), identity.getPsaId());
        return false;
    }

    private static String normalizeGroupId(String groupId) {
        if (groupId == null || groupId.trim().isEmpty()) {
            return null;
        }
        return groupId.trim();
    }

    private List<ShieldPermission> loadPermissions(ShieldUserIdentity identity) {
        String cacheKey = identity.toCacheKey();
        long start = ShieldTimingLog.startNanos();
        List<ShieldPermission> cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            long costMs = ShieldTimingLog.elapsedMs(start);
            ShieldTimingLog.logPermissionLoad(LOG, config.getSlowThresholdMs(), cacheKey,
                    true, costMs, cached.size());
            return cached;
        }

        List<ShieldPermission> result = apiClient.loadPermissions(identity.getUsername(), identity.getPsaId());
        long costMs = ShieldTimingLog.elapsedMs(start);
        if (!result.isEmpty()) {
            permissionCache.put(cacheKey, result);
        } else {
            permissionCache.invalidate(cacheKey);
        }
        ShieldTimingLog.logPermissionLoad(LOG, config.getSlowThresholdMs(), cacheKey,
                false, costMs, result.size());
        return result;
    }

    private List<ShieldPermission> loadSelectedGroupPermissions(String selectedGroupId) {
        String cacheKey = selectedGroupId.toLowerCase(Locale.ROOT);
        List<ShieldPermission> cached = selectedGroupPermissionCache.getIfPresent(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        List<ShieldPermission> result = apiClient.loadSelectedGroupPermissions(selectedGroupId);
        if (!result.isEmpty()) {
            selectedGroupPermissionCache.put(cacheKey, result);
        } else {
            selectedGroupPermissionCache.invalidate(cacheKey);
        }
        return result;
    }

    public void invalidateAll() {
        if (permissionCache != null) {
            permissionCache.invalidateAll();
        }
        if (selectedGroupPermissionCache != null) {
            selectedGroupPermissionCache.invalidateAll();
        }
    }
}
