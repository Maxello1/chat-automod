package com.maxello1.chatautomod.fabric1211.discord;

import java.time.Instant;
import java.util.List;
import java.util.Set;

record DiscordAlertMessage(
        String content,
        String title,
        String description,
        List<Field> fields,
        List<Button> buttons,
        Instant timestamp,
        int color,
        Set<String> allowedRoleMentionIds
) {
    DiscordAlertMessage {
        content = content == null ? "" : content;
        description = description == null ? "" : description;
        fields = List.copyOf(fields);
        buttons = List.copyOf(buttons);
        allowedRoleMentionIds = Set.copyOf(allowedRoleMentionIds);
    }

    record Field(String name, String value, boolean inline) {}

    record Button(String customId, String label, Style style) {
        enum Style {
            PRIMARY,
            DANGER,
            SECONDARY
        }
    }
}
