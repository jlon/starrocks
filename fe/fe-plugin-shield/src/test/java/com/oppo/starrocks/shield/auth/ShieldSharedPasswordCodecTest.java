package com.oppo.starrocks.shield.auth;

import org.junit.Assert;
import org.junit.Test;

import com.starrocks.mysql.MysqlPassword;

public class ShieldSharedPasswordCodecTest {
    @Test
    public void testPlainPasswordWithSaltHandshake() {
        byte[] salt = "randomseedrandomseed".getBytes();
        byte[] authResponse = MysqlPassword.scramble(salt, "YourSharedPassword");
        Assert.assertNotNull(authResponse);
        Assert.assertTrue(ShieldSharedPasswordCodec.verify(
                "YourSharedPassword".getBytes(), authResponse, salt));
        Assert.assertFalse(ShieldSharedPasswordCodec.verify(
                "WrongPassword".getBytes(), authResponse, salt));
    }

    @Test
    public void testMysqlScrambledPasswordWithSaltHandshake() {
        byte[] stored = MysqlPassword.makeScrambledPassword("YourSharedPassword");
        byte[] salt = "randomseedrandomseed".getBytes();
        byte[] authResponse = MysqlPassword.scramble(salt, "YourSharedPassword");
        Assert.assertTrue(ShieldSharedPasswordCodec.isMysqlScrambledPassword(stored));
        Assert.assertTrue(ShieldSharedPasswordCodec.verify(stored, authResponse, salt));
    }
}
