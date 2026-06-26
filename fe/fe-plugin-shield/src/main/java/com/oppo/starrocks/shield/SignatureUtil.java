package com.oppo.starrocks.shield;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

final class SignatureUtil {
    private static final String SIGN_NAME = "signature";

    private SignatureUtil() {
    }

    static String sign(Map<String, Object> args, String appKey) {
        args.remove(SIGN_NAME);
        Object[] keyList = args.keySet().toArray();
        Arrays.sort(keyList);
        StringBuilder buffer = new StringBuilder();
        for (Object keyObject : keyList) {
            String key = keyObject.toString();
            if (buffer.length() != 0) {
                buffer.append("&");
            }
            buffer.append(key).append("=").append(args.get(key));
        }
        String valueStr = (buffer.toString() + ":" + appKey).replace("\r", "").replace("\n", "");
        return md5Hex(valueStr);
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }
}
