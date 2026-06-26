package com.oppo.starrocks.shield;

import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

/**
 * Unified latency logging for Shield plugin.
 */
final class ShieldTimingLog {
    private ShieldTimingLog() {
    }

    static long startNanos() {
        return System.nanoTime();
    }

    static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    static void logPermissionLoad(Logger log, long slowThresholdMs, String cacheKey,
                                  boolean cacheHit, long costMs, int tableCount) {
        if (cacheHit && costMs < slowThresholdMs) {
            log.debug("Shield permission cache hit, user={}, costMs={}, tableCount={}",
                    cacheKey, costMs, tableCount);
            return;
        }
        if (costMs >= slowThresholdMs) {
            log.warn("Shield permission load slow, user={}, cacheHit={}, costMs={}, tableCount={}, thresholdMs={}",
                    cacheKey, cacheHit, costMs, tableCount, slowThresholdMs);
        } else {
            log.info("Shield permission load, user={}, cacheHit={}, costMs={}, tableCount={}",
                    cacheKey, cacheHit, costMs, tableCount);
        }
    }

    static void logAuthCheck(Logger log, long slowThresholdMs, String checkType, String user,
                             String resource, boolean allowed, long costMs) {
        if (costMs >= slowThresholdMs) {
            log.warn("Shield auth check slow, type={}, user={}, resource={}, allowed={}, costMs={}, thresholdMs={}",
                    checkType, user, resource, allowed, costMs, slowThresholdMs);
        } else {
            log.info("Shield auth check, type={}, user={}, resource={}, allowed={}, costMs={}",
                    checkType, user, resource, allowed, costMs);
        }
    }
}
