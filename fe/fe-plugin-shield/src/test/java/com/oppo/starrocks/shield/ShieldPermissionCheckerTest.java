package com.oppo.starrocks.shield;

import java.util.Map;

import com.starrocks.authorization.PrivilegeType;
import org.junit.Assert;
import org.junit.Test;

public class ShieldPermissionCheckerTest {
    @Test
    public void bypassesAllChecksWhenAuthDisabled() {
        ShieldPermissionChecker checker = new ShieldPermissionChecker(Map.of(
                ShieldConfig.AUTH_ENABLED, "false"
        ));

        Assert.assertTrue(checker.hasDatabasePermission("80372263_37422", "ad_model", PrivilegeType.SELECT));
        Assert.assertTrue(checker.hasTablePermission("80372263_37422", "ad_model", "demo_table", PrivilegeType.SELECT));
        Assert.assertTrue(checker.hasDatabasePermission("unknown_user", "any_db", PrivilegeType.ANY));
        Assert.assertTrue(checker.hasTablePermission("unknown_user", "any_db", "any_table", PrivilegeType.INSERT));
    }
}
