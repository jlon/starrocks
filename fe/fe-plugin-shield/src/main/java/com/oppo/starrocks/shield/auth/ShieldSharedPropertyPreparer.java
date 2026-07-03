package com.oppo.starrocks.shield.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.starrocks.mysql.MysqlPassword;
import com.starrocks.sql.analyzer.SemanticException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Converts {@link ShieldSharedAuthConfig#PASSWORD} to a persisted MySQL scrambled hash
 * ({@link ShieldSharedAuthConfig#PASSWORD_HASH}) so credentials survive FE restart and replicate to all FEs.
 */
public final class ShieldSharedPropertyPreparer {
    private static final Logger LOG = LogManager.getLogger(ShieldSharedPropertyPreparer.class);

    private ShieldSharedPropertyPreparer() {
    }

    /**
     * If {@link ShieldSharedAuthConfig#PASSWORD} is set, store it as {@link ShieldSharedAuthConfig#PASSWORD_HASH}
     * and remove the plain password so it is not persisted in FE metadata.
     */
    public static void prepare(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        String type = properties.get(SecurityIntegrationTypeKey.TYPE);
        if (type != null && !ShieldSharedAuthConfig.TYPE.equalsIgnoreCase(type)) {
            return;
        }
        String password = properties.remove(ShieldSharedAuthConfig.PASSWORD);
        if (password == null) {
            return;
        }
        if (password.isEmpty()) {
            throw new SemanticException("property " + ShieldSharedAuthConfig.PASSWORD + " cannot be empty");
        }
        String passwordHash = new String(MysqlPassword.makeScrambledPassword(password), StandardCharsets.UTF_8);
        properties.put(ShieldSharedAuthConfig.PASSWORD_HASH, passwordHash);
        LOG.info("Prepared shared Shield auth password hash for persistence");
    }

    /**
     * Local copy of type key to avoid extending fe-core SecurityIntegration from plugin package.
     */
    private static final class SecurityIntegrationTypeKey {
        private static final String TYPE = "type";

        private SecurityIntegrationTypeKey() {
        }
    }
}
