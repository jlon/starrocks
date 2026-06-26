package com.oppo.starrocks.shield;

/**
 * Parses StarRocks login user into Shield identity.
 * Sync service creates users as {@code {username}_{psaId}}.
 */
public class ShieldUserIdentity {
    private final String username;
    private final String psaId;

    private ShieldUserIdentity(String username, String psaId) {
        this.username = username;
        this.psaId = psaId;
    }

    public static ShieldUserIdentity parse(String starRocksUser) {
        if (starRocksUser == null || starRocksUser.isEmpty()) {
            return null;
        }
        int separatorIndex = starRocksUser.lastIndexOf('_');
        if (separatorIndex <= 0 || separatorIndex >= starRocksUser.length() - 1) {
            return null;
        }
        String username = starRocksUser.substring(0, separatorIndex);
        String psaId = starRocksUser.substring(separatorIndex + 1);
        return new ShieldUserIdentity(username, psaId);
    }

    public static ShieldUserIdentity fromCacheKey(String cacheKey) {
        int separatorIndex = cacheKey.indexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= cacheKey.length() - 1) {
            throw new IllegalArgumentException("Invalid Shield cache key: " + cacheKey);
        }
        return new ShieldUserIdentity(cacheKey.substring(0, separatorIndex), cacheKey.substring(separatorIndex + 1));
    }

    public String getUsername() {
        return username;
    }

    public String getPsaId() {
        return psaId;
    }

    public String toCacheKey() {
        return username + ":" + psaId;
    }
}
