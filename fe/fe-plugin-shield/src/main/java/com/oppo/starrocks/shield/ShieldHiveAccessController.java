package com.oppo.starrocks.shield;

import java.io.IOException;
import java.util.Map;

import com.starrocks.analysis.TableName;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.ExternalAccessController;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.qe.ConnectContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real-time Shield authorization for Hive external catalogs.
 *
 * <p>Loaded via catalog property:
 * {@code access.controller.class = com.oppo.starrocks.shield.ShieldHiveAccessController}
 */
public class ShieldHiveAccessController extends ExternalAccessController implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ShieldHiveAccessController.class);

    private final ShieldPermissionChecker permissionChecker;

    public ShieldHiveAccessController(Map<String, String> properties) {
        this.permissionChecker = new ShieldPermissionChecker(properties);
        LOG.info("ShieldHiveAccessController initialized, domain={}, cacheTtlSeconds={}, slowThresholdMs={}",
                properties.get(ShieldConfig.DOMAIN),
                properties.getOrDefault(ShieldConfig.CACHE_TTL_SECONDS, "60"),
                properties.getOrDefault(ShieldConfig.SLOW_THRESHOLD_MS, "500"));
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
        // Shield sync only grants table-level SELECT; column access follows table access.
        checkTable(context, tableName.getDb(), tableName.getTbl(), privilegeType);
    }

    private void checkDatabase(ConnectContext context, String database, PrivilegeType privilegeType)
            throws AccessDeniedException {
        if (!isReadablePrivilege(privilegeType)) {
            throw new AccessDeniedException();
        }
        String user = context.getQualifiedUser();
        try {
            if (!permissionChecker.hasDatabasePermission(user, database)) {
                LOG.info("Shield denied database access. user={}, database={}", user, database);
                throw new AccessDeniedException();
            }
        } catch (ShieldApiException e) {
            LOG.error("Shield API unavailable when checking database access. user={}, database={}", user, database, e);
            throw new AccessDeniedException();
        }
    }

    private void checkTable(ConnectContext context, String database, String table, PrivilegeType privilegeType)
            throws AccessDeniedException {
        if (!isReadablePrivilege(privilegeType)) {
            throw new AccessDeniedException();
        }
        String user = context.getQualifiedUser();
        try {
            if (!permissionChecker.hasTablePermission(user, database, table)) {
                LOG.info("Shield denied table access. user={}, table={}.{}", user, database, table);
                throw new AccessDeniedException();
            }
        } catch (ShieldApiException e) {
            LOG.error("Shield API unavailable when checking table access. user={}, table={}.{}",
                    user, database, table, e);
            throw new AccessDeniedException();
        }
    }

    private boolean isReadablePrivilege(PrivilegeType privilegeType) {
        return privilegeType == PrivilegeType.SELECT
                || privilegeType == PrivilegeType.ANY;
    }

    @Override
    public void close() throws IOException {
        permissionChecker.invalidateAll();
    }
}
