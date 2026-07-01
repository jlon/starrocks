package com.oppo.starrocks.shield.auth;

import java.util.Map;

import com.oppo.starrocks.shield.ShieldConfig;
import com.starrocks.sql.analyzer.SemanticException;

/**
 * Minimal Shield API settings for optional login-time verification.
 */
final class ShieldSharedShieldApiConfig {
    private final String domain;
    private final String appKey;
    private final String operator;
    private final String sysId;
    private final String areaCode;

    ShieldSharedShieldApiConfig(Map<String, String> properties) {
        this.domain = getRequired(properties, ShieldConfig.DOMAIN);
        this.appKey = getRequired(properties, ShieldConfig.APP_KEY);
        this.operator = properties.getOrDefault(ShieldConfig.OPERATOR, "");
        this.sysId = properties.getOrDefault(ShieldConfig.SYS_ID, "starrocks");
        this.areaCode = properties.getOrDefault(ShieldConfig.AREA_CODE, "china1");
    }

    private static String getRequired(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new SemanticException("missing required property for shield login verification: " + key);
        }
        return value.trim();
    }

    Map<String, String> toShieldProperties() {
        return Map.of(
                ShieldConfig.DOMAIN, domain,
                ShieldConfig.APP_KEY, appKey,
                ShieldConfig.OPERATOR, operator,
                ShieldConfig.SYS_ID, sysId,
                ShieldConfig.AREA_CODE, areaCode
        );
    }
}
