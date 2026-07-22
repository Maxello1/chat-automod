package com.maxello1.chatautomod.fabric262;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MinecraftPlatformAdapter;
import com.maxello1.chatautomod.core.api.PlatformPlayer;
import com.maxello1.chatautomod.core.api.StaffRecipient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FabricModerationPlatform implements MinecraftPlatformAdapter {
    private final FabricRuntime runtime;

    FabricModerationPlatform(FabricRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void execute(ActionPlan plan) {
        for (ModerationAction action : plan.actions()) {
            try {
                switch (action) {
                    case ModerationAction.NotifyStaff alert -> notifyStaff(alert);
                    case ModerationAction.Warn warning -> notifyPlayer(warning.playerId(), warning.message());
                    case ModerationAction.Mute mute -> mutePlayer(mute.playerId(), mute.until(), mute.reason());
                    case ModerationAction.Kick kick -> kickPlayer(kick.playerId(), kick.reason());
                    case ModerationAction.ExecuteCommand command -> executeServerCommand(command.command());
                }
            } catch (RuntimeException exception) {
                runtime.logActionFailure(action.type(), exception);
            }
        }
    }

    @Override
    public Path configDirectory() {
        return runtime.configDirectory();
    }

    @Override
    public Path worldDataDirectory() {
        return runtime.worldDataDirectory();
    }

    @Override
    public Collection<StaffRecipient> onlineStaff() {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return java.util.List.of();
        }
        int fallback = runtime.fallbackOperatorLevel();
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> runtime.permissions().hasPermission(
                        player,
                        FabricPermissionService.ALERTS,
                        fallback))
                .map(player -> new StaffRecipient(
                        player.getUUID(),
                        player.getName().getString(),
                        runtime.inspectEnabled(player.getUUID())
                                && runtime.permissions().hasPermission(
                                player,
                                FabricPermissionService.INSPECT,
                                fallback)))
                .toList();
    }

    @Override
    public Optional<PlatformPlayer> findPlayer(UUID uuid) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(server.getPlayerList().getPlayer(uuid))
                .map(player -> new PlatformPlayer(player.getUUID(), player.getName().getString()));
    }

    @Override
    public void notifyPlayer(UUID playerId, String message) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal(safeText(message)));
        }
    }

    @Override
    public void notifyStaff(ModerationAction.NotifyStaff alert) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return;
        }
        for (StaffRecipient recipient : onlineStaff()) {
            ServerPlayer player = server.getPlayerList().getPlayer(recipient.playerId());
            if (player != null) {
                player.sendSystemMessage(staffAlert(alert, recipient.inspectEnabled()));
            }
        }
    }

    private Component staffAlert(ModerationAction.NotifyStaff alert, boolean inspectEnabled) {
        String rules = alert.ruleIds().isEmpty() ? "unknown rule" : String.join(", ", alert.ruleIds());
        MutableComponent message = Component.literal("[AutoMod] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(safeText(alert.playerName())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" triggered ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(safeText(rules)).withStyle(ChatFormatting.RED));

        message.append(Component.literal("\nAction: " + alert.decision() + ", +" + alert.pointsAdded()
                + " points (score " + alert.scoreAfter() + ")").withStyle(ChatFormatting.GRAY));
        if (inspectEnabled && runtime.showOriginalAlerts()) {
            message.append(Component.literal("\nMessage: \"")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(safeText(alert.originalMessage())).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("\"").withStyle(ChatFormatting.DARK_GRAY)));
        }

        Component hover = Component.literal("Rules: " + safeText(rules)
                + "\nScore: " + alert.scoreAfter()
                + "\nTime: " + alert.timestamp());
        String target = alert.playerId().toString();
        MutableComponent history = button(
                "[History]",
                "/automod history " + target,
                hover,
                ChatFormatting.AQUA);
        Duration muteDuration = runtime.muteButtonDuration();
        MutableComponent mute = button(
                "[Mute " + compactDuration(muteDuration) + "]",
                "/automod mute " + target + " " + compactDuration(muteDuration) + " Staff alert action",
                hover,
                ChatFormatting.RED);
        return message.append(Component.literal("\n")).append(history).append(Component.literal(" ")).append(mute);
    }

    private static MutableComponent button(
            String label,
            String command,
            Component hover,
            ChatFormatting color
    ) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    @Override
    public void mutePlayer(UUID playerId, Instant until, String reason) {
        runtime.ensureAutomaticMute(playerId, until, safeText(reason));
        notifyPlayer(playerId, "You have been muted until " + until + ". Reason: " + safeText(reason));
    }

    @Override
    public void kickPlayer(UUID playerId, String reason) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.connection.disconnect(Component.literal(safeText(reason)));
        }
    }

    @Override
    public void executeServerCommand(String command) {
        MinecraftServer server = runtime.server();
        if (server == null || command == null || command.isBlank()) {
            return;
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), normalized);
    }

    private static String compactDuration(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds % 604_800 == 0) return (seconds / 604_800) + "w";
        if (seconds % 86_400 == 0) return (seconds / 86_400) + "d";
        if (seconds % 3_600 == 0) return (seconds / 3_600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static String safeText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), 512));
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(512)
                .forEach(result::appendCodePoint);
        return result.toString();
    }
}
