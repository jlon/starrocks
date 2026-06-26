package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real-time Shield permission checker with short-lived cache.
 */
public class ShieldPermissionChecker {
    private static final Logger LOG = LogManager.getLogger(ShieldPermissionChecker.class);

    private final ShieldConfig config;
    private final ShieldApiClient apiClient;
    private final LoadingCache<String, List<DatabaseTable>> permissionCache;

    public ShieldPermissionChecker(Map<String, String> properties) {
        this.config = new ShieldConfig(properties);
        this.apiClient = new ShieldApiClient(config);
        this.permissionCache = CacheBuilder.newBuilder()
                .expireAfterWrite(config.getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(10000)
                .build(new CacheLoader<>() {
                    @Override
                    public List<DatabaseTable> load(String cacheKey) {
                        ShieldUserIdentity identity = ShieldUserIdentity.fromCacheKey(cacheKey);
                        return apiClient.loadDatabaseTables(identity.getUsername(), identity.getPsaId());
                    }
                });
        LOG.info("Shield permission cache enabled, ttlSeconds={}, slowThresholdMs={}",
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
        boolean cacheHit = cached != null;
        try {
            List<DatabaseTable> result = cacheHit ? cached : permissionCache.get(cacheKey);
            long costMs = ShieldTimingLog.elapsedMs(start);
            ShieldTimingLog.logPermissionLoad(LOG, config.getSlowThresholdMs(), cacheKey,
                    cacheHit, costMs, result.size());
            return result;
        } catch (ExecutionException e) {
            long costMs = ShieldTimingLog.elapsedMs(start);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.error("Failed to load Shield permissions for user {}, cacheHit={}, costMs={}",
                    cacheKey, cacheHit, costMs, cause);
            throw new ShieldApiException("Failed to load Shield permissions for user " + cacheKey, cause);
        }
    }

    public void invalidateAll() {
        permissionCache.invalidateAll();
    }
}
