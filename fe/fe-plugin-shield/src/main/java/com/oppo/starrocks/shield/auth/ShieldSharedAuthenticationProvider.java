package com.oppo.starrocks.shield.auth;

import java.io.IOException;
import java.util.List;

import com.oppo.starrocks.shield.ShieldApiClient;
import com.oppo.starrocks.shield.ShieldConfig;
import com.oppo.starrocks.shield.ShieldUserIdentity;
import com.oppo.starrocks.shield.UserGroupInfo;
import com.starrocks.authentication.AuthenticationException;
import com.starrocks.authentication.AuthenticationProvider;
import com.starrocks.common.ErrorCode;
import com.starrocks.mysql.MysqlPassword;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared-password authentication for ephemeral Shield users.
 */
public final class ShieldSharedAuthenticationProvider implements AuthenticationProvider {
    private static final Logger LOG = LogManager.getLogger(ShieldSharedAuthenticationProvider.class);

    private final ShieldSharedAuthConfig config;
    private final ShieldSharedPasswordStore passwordStore;

    public ShieldSharedAuthenticationProvider(ShieldSharedAuthConfig config) {
        this.config = config;
        this.passwordStore = config.getPasswordFile() != null
                ? new ShieldSharedPasswordStore(config.getPasswordFile())
                : null;
    }

    @Override
    public void authenticate(ConnectContext context, UserIdentity userIdentity, byte[] authResponse)
            throws AuthenticationException {
        String loginUser = userIdentity.getUser();
        if (!config.matchesUsername(loginUser)) {
            throw new AuthenticationException("login name does not match configured username pattern: " + loginUser);
        }

        byte[] passwordBytes;
        try {
            passwordBytes = loadStoredPasswordBytes();
        } catch (IOException e) {
            LOG.error("Failed to load shared Shield auth password", e);
            throw new AuthenticationException("cannot load shared Shield auth password: " + e.getMessage());
        }

        verifyMysqlPassword(context, userIdentity, authResponse, passwordBytes);

        if (config.isVerifyShieldOnLogin()) {
            verifyShieldAccess(loginUser);
        }
    }

    private byte[] loadStoredPasswordBytes() throws IOException {
        if (config.hasPasswordHash()) {
            return config.getPasswordHashBytes();
        }
        return passwordStore.loadPasswordBytes();
    }

    private void verifyMysqlPassword(ConnectContext context, UserIdentity userIdentity,
                                   byte[] authResponse, byte[] passwordBytes) throws AuthenticationException {
        String usePassword = authResponse.length == 0 ? "NO" : "YES";
        if (!ShieldSharedPasswordCodec.verify(passwordBytes, authResponse, context.getAuthDataSalt())) {
            throw new AuthenticationException(ErrorCode.ERR_AUTHENTICATION_FAIL, userIdentity.getUser(), usePassword);
        }
    }

    private void verifyShieldAccess(String loginUser) throws AuthenticationException {
        ShieldUserIdentity identity = ShieldUserIdentity.parse(loginUser);
        if (identity == null) {
            throw new AuthenticationException("cannot parse Shield identity from login user: " + loginUser);
        }
        ShieldApiClient apiClient = new ShieldApiClient(new ShieldConfig(config.getShieldApiConfig().toShieldProperties()));
        List<UserGroupInfo> groups = apiClient.fetchUserGroups(identity.getUsername()).stream()
                .filter(group -> identity.getPsaId().equals(group.getPsaId()))
                .toList();
        if (groups.isEmpty()) {
            throw new AuthenticationException("Shield has no app group for user: " + loginUser);
        }
    }

    @Override
    public byte[] authSwitchRequestPacket(ConnectContext context, String user, String host)
            throws AuthenticationException {
        return context.getAuthDataSalt();
    }
}
