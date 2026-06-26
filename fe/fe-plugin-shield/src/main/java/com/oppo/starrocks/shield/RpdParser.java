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

    private final String areaFilter;

    public RpdParser(String areaFilter) {
        this.areaFilter = areaFilter;
    }

    public List<DatabaseTable> parseRpdPaths(List<ResourcePermission> permissions) {
        return permissions.stream()
                .map(ResourcePermission::getRpd)
                .filter(Objects::nonNull)
                .filter(rpd -> rpd.contains(areaFilter))
                .map(this::parseDatabaseTable)
                .filter(Objects::nonNull)
                .map(this::normalizeDatabaseTable)
                .collect(Collectors.toList());
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
