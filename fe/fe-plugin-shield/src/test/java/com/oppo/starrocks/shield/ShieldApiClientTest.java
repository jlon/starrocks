package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ShieldApiClientTest {
    @Test
    public void loadsOnlyASelectedGroupThatBelongsToUserAndPsa() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key"
        ));
        ShieldApiClient client = new ShieldApiClient(config) {
            @Override
            public List<UserGroupInfo> fetchUserGroups(String username) {
                return List.of(
                        new UserGroupInfo(username, "71517", "bdp"),
                        new UserGroupInfo(username, "99999", "other"));
            }

            @Override
            public List<ResourcePermission> fetchGroupPermissions(String operator, String groupId) {
                Assert.assertEquals("bdp", groupId);
                return List.of(new ResourcePermission(
                        "hive://bdp:group@china1/hive/group_db.db?option=select", "select"));
            }
        };

        List<ShieldPermission> permissions =
                client.loadSelectedGroupPermissions("80372263", "71517", "BDP");
        Assert.assertEquals(1, permissions.size());
        Assert.assertEquals("group_db", permissions.get(0).getDatabase());
    }

    @Test
    public void rejectsSelectedGroupThatDoesNotBelongToUserAndPsa() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key"
        ));
        ShieldApiClient client = new ShieldApiClient(config) {
            @Override
            public List<UserGroupInfo> fetchUserGroups(String username) {
                return List.of(new UserGroupInfo(username, "71517", "bdp"));
            }

            @Override
            public List<ResourcePermission> fetchGroupPermissions(String operator, String groupId) {
                Assert.fail("group permissions must not be fetched for an unauthorized group");
                return List.of();
            }
        };

        Assert.assertTrue(client.loadSelectedGroupPermissions(
                "80372263", "71517", "hive").isEmpty());
    }
}
