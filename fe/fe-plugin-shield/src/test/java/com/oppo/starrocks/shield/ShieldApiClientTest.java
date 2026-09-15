package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ShieldApiClientTest {
    @Test
    public void loadsSelectedGroupPermissionsWithConfiguredOperator() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key",
                ShieldConfig.OPERATOR, "api-operator"
        ));
        ShieldApiClient client = new ShieldApiClient(config) {
            @Override
            public List<ResourcePermission> fetchGroupPermissions(String operator, String groupId) {
                Assert.assertEquals("api-operator", operator);
                Assert.assertEquals("bdp", groupId);
                return List.of(new ResourcePermission(
                        "hive://bdp:group@china1/hive/group_db.db?option=select", "select"));
            }
        };

        List<ShieldPermission> permissions = client.loadSelectedGroupPermissions("bdp");
        Assert.assertEquals(1, permissions.size());
        Assert.assertEquals("group_db", permissions.get(0).getDatabase());
    }

    @Test
    public void returnsEmptyWhenSelectedGroupHasNoPermissions() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key"
        ));
        ShieldApiClient client = new ShieldApiClient(config) {
            @Override
            public List<ResourcePermission> fetchGroupPermissions(String operator, String groupId) {
                return List.of();
            }
        };

        Assert.assertTrue(client.loadSelectedGroupPermissions("hive").isEmpty());
    }
}
