package com.oppo.starrocks.shield.auth;

import java.util.HashMap;
import java.util.Map;

import com.starrocks.authentication.AuthenticationException;
import com.starrocks.authentication.AuthenticationProvider;
import com.starrocks.authentication.SecurityIntegration;
import com.starrocks.sql.analyzer.SemanticException;

/**
 * Security integration: shared password file + ephemeral Shield users ({@code 工号_psaId}).
 *
 * <p>Loaded from fe-plugin-shield.jar via {@code PluginSecurityIntegrationSupport} in fe-core.
 */
public final class ShieldSharedSecurityIntegration extends SecurityIntegration {
    private final ShieldSharedAuthConfig config;

    public ShieldSharedSecurityIntegration(String name, Map<String, String> propertyMap) {
        super(name, propertyMap);
        this.config = new ShieldSharedAuthConfig(propertyMap);
    }

    @Override
    public AuthenticationProvider getAuthenticationProvider() throws AuthenticationException {
        return new ShieldSharedAuthenticationProvider(config);
    }

    @Override
    public Map<String, String> getPropertyMapWithMasking() {
        Map<String, String> masked = new HashMap<>(propertyMap);
        masked.remove(ShieldSharedAuthConfig.PASSWORD);
        if (masked.containsKey(ShieldSharedAuthConfig.PASSWORD_HASH)) {
            masked.put(ShieldSharedAuthConfig.PASSWORD_HASH, "******");
        }
        return masked;
    }

    @Override
    public void checkProperty() throws SemanticException {
        super.checkProperty();
        if (!ShieldSharedAuthConfig.TYPE.equalsIgnoreCase(getType())) {
            throw new SemanticException("invalid security integration type: " + getType());
        }
        // validate required properties early
        new ShieldSharedAuthConfig(propertyMap);
    }
}
