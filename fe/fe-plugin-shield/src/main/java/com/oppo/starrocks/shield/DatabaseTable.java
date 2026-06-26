package com.oppo.starrocks.shield;

public class DatabaseTable {
    private final String database;
    private final String table;

    public DatabaseTable(String database, String table) {
        this.database = database;
        this.table = table;
    }

    public String getDatabase() {
        return database;
    }

    public String getTable() {
        return table;
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

    @Override
    public String toString() {
        return database + "." + table;
    }
}
