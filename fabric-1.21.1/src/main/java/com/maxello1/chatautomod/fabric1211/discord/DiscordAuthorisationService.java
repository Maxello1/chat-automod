package com.maxello1.chatautomod.fabric1211.discord;

import java.util.Collection;

public final class DiscordAuthorisationService {
    public boolean isAuthorised(
            String userId,
            Collection<String> roleIds,
            DiscordConfig config
    ) {
        if (userId != null && config.allowedUserIds().contains(userId)) {
            return true;
        }
        return roleIds != null && roleIds.stream().anyMatch(config.allowedRoleIds()::contains);
    }
}
