package com.oppo.starrocks.shield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class ShieldPermissionFilterTest {
    @Test
    public void keepsPermissionsForAllowedAppGroups() {
        List<ResourcePermission> permissions = Arrays.asList(
                new ResourcePermission(
                        "hive://advert:group@china1/hive/toutiao.db/t1?option=select", "select"),
                new ResourcePermission(
                        "hive://other:group@china1/hive/foo.db/t2?option=select", "select")
        );
        List<ResourcePermission> filtered = ShieldPermissionFilter.filterByAppGroups(
                permissions, Set.of("advert"));
        Assert.assertEquals(1, filtered.size());
        Assert.assertEquals("advert", RpdParser.extractAppGroup(filtered.get(0).getRpd()));
    }

    @Test
    public void keepsLegacyRpdWithoutAppGroupPrefix() {
        List<ResourcePermission> permissions = Collections.singletonList(
                new ResourcePermission("hive://group@china1/ad_model.db/ad_table?option=select", "select")
        );
        List<ResourcePermission> filtered = ShieldPermissionFilter.filterByAppGroups(
                permissions, Set.of("group-demo-001"));
        Assert.assertEquals(1, filtered.size());
    }

    @Test
    public void returnsEmptyWhenNoAllowedGroups() {
        List<ResourcePermission> permissions = Collections.singletonList(
                new ResourcePermission(
                        "hive://advert:group@china1/hive/toutiao.db/t1?option=select", "select")
        );
        Assert.assertTrue(ShieldPermissionFilter.filterByAppGroups(permissions, Collections.emptySet()).isEmpty());
    }

    @Test
    public void matchesAppGroupIgnoreCase() {
        List<ResourcePermission> permissions = Collections.singletonList(
                new ResourcePermission(
                        "hive://wearable-device-data-group:group@china1/hive/iot_ow.db?option=select",
                        "select")
        );
        List<ResourcePermission> filtered = ShieldPermissionFilter.filterByAppGroups(
                permissions, Set.of("Wearable-device-data-group"));
        Assert.assertEquals(1, filtered.size());
    }
}
