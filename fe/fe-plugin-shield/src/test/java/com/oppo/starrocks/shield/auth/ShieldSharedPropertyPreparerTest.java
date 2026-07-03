package com.oppo.starrocks.shield.auth;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ShieldSharedPropertyPreparerTest {
    @Test
    public void testConvertPasswordToPersistedHash() {
        Map<String, String> props = new HashMap<>();
        props.put("type", ShieldSharedAuthConfig.TYPE);
        props.put(ShieldSharedAuthConfig.PASSWORD, "YourSharedPassword");

        ShieldSharedPropertyPreparer.prepare(props);

        Assert.assertFalse(props.containsKey(ShieldSharedAuthConfig.PASSWORD));
        String hash = props.get(ShieldSharedAuthConfig.PASSWORD_HASH);
        Assert.assertNotNull(hash);
        Assert.assertTrue(hash.startsWith("*"));
        Assert.assertEquals(41, hash.length());
    }
}
