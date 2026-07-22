package com.maxello1.chatautomod.core.api;

import java.util.UUID;

@FunctionalInterface
public interface PermissionService {
    boolean hasPermission(UUID playerId, String permission, int fallbackOperatorLevel);
}
