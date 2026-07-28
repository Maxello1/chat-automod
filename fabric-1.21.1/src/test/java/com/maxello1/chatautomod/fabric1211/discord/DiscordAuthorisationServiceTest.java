package com.maxello1.chatautomod.fabric1211.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAuthorisationServiceTest {
    private final DiscordAuthorisationService service = new DiscordAuthorisationService();
    private final DiscordConfig config = configured(Set.of("role-1"), Set.of("user-1"));

    @Test
    void authorisesConfiguredUserId() {
        assertTrue(service.isAuthorised("user-1", List.of(), config));
    }

    @Test
    void authorisesConfiguredRoleId() {
        assertTrue(service.isAuthorised("other", List.of("role-1", "role-2"), config));
    }

    @Test
    void rejectsUnconfiguredUserAndRoles() {
        assertFalse(service.isAuthorised("other", List.of("role-2"), config));
    }

    private static DiscordConfig configured(Set<String> roles, Set<String> users) {
        DiscordConfig defaults = DiscordConfig.defaults();
        return new DiscordConfig(
                1,
                true,
                defaults.tokenEnvironmentVariable(),
                "1",
                "2",
                roles,
                users,
                "",
                defaults.caseExpiry(),
                false,
                defaults.actions());
    }
}
