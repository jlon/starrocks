package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
    private final Cache<String, List<DatabaseTable>> permissionCache;

    public ShieldPermissionChecker(Map<String, String> properties) {
        this.config = new ShieldConfig(properties);
        this.apiClient = new ShieldApiClient(config);
        this.permissionCache = CacheBuilder.newBuilder()
                .expireAfterWrite(config.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(10000)
                .build();
        LOG.info("Shield permission cache enabled, ttlSeconds={}, denyNotCached=true, slowThresholdMs={}",
                config.getCacheTtlSeconds(), config.getSlowThresholdMs());
    }

    public boolean isSuperAdmin(String starRocksUser) {
        return config.getSuperAdminUsers().contains(starRocksUser);
    }

    public boolean hasTablePermission(String starRocksUser, String database, String table) {
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

        List<DatabaseTable> tables = loadPermissions(identity);
        boolean allowed = tables.stream().anyMatch(item -> item.matches(database, table));
        ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "TABLE", starRocksUser,
                database + "." + table, allowed, ShieldTimingLog.elapsedMs(start));
        return allowed;
    }

    public boolean hasDatabasePermission(String starRocksUser, String database) {
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

        List<DatabaseTable> tables = loadPermissions(identity);
        boolean allowed = tables.stream().anyMatch(item -> item.matchesDatabase(database));
        ShieldTimingLog.logAuthCheck(LOG, config.getSlowThresholdMs(), "DATABASE", starRocksUser,
                database, allowed, ShieldTimingLog.elapsedMs(start));
        return allowed;
    }

    private List<DatabaseTable> loadPermissions(ShieldUserIdentity identity) {
        String cacheKey = identity.toCacheKey();
        long start = ShieldTimingLog.startNanos();
        List<DatabaseTable> cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            long costMs = ShieldTimingLog.elapsedMs(start);
            ShieldTimingLog.logPermissionLoad(LOG, config.getSlowThresholdMs(), cacheKey,
                    true, costMs, cached.size());
            return cached;
        }

        List<DatabaseTable> result = apiClient.loadDatabaseTables(identity.getUsername(), identity.getPsaId());
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

    public void invalidateAll() {
        permissionCache.invalidateAll();
    }
}
