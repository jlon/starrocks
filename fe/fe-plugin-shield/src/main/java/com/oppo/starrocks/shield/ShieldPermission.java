package com.oppo.starrocks.shield;

import com.starrocks.authorization.PrivilegeType;

/**
 * Parsed Shield RPD permission with authority level.
 *
 * <p>Database-level {@code create} ({@code *.db?option=create}) grants full access to all tables
 * in the database. Table-level {@code create} grants full access to that table only.
 * Table-level {@code select} grants read-only access.
 */
public class ShieldPermission {
    public enum Authority {
        SELECT,
        CREATE,
        ADMIN
    }

    private final String database;
    private final String table;
    private final Authority authority;

    public ShieldPermission(String database, String table, Authority authority) {
        this.database = database;
        this.table = table;
        this.authority = authority;
    }

    public String getDatabase() {
        return database;
    }

    public String getTable() {
        return table;
    }

    public Authority getAuthority() {
        return authority;
    }

    public boolean isDatabaseLevel() {
        return "*".equals(table);
    }

    public boolean matches(String db, String tableName) {
        boolean dbMatched = "*".equals(database) || database.equalsIgnoreCase(db);
        if (!dbMatched) {
            return false;
        }
        return "*".equals(table) || table.equalsIgnoreCase(tableName);
    }

    public boolean matchesDatabase(String db) {
        return "*".equals(database) || database.equalsIgnoreCase(db);
    }

    /**
     * Whether this permission satisfies a table-level privilege check.
     */
    public boolean satisfiesTable(PrivilegeType required) {
        if (required == PrivilegeType.ANY) {
            return true;
        }
        if (authority == Authority.CREATE || authority == Authority.ADMIN) {
            return true;
        }
        return required == PrivilegeType.SELECT;
    }

    /**
     * Whether this permission satisfies a database-level privilege check.
     * CREATE TABLE requires database-level create; read checks accept any grant in the database.
     */
    public boolean satisfiesDatabase(PrivilegeType required) {
        if (required == PrivilegeType.ANY) {
            return true;
        }
        if (isDatabaseLevel() && (authority == Authority.CREATE || authority == Authority.ADMIN)) {
            return true;
        }
        if (required == PrivilegeType.CREATE_TABLE
                || required == PrivilegeType.CREATE_VIEW
                || required == PrivilegeType.CREATE_DATABASE) {
            return isDatabaseLevel() && (authority == Authority.CREATE || authority == Authority.ADMIN);
        }
        if (authority == Authority.CREATE || authority == Authority.ADMIN) {
            return isDatabaseLevel();
        }
        return required == PrivilegeType.SELECT;
    }

    @Override
    public String toString() {
        return database + "." + table + "(" + authority + ")";
    }
}
