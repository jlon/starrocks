package com.oppo.starrocks.shield.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.starrocks.mysql.MysqlPassword;
import org.apache.commons.lang3.StringUtils;

/**
 * Supports both plain-text and MySQL scrambled password formats in the shared password file.
 */
final class ShieldSharedPasswordCodec {
    private static final int MYSQL_SCRAMBLED_PASSWORD_LENGTH = 41;

    private ShieldSharedPasswordCodec() {
    }

    static boolean isMysqlScrambledPassword(byte[] passwordBytes) {
        return passwordBytes != null
                && passwordBytes.length == MYSQL_SCRAMBLED_PASSWORD_LENGTH
                && passwordBytes[0] == MysqlPassword.PVERSION41_CHAR;
    }

    static boolean verify(byte[] storedPasswordBytes, byte[] authResponse, byte[] randomString) {
        if (isMysqlScrambledPassword(storedPasswordBytes)) {
            return verifyScrambledPassword(storedPasswordBytes, authResponse, randomString);
        }
        return verifyPlainPassword(storedPasswordBytes, authResponse, randomString);
    }

    private static boolean verifyScrambledPassword(byte[] storedPasswordBytes, byte[] authResponse,
                                                   byte[] randomString) {
        if (randomString != null) {
            if (authResponse.length == 0) {
                return storedPasswordBytes.length == 0;
            }
            byte[] hashStage2 = MysqlPassword.getSaltFromPassword(storedPasswordBytes);
            if (hashStage2.length != authResponse.length) {
                return false;
            }
            return MysqlPassword.checkScramble(authResponse, randomString, hashStage2);
        }

        byte[] scrambledRemotePass = MysqlPassword.makeScrambledPassword(stripNullTerminated(authResponse));
        return MysqlPassword.checkScrambledPlainPass(storedPasswordBytes, scrambledRemotePass);
    }

    private static boolean verifyPlainPassword(byte[] storedPasswordBytes, byte[] authResponse, byte[] randomString) {
        String plainPassword = new String(storedPasswordBytes, StandardCharsets.UTF_8);
        if (randomString != null) {
            if (authResponse.length == 0) {
                return plainPassword.isEmpty();
            }
            byte[] expected = MysqlPassword.scramble(randomString, plainPassword);
            return expected != null && Arrays.equals(authResponse, expected);
        }

        byte[] scrambledRemotePass = MysqlPassword.makeScrambledPassword(stripNullTerminated(authResponse));
        return MysqlPassword.checkPlainPass(scrambledRemotePass, plainPassword);
    }

    private static String stripNullTerminated(byte[] authResponse) {
        return StringUtils.stripEnd(new String(authResponse, StandardCharsets.UTF_8), "\0");
    }
}
