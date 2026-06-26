package com.oppo.starrocks.shield;

import org.junit.Assert;
import org.junit.Test;

public class ShieldUserIdentityTest {
    @Test
    public void testParseStarRocksUser() {
        ShieldUserIdentity identity = ShieldUserIdentity.parse("80372263_37422");
        Assert.assertNotNull(identity);
        Assert.assertEquals("80372263", identity.getUsername());
        Assert.assertEquals("37422", identity.getPsaId());
        Assert.assertEquals("80372263:37422", identity.toCacheKey());
    }

    @Test
    public void testInvalidUser() {
        Assert.assertNull(ShieldUserIdentity.parse("super_readonly"));
        Assert.assertNull(ShieldUserIdentity.parse(""));
    }
}
