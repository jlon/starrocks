package com.oppo.starrocks.shield;

public class UserGroupInfo {
    private final String username;
    private final String psaId;
    private final String groupId;

    public UserGroupInfo(String username, String psaId, String groupId) {
        this.username = username;
        this.psaId = psaId;
        this.groupId = groupId;
    }

    public String getUsername() {
        return username;
    }

    public String getPsaId() {
        return psaId;
    }

    public String getGroupId() {
        return groupId;
    }
}
