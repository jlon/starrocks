package com.oppo.starrocks.shield;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.starrocks.authorization.PrivilegeType;
import org.junit.Assert;
import org.junit.Test;

public class ShieldPermissionCheckerTest {
    private static final Map<String, String> ENABLED_PROPERTIES = Map.of(
            ShieldConfig.DOMAIN, "http://shield.example",
            ShieldConfig.APP_KEY, "test-key"
    );

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

    @Test
    public void skipsGroupFallbackWhenUserPermissionAllowsAccess() {
        ShieldConfig config = new ShieldConfig(ENABLED_PROPERTIES);
        FakeShieldApiClient apiClient = new FakeShieldApiClient(config,
                List.of(new ShieldPermission("allowed_db", "*", ShieldPermission.Authority.SELECT)),
                List.of(new ShieldPermission("fallback_db", "*", ShieldPermission.Authority.SELECT)));
        ShieldPermissionChecker checker = new ShieldPermissionChecker(config, apiClient);

        Assert.assertTrue(checker.hasTablePermission(
                "80372263_71517", "allowed_db", "t1", PrivilegeType.SELECT));
        Assert.assertEquals(0, apiClient.selectedGroupFallbackCalls);
    }

    @Test
    public void usesOnlySelectedGroupFallbackWhenSessionGroupIsSet() {
        ShieldConfig config = new ShieldConfig(ENABLED_PROPERTIES);
        FakeShieldApiClient apiClient = new FakeShieldApiClient(config,
                List.of(new ShieldPermission("unrelated_db", "*", ShieldPermission.Authority.SELECT)),
                List.of(new ShieldPermission("selected_db", "*", ShieldPermission.Authority.SELECT)));
        ShieldPermissionChecker checker = new ShieldPermissionChecker(config, apiClient);

        Assert.assertTrue(checker.hasTablePermission(
                "80372263_71517", "selected_db", "t1", PrivilegeType.SELECT, "bdp"));
        Assert.assertEquals(1, apiClient.selectedGroupFallbackCalls);
    }

    @Test
    public void deniesAccessWhenSelectedGroupHasNoPermission() {
        ShieldConfig config = new ShieldConfig(ENABLED_PROPERTIES);
        FakeShieldApiClient apiClient =
                new FakeShieldApiClient(config, Collections.emptyList(), Collections.emptyList());
        ShieldPermissionChecker checker = new ShieldPermissionChecker(config, apiClient);

        Assert.assertFalse(checker.hasTablePermission(
                "80372263_71517", "psa_db", "t1", PrivilegeType.SELECT, "bdp"));
        Assert.assertEquals(1, apiClient.selectedGroupFallbackCalls);
    }

    @Test
    public void deniesAccessWithoutSelectedGroupAfterUserPermissionMiss() {
        ShieldConfig config = new ShieldConfig(ENABLED_PROPERTIES);
        FakeShieldApiClient apiClient =
                new FakeShieldApiClient(config, Collections.emptyList(), Collections.emptyList());
        ShieldPermissionChecker checker = new ShieldPermissionChecker(config, apiClient);

        Assert.assertFalse(checker.hasTablePermission(
                "80372263_71517", "denied_db", "t1", PrivilegeType.SELECT));
        Assert.assertEquals(0, apiClient.selectedGroupFallbackCalls);
    }

    private static class FakeShieldApiClient extends ShieldApiClient {
        private final List<ShieldPermission> userPermissions;
        private final List<ShieldPermission> selectedGroupPermissions;
        private int selectedGroupFallbackCalls;

        FakeShieldApiClient(ShieldConfig config, List<ShieldPermission> userPermissions,
                            List<ShieldPermission> selectedGroupPermissions) {
            super(config);
            this.userPermissions = userPermissions;
            this.selectedGroupPermissions = selectedGroupPermissions;
        }

        @Override
        List<ShieldPermission> loadPermissions(String username, String psaId) {
            return userPermissions;
        }

        @Override
        List<ShieldPermission> loadSelectedGroupPermissions(String requestedGroupId) {
            selectedGroupFallbackCalls++;
            return selectedGroupPermissions;
        }
    }
}
