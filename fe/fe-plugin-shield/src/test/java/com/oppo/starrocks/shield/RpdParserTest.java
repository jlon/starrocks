package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.List;

import com.starrocks.authorization.PrivilegeType;
import org.junit.Assert;
import org.junit.Test;

public class RpdParserTest {
    @Test
    public void testParseDatabaseTable() {
        RpdParser parser = new RpdParser("china1/");
        DatabaseTable table = parser.parseDatabaseTable(
                "hive://group@china1/ad_model.db/ad_table?option=select");
        Assert.assertNotNull(table);
        Assert.assertEquals("ad_model", table.getDatabase());
        Assert.assertEquals("ad_table", table.getTable());
        Assert.assertTrue(table.matches("ad_model", "ad_table"));
        Assert.assertFalse(table.matches("ad_model", "other"));
    }

    @Test
    public void testParseRpdPathsFiltersArea() {
        RpdParser parser = new RpdParser("china1/");
        List<ResourcePermission> permissions = Arrays.asList(
                new ResourcePermission("hive://group@china1/foo.db/bar?option=select", "select"),
                new ResourcePermission("hive://group@us-west/foo.db/bar?option=select", "select")
        );
        List<DatabaseTable> tables = parser.parseRpdPaths(permissions);
        Assert.assertEquals(1, tables.size());
        Assert.assertEquals("foo", tables.get(0).getDatabase());
    }

    @Test
    public void testWildcardDatabase() {
        RpdParser parser = new RpdParser("china1/");
        DatabaseTable table = parser.parseDatabaseTable("hive://group@china1/all.db?option=select");
        Assert.assertNotNull(table);
        Assert.assertTrue(table.matchesDatabase("any_db"));
    }

    @Test
    public void testParseDatabaseLevelCreatePermission() {
        RpdParser parser = new RpdParser("china1/");
        List<ShieldPermission> permissions = parser.parsePermissions(Arrays.asList(
                new ResourcePermission(
                        "hive://dc_product:group@china1/hive/dc_oppo_pdw_dwd.db?option=create", "create")
        ));
        Assert.assertEquals(1, permissions.size());
        ShieldPermission permission = permissions.get(0);
        Assert.assertEquals("dc_oppo_pdw_dwd", permission.getDatabase());
        Assert.assertEquals("*", permission.getTable());
        Assert.assertEquals(ShieldPermission.Authority.CREATE, permission.getAuthority());
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.SELECT));
        Assert.assertTrue(permission.matches("dc_oppo_pdw_dwd", "user_created_table"));
        Assert.assertTrue(permission.satisfiesDatabase(PrivilegeType.CREATE_TABLE));
    }

    @Test
    public void testParseTableLevelCreatePermission() {
        RpdParser parser = new RpdParser("china1/");
        List<ShieldPermission> permissions = parser.parsePermissions(Arrays.asList(
                new ResourcePermission(
                        "hive://dc_product:group@china1/hive/informer_dw.db/imei_bitmapid_mapping_all_d?option=create",
                        "create")
        ));
        Assert.assertEquals(1, permissions.size());
        ShieldPermission permission = permissions.get(0);
        Assert.assertEquals("informer_dw", permission.getDatabase());
        Assert.assertEquals("imei_bitmapid_mapping_all_d", permission.getTable());
        Assert.assertTrue(permission.satisfiesTable(PrivilegeType.SELECT));
        Assert.assertFalse(permission.satisfiesDatabase(PrivilegeType.CREATE_TABLE));
    }

    @Test
    public void testParseAuthorityFromRpdOptionWhenAuthorityFieldMissing() {
        Assert.assertEquals(ShieldPermission.Authority.CREATE,
                RpdParser.parseAuthority(null, "hive://group@china1/tmp.db?option=create"));
        Assert.assertEquals(ShieldPermission.Authority.SELECT,
                RpdParser.parseAuthority(null, "hive://group@china1/tmp.db/t?option=select"));
    }
}
