package com.oppo.starrocks.shield;

public class ResourcePermission {
    private final String rpd;
    private final String authority;
    private final String expireTime;

    public ResourcePermission(String rpd, String authority) {
        this(rpd, authority, null);
    }

    public ResourcePermission(String rpd, String authority, String expireTime) {
        this.rpd = rpd;
        this.authority = authority;
        this.expireTime = expireTime;
    }

    public String getRpd() {
        return rpd;
    }

    public String getAuthority() {
        return authority;
    }

    public String getExpireTime() {
        return expireTime;
    }
}
