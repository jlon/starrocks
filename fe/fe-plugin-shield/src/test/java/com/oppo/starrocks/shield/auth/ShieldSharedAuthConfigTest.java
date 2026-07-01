package com.oppo.starrocks.shield.auth;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ShieldSharedAuthConfigTest {
    @Test
    public void testDefaultUsernamePattern() {
        Map<String, String> props = baseProps();
        ShieldSharedAuthConfig config = new ShieldSharedAuthConfig(props);
        Assert.assertTrue(config.matchesUsername("80372263_37422"));
        Assert.assertFalse(config.matchesUsername("root"));
    }

    @Test
    public void testBlankPatternAllowsAnyUsername() {
        Map<String, String> props = baseProps();
        props.put(ShieldSharedAuthConfig.USERNAME_PATTERN, "");
        ShieldSharedAuthConfig config = new ShieldSharedAuthConfig(props);
        Assert.assertTrue(config.matchesUsername("any_user"));
    }

    @Test
    public void testPlainPasswordPropertyAcceptedForValidation() {
        Map<String, String> props = new HashMap<>();
        props.put(ShieldSharedAuthConfig.TYPE, ShieldSharedAuthConfig.TYPE);
        props.put(ShieldSharedAuthConfig.PASSWORD, "YourSharedPassword");
        new ShieldSharedAuthConfig(props);
    }

    private Map<String, String> baseProps() {
        Map<String, String> props = new HashMap<>();
        props.put(ShieldSharedAuthConfig.TYPE, ShieldSharedAuthConfig.TYPE);
        props.put(ShieldSharedAuthConfig.PASSWORD_FILE, "/tmp/shield-shared.password");
        return props;
    }
}
