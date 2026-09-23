package com.oppo.starrocks.shield;

import java.io.IOException;
import java.util.Map;

import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.ExternalAccessController;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.TableName;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.qe.ConnectContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real-time Shield authorization for Hive external catalogs.
 *
 * <p>Loaded via catalog property:
 * {@code access.controller.class = com.oppo.starrocks.shield.ShieldHiveAccessController}
 *
 * <p>Set {@code shield.auth.enabled = false} to bypass all database/table permission checks without
 * falling back to native GRANT checks. Catalog {@code USAGE} privilege is still enforced by StarRocks.
 *
 * <p>Shield RPD {@code authority=create} at database level grants full access to all tables in the
 * database; table-level {@code create} grants full access to that table; {@code select} is read-only.
 */
public class ShieldHiveAccessController extends ExternalAccessController implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ShieldHiveAccessController.class);

    private final ShieldPermissionChecker permissionChecker;

    public ShieldHiveAccessController(Map<String, String> properties) {
        this.permissionChecker = new ShieldPermissionChecker(properties);
        LOG.info("ShieldHiveAccessController initialized, authEnabled={}, domain={}, cacheTtlSeconds={}, "
                        + "slowThresholdMs={}, connectTimeoutMs={}, readTimeoutMs={}, retryCount={}, retryDelayMs={}",
                properties.getOrDefault(ShieldConfig.AUTH_ENABLED, "true"),
                properties.get(ShieldConfig.DOMAIN),
                properties.getOrDefault(ShieldConfig.CACHE_TTL_SECONDS, "60"),
                properties.getOrDefault(ShieldConfig.SLOW_THRESHOLD_MS, "500"),
                properties.getOrDefault(ShieldConfig.CONNECT_TIMEOUT_MS, "5000"),
                properties.getOrDefault(ShieldConfig.READ_TIMEOUT_MS, "10000"),
                properties.getOrDefault(ShieldConfig.RETRY_COUNT, "3"),
                properties.getOrDefault(ShieldConfig.RETRY_DELAY_MS, "200"));
    }

    @Override
    public void checkDbAction(ConnectContext context, String catalogName, String db,
                              PrivilegeType privilegeType) throws AccessDeniedException {
        checkDatabase(context, db, privilegeType);
    }

    @Override
    public void checkAnyActionOnDb(ConnectContext context, String catalogName, String db)
            throws AccessDeniedException {
        checkDatabase(context, db, PrivilegeType.ANY);
    }

    @Override
    public void checkTableAction(ConnectContext context, TableName tableName, PrivilegeType privilegeType)
            throws AccessDeniedException {
        checkTable(context, tableName.getDb(), tableName.getTbl(), privilegeType);
    }

    @Override
    public void checkAnyActionOnTable(ConnectContext context, TableName tableName)
            throws AccessDeniedException {
        checkTable(context, tableName.getDb(), tableName.getTbl(), PrivilegeType.ANY);
    }

    @Override
    public void checkColumnAction(ConnectContext context, TableName tableName,
                                  String column, PrivilegeType privilegeType) throws AccessDeniedException {
        checkTable(context, tableName.getDb(), tableName.getTbl(), privilegeType);
    }

    private void checkDatabase(ConnectContext context, String database, PrivilegeType privilegeType)
            throws AccessDeniedException {
        String user = context.getQualifiedUser();
        try {
            if (!permissionChecker.hasDatabasePermission(
                    user, database, privilegeType, context.getSessionVariable().getShieldAppGroup())) {
                LOG.info("Shield denied database access. user={}, database={}, privilege={}",
                        user, database, privilegeType.name());
                throw new AccessDeniedException();
            }
        } catch (ShieldApiException e) {
            LOG.error("Shield API unavailable when checking database access. user={}, database={}", user, database, e);
            throw ErrorReportException.report(ErrorCode.ERR_SHIELD_API_UNAVAILABLE, e.toUserMessage());
        }
    }

    private void checkTable(ConnectContext context, String database, String table, PrivilegeType privilegeType)
            throws AccessDeniedException {
        String user = context.getQualifiedUser();
        try {
            if (!permissionChecker.hasTablePermission(
                    user, database, table, privilegeType, context.getSessionVariable().getShieldAppGroup())) {
                LOG.info("Shield denied table access. user={}, table={}.{}, privilege={}",
                        user, database, table, privilegeType.name());
                throw new AccessDeniedException();
            }
        } catch (ShieldApiException e) {
            LOG.error("Shield API unavailable when checking table access. user={}, table={}.{}",
                    user, database, table, e);
            throw ErrorReportException.report(ErrorCode.ERR_SHIELD_API_UNAVAILABLE, e.toUserMessage());
        }
    }

    @Override
    public void close() throws IOException {
        permissionChecker.invalidateAll();
    }
}
