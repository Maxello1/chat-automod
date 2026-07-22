package com.maxello1.chatautomod.core;

import com.maxello1.chatautomod.core.config.DurationParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test
    void parsesSupportedUnitsAndRejectsInvalidValues() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30s"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        assertEquals(Duration.ofHours(2), DurationParser.parse("2h"));
        assertEquals(Duration.ofDays(1), DurationParser.parse("1d"));
        assertEquals(Duration.ofDays(7), DurationParser.parse("1w"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-1m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("999999999999999999999w"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("2h", Duration.ofHours(1)));
    }
}
