package com.oppo.starrocks.shield;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RpdParser {
    private static final Logger LOG = LogManager.getLogger(RpdParser.class);
    private static final Pattern RPD_PATTERN = Pattern.compile("/([^/]+)\\.db(?:/([^/?]+))?\\??");
    private static final Pattern APP_GROUP_PATTERN = Pattern.compile("hive://([^:@]+):group@");

    private final String areaFilter;

    public RpdParser(String areaFilter) {
        this.areaFilter = areaFilter;
    }

    /**
     * Extracts app group from {@code hive://{group}:group@{area}/...}.
     * Returns null for legacy {@code hive://group@{area}/...} RPDs.
     */
    public static String extractAppGroup(String rpd) {
        if (rpd == null) {
            return null;
        }
        Matcher matcher = APP_GROUP_PATTERN.matcher(rpd);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public List<ShieldPermission> parsePermissions(List<ResourcePermission> permissions) {
        return permissions.stream()
                .filter(permission -> permission.getRpd() != null)
                .filter(permission -> permission.getRpd().contains(areaFilter))
                .map(this::parsePermission)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * @deprecated use {@link #parsePermissions(List)} which preserves authority.
     */
    @Deprecated
    public List<DatabaseTable> parseRpdPaths(List<ResourcePermission> permissions) {
        return parsePermissions(permissions).stream()
                .map(permission -> new DatabaseTable(permission.getDatabase(), permission.getTable()))
                .collect(Collectors.toList());
    }

    private ShieldPermission parsePermission(ResourcePermission permission) {
        DatabaseTable dbTable = parseDatabaseTable(permission.getRpd());
        if (dbTable == null) {
            return null;
        }
        DatabaseTable normalized = normalizeDatabaseTable(dbTable);
        ShieldPermission.Authority authority = parseAuthority(permission.getAuthority(), permission.getRpd());
        return new ShieldPermission(normalized.getDatabase(), normalized.getTable(), authority);
    }

    static ShieldPermission.Authority parseAuthority(String authority, String rpd) {
        if (authority != null) {
            if ("create".equalsIgnoreCase(authority)) {
                return ShieldPermission.Authority.CREATE;
            }
            if ("admin".equalsIgnoreCase(authority)) {
                return ShieldPermission.Authority.ADMIN;
            }
        }
        if (rpd != null && rpd.contains("option=create")) {
            return ShieldPermission.Authority.CREATE;
        }
        return ShieldPermission.Authority.SELECT;
    }

    private DatabaseTable normalizeDatabaseTable(DatabaseTable dbTable) {
        String originalDb = dbTable.getDatabase().replace(".db", "").trim();
        String originalTable = dbTable.getTable().replace(".db", "").trim();

        if (originalTable.contains(".")) {
            String[] parts = originalTable.split("\\.");
            if (parts.length == 2) {
                return new DatabaseTable(parts[0], parts[1]);
            }
        }
        return new DatabaseTable(originalDb, originalTable);
    }

    public DatabaseTable parseDatabaseTable(String rpd) {
        try {
            Matcher matcher = RPD_PATTERN.matcher(rpd);
            if (matcher.find()) {
                String database = matcher.group(1);
                String table = matcher.group(2) != null ? matcher.group(2) : "*";
                if ("all".equalsIgnoreCase(database)) {
                    database = "*";
                }
                return new DatabaseTable(database, table);
            }

            if (rpd.contains("hive?option")) {
                return new DatabaseTable("*", "*");
            }
        } catch (Exception e) {
            LOG.error("RPD parsing error for: {}", rpd, e);
        }

        LOG.warn("Unrecognized RPD format: {}", rpd);
        return null;
    }
}
