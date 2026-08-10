package com.oppo.starrocks.shield;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scopes {@link ResourcePermission} records to app groups allowed for a psaId.
 */
final class ShieldPermissionFilter {
    private ShieldPermissionFilter() {
    }

    static List<ResourcePermission> filterByAppGroups(List<ResourcePermission> permissions,
                                                      Set<String> allowedGroupIds) {
        if (permissions.isEmpty() || allowedGroupIds.isEmpty()) {
            return Collections.emptyList();
        }
        return permissions.stream()
                .filter(permission -> matchesAppGroup(permission.getRpd(), allowedGroupIds))
                .collect(Collectors.toList());
    }

    private static boolean matchesAppGroup(String rpd, Set<String> allowedGroupIds) {
        String appGroup = RpdParser.extractAppGroup(rpd);
        if (appGroup == null) {
            // Legacy RPD without "{group}:group@" prefix cannot be scoped; keep when psaId has groups.
            return true;
        }
        return allowedGroupIds.contains(appGroup);
    }
}
