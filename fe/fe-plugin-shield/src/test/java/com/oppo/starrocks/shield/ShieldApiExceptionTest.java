package com.oppo.starrocks.shield;

import java.net.SocketTimeoutException;

import org.junit.Assert;
import org.junit.Test;

public class ShieldApiExceptionTest {
    @Test
    public void timeoutUsesDedicatedUserMessage() {
        ShieldApiException exception = new ShieldApiException(
                "Shield API request failed for /oauthority/api/getUserAppGroup",
                new SocketTimeoutException("Read timed out"));

        Assert.assertTrue(exception.isTimeout());
        Assert.assertEquals("神盾请求超时，请稍后重试或联系管理员", exception.toUserMessage());
    }

    @Test
    public void nonTimeoutUsesFailureMessage() {
        ShieldApiException exception = new ShieldApiException("Shield API error: invalid signature");

        Assert.assertFalse(exception.isTimeout());
        Assert.assertEquals("神盾接口不可用: Shield API error: invalid signature", exception.toUserMessage());
    }
}
