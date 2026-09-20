package com.oppo.starrocks.udfs.dcfunctions;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

final class CryptoSupport {
    static final String DEFAULT_AES_KEY = "tokeniujhonline";
    private static final byte[] DES_KEY = new byte[]{0x76, 0x0b, (byte) 0x92, (byte) 0xc2, 0x1f, (byte) 0x94, 0x57, 0x62};

    private CryptoSupport() {
    }

    static String sha2(String value, String algorithm) {
        try {
            String encName = algorithm == null || algorithm.isEmpty() ? "SHA-256" : algorithm;
            MessageDigest digest = MessageDigest.getInstance(encName);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return "0";
        }
    }

    static String getSsoId(String ssoid, String key) {
        if (ssoid == null) {
            return "0";
        }
        try {
            String token = aesDecrypt(ssoid, key == null ? DEFAULT_AES_KEY : key);
            return String.valueOf(Long.valueOf(getSsoIdFromToken(token)));
        } catch (Exception e) {
            return "0";
        }
    }

    private static String aesDecrypt(String encrypted, String decryptKey) throws Exception {
        if (isBlank(encrypted)) {
            return null;
        }
        return aesDecryptByBytes(Base64.getDecoder().decode(encrypted), decryptKey);
    }

    private static String aesDecryptByBytes(byte[] encryptedBytes, String decryptKey) throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(decryptKey.getBytes(StandardCharsets.UTF_8));
        keyGenerator.init(128, random);
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyGenerator.generateKey().getEncoded(), "AES"));
        return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
    }

    private static String getSsoIdFromToken(String value) {
        if (value == null) {
            return "0";
        }
        if (value.length() > 12 && value.startsWith("TOKEN")) {
            if (value.contains("%s")) {
                try {
                    value = URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                    return "0";
                }
            }
            try {
                byte[] encryptedBytes = Base64.getDecoder().decode(value.replaceAll("_timeout", "").substring(6));
                byte[] decryptedBytes = desDecrypt(encryptedBytes);
                if (decryptedBytes == null) {
                    return "0";
                }
                String[] fields = new String(decryptedBytes, StandardCharsets.UTF_8).split("&");
                if (fields.length != 3) {
                    return "0";
                }
                return fields[0];
            } catch (Exception e) {
                return "0";
            }
        }
        return "0";
    }

    private static byte[] desDecrypt(byte[] encryptedBytes) {
        try {
            SecretKey secretKey = new SecretKeySpec(DES_KEY, "DES");
            Cipher cipher = Cipher.getInstance("DES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return cipher.doFinal(encryptedBytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String value = Integer.toHexString(b & 0xff);
            if (value.length() == 1) {
                builder.append('0');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
