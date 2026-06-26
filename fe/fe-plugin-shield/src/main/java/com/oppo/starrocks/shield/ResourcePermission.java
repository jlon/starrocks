package com.oppo.starrocks.shield;

public class ResourcePermission {
    private final String rpd;
    private final String authority;

    public ResourcePermission(String rpd, String authority) {
        this.rpd = rpd;
        this.authority = authority;
    }

    public String getRpd() {
        return rpd;
    }

    public String getAuthority() {
        return authority;
    }
}
