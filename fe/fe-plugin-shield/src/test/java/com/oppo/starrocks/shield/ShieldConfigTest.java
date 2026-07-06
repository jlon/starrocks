package com.oppo.starrocks.shield;

import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class ShieldConfigTest {
    @Test
    public void usesDefaultTimeoutAndRetrySettings() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key"
        ));

        Assert.assertEquals(5000, config.getConnectTimeoutMs());
        Assert.assertEquals(10000, config.getReadTimeoutMs());
        Assert.assertEquals(3, config.getRetryCount());
        Assert.assertEquals(200, config.getRetryDelayMs());
    }

    @Test
    public void acceptsCustomTimeoutAndRetrySettings() {
        ShieldConfig config = new ShieldConfig(Map.of(
                ShieldConfig.DOMAIN, "http://shield.example",
                ShieldConfig.APP_KEY, "test-key",
                ShieldConfig.CONNECT_TIMEOUT_MS, "3000",
                ShieldConfig.READ_TIMEOUT_MS, "15000",
                ShieldConfig.RETRY_COUNT, "5",
                ShieldConfig.RETRY_DELAY_MS, "500"
        ));

        Assert.assertEquals(3000, config.getConnectTimeoutMs());
        Assert.assertEquals(15000, config.getReadTimeoutMs());
        Assert.assertEquals(5, config.getRetryCount());
        Assert.assertEquals(500, config.getRetryDelayMs());
    }
}
