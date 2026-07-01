package com.oppo.starrocks.shield.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.starrocks.sql.analyzer.SemanticException;

/**
 * Configuration for {@link ShieldSharedSecurityIntegration}.
 */
public final class ShieldSharedAuthConfig {
    public static final String TYPE = "authentication_shield_shared";
    /** Optional legacy: read password from local file (not replicated across FEs / lost on ephemeral disk). */
    public static final String PASSWORD_FILE = "shield.shared.password_file";
    /** Write-only property: converted to {@link #PASSWORD_HASH} before persistence. */
    public static final String PASSWORD = "shield.shared.password";
    /** Persisted MySQL scrambled password; survives restart and replicates via edit log. */
    public static final String PASSWORD_HASH = "shield.shared.password_hash";
    public static final String USERNAME_PATTERN = "shield.shared.username_pattern";
    public static final String VERIFY_SHIELD_ON_LOGIN = "shield.shared.verify_shield_on_login";
    public static final String DEFAULT_USERNAME_PATTERN = "^\\d+_\\d+$";

    private final String passwordFile;
    private final byte[] passwordHashBytes;
    private final Pattern usernamePattern;
    private final boolean verifyShieldOnLogin;
    private final ShieldSharedShieldApiConfig shieldApiConfig;

    public ShieldSharedAuthConfig(Map<String, String> properties) {
        this.passwordFile = trimToNull(properties.get(PASSWORD_FILE));
        String passwordHash = trimToNull(properties.get(PASSWORD_HASH));
        if (passwordHash != null) {
            this.passwordHashBytes = passwordHash.getBytes(StandardCharsets.UTF_8);
        } else {
            this.passwordHashBytes = null;
        }
        if (passwordHashBytes == null && passwordFile == null
                && trimToNull(properties.get(PASSWORD)) == null) {
            throw new SemanticException("one of " + PASSWORD_HASH + ", " + PASSWORD_FILE
                    + ", or " + PASSWORD + " is required");
        }

        String patternText = properties.getOrDefault(USERNAME_PATTERN, DEFAULT_USERNAME_PATTERN);
        if (patternText == null || patternText.isBlank()) {
            this.usernamePattern = null;
        } else {
            try {
                this.usernamePattern = Pattern.compile(patternText);
            } catch (PatternSyntaxException e) {
                throw new SemanticException("invalid " + USERNAME_PATTERN + ": " + e.getMessage());
            }
        }
        this.verifyShieldOnLogin = Boolean.parseBoolean(
                properties.getOrDefault(VERIFY_SHIELD_ON_LOGIN, "false"));
        this.shieldApiConfig = verifyShieldOnLogin ? new ShieldSharedShieldApiConfig(properties) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getPasswordFile() {
        return passwordFile;
    }

    public boolean hasPasswordHash() {
        return passwordHashBytes != null;
    }

    public byte[] getPasswordHashBytes() {
        return passwordHashBytes;
    }

    public boolean matchesUsername(String username) {
        return usernamePattern == null || usernamePattern.matcher(username).matches();
    }

    public boolean isVerifyShieldOnLogin() {
        return verifyShieldOnLogin;
    }

    public ShieldSharedShieldApiConfig getShieldApiConfig() {
        return shieldApiConfig;
    }
}
