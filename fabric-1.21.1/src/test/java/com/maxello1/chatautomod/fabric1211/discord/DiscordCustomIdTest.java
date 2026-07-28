package com.maxello1.chatautomod.fabric1211.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordCustomIdTest {
    @Test
    void parsesCompactOpaqueCustomId() {
        DiscordCustomId parsed = DiscordCustomId.parse("cam:CaseId_12345678:m60").orElseThrow();

        assertEquals("CaseId_12345678", parsed.caseId());
        assertEquals(DiscordPunishment.MUTE_1_HOUR, parsed.action());
    }

    @Test
    void rejectsMalformedOrUnknownIds() {
        assertTrue(DiscordCustomId.parse("other:CaseId_12345678:m60").isEmpty());
        assertTrue(DiscordCustomId.parse("cam:short:m60").isEmpty());
        assertTrue(DiscordCustomId.parse("cam:CaseId_12345678:warn").isEmpty());
        assertTrue(DiscordCustomId.parse("cam:CaseId_12345678:m60:extra").isEmpty());
    }
}
