package com.oppo.starrocks.udfs.dcfunctions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionSupport {
    private static final String DEFAULT_VERSION = "其他";
    private static final Pattern APP_PATTERN = Pattern.compile("^\\w*(\\d+)\\.(\\d+)\\.?((\\d+)(\\.?\\w*)|(\\w*))");
    private static final Pattern OS_PATTERN = Pattern.compile("^[vV](\\d+)\\.(\\d+)\\.?((\\d+)(\\.?\\w*)|(\\w*))");

    private VersionSupport() {
    }

    static String normalizeApp(String value) {
        return normalize(value, APP_PATTERN, false);
    }

    static String normalizeOs(String value) {
        return normalize(value, OS_PATTERN, true);
    }

    private static String normalize(String value, Pattern pattern, boolean prefixV) {
        if (value == null || value.isEmpty()) {
            return DEFAULT_VERSION;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return DEFAULT_VERSION;
        }
        StringBuilder builder = new StringBuilder();
        if (prefixV) {
            builder.append("V");
        }
        builder.append(matcher.group(1));
        builder.append(".");
        builder.append(matcher.group(2));
        String group4 = matcher.group(4);
        if (group4 != null) {
            builder.append(".");
            builder.append(group4);
        }
        return builder.toString();
    }
}
