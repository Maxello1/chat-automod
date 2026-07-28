package com.maxello1.chatautomod.fabric1211.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.List;

final class JdaDiscordMessageFactory {
    private JdaDiscordMessageFactory() {}

    static MessageCreateData create(DiscordAlertMessage message) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(message.title())
                .setDescription(message.description())
                .setTimestamp(message.timestamp())
                .setColor(message.color());
        message.fields().forEach(field ->
                embed.addField(field.name(), field.value(), field.inline()));

        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setEmbeds(List.of(embed.build()))
                .setAllowedMentions(List.of())
                .mentionRoles(message.allowedRoleMentionIds());
        if (!message.buttons().isEmpty()) {
            builder.setComponents(List.of(ActionRow.of(message.buttons().stream()
                    .map(JdaDiscordMessageFactory::button)
                    .toList())));
        }
        if (!message.content().isEmpty()) {
            builder.setContent(message.content());
        }
        return builder.build();
    }

    private static Button button(DiscordAlertMessage.Button button) {
        return switch (button.style()) {
            case PRIMARY -> Button.primary(button.customId(), button.label());
            case DANGER -> Button.danger(button.customId(), button.label());
            case SECONDARY -> Button.secondary(button.customId(), button.label());
        };
    }
}
