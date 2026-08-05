package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.starrocks.authorization.PrivilegeType;
import org.junit.Assert;
import org.junit.Test;

public class ShieldPermissionTest {
    @Test
    public void databaseLevelCreateSatisfiesAllTablePrivileges() {
        ShieldPermission permission = new ShieldPermission("tmp", "*", ShieldPermission.Authority.CREATE);
        Assert.assertTrue(permission.matches("tmp", "any_new_table"));
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.SELECT));
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.INSERT));
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.DROP));
        Assert.assertTrue(permission.satisfiesDatabase(PrivilegeType.CREATE_TABLE));
    }

    @Test
    public void tableLevelCreateSatisfiesTableButNotDatabaseCreate() {
        ShieldPermission permission = new ShieldPermission("informer_dw", "imei_bitmapid_mapping_all_d",
                ShieldPermission.Authority.CREATE);
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.SELECT));
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.INSERT));
        Assert.assertFalse(permission.satisfiesDatabase(PrivilegeType.CREATE_TABLE));
    }

    @Test
    public void tableLevelSelectIsReadOnly() {
        ShieldPermission permission = new ShieldPermission("tmp", "existing_table", ShieldPermission.Authority.SELECT);
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.SELECT));
        Assert.assertFalse(permission.satisfiesTable(PrivilegeType.INSERT));
        Assert.assertFalse(permission.satisfiesDatabase(PrivilegeType.CREATE_TABLE));
    }

    @Test
    public void databaseLevelCreateDoesNotMatchOtherDatabase() {
        ShieldPermission permission = new ShieldPermission("tmp", "*", ShieldPermission.Authority.CREATE);
        Assert.assertFalse(permission.matches("other_db", "t1"));
        Assert.assertFalse(permission.matchesDatabase("other_db"));
    }
}
