package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
}
