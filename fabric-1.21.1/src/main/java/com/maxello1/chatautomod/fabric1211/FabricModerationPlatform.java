package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MinecraftPlatformAdapter;
import com.maxello1.chatautomod.core.api.PlatformPlayer;
import com.maxello1.chatautomod.core.api.StaffRecipient;
import com.maxello1.chatautomod.core.model.MuteKind;
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
        int staffFallback = runtime.staffFallbackOperatorLevel();
        int commandFallback = runtime.commandFallbackOperatorLevel();
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> runtime.permissions().hasPermission(
                        player,
                        FabricPermissionService.ALERTS,
                        staffFallback))
                .map(player -> new StaffRecipient(
                        player.getUUID(),
                        player.getGameProfile().getName(),
                        runtime.inspectEnabled(player.getUUID())
                                && runtime.permissions().hasPermission(
                                player,
                                FabricPermissionService.INSPECT,
                                commandFallback)))
                .toList();
    }

    @Override
    public Optional<PlatformPlayer> findPlayer(UUID uuid) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(server.getPlayerList().getPlayer(uuid))
                .map(player -> new PlatformPlayer(player.getUUID(), player.getGameProfile().getName()));
    }

    @Override
    public void notifyPlayer(UUID playerId, String message) {
        runOnServerThread(() -> {
            MinecraftServer server = runtime.server();
            if (server == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal(safeText(message)));
            }
        });
    }

    @Override
    public void notifyStaff(ModerationAction.NotifyStaff alert) {
        runOnServerThread(() -> {
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
        });
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
        } else {
            message.append(Component.literal("\nMessage content hidden by privacy settings")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        message.append(Component.literal("\nMatched rule: " + safeText(rules)).withStyle(ChatFormatting.DARK_GRAY));

        Component hover = Component.literal("Rules: " + safeText(rules)
                + "\nScore: " + alert.scoreAfter()
                + "\nTime: " + alert.timestamp());
        String target = alert.playerId().toString();
        MutableComponent history = button(
                "[History]",
                "/automod history " + target,
                hover,
                ChatFormatting.AQUA);
        MutableComponent unmute = button(
                "[Unmute]",
                "/automod unmute " + target,
                hover,
                ChatFormatting.GREEN);
        MutableComponent inspect = button(
                "[Inspect]",
                "/automod inspect on",
                hover,
                ChatFormatting.YELLOW);
        return message.append(Component.literal("\n"))
                .append(history)
                .append(Component.literal(" "))
                .append(unmute)
                .append(Component.literal(" "))
                .append(inspect);
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
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    void applyAutomaticMute(ModerationAction.Mute mute) {
        runOnServerThread(() -> runtime.ensureAutomaticMute(mute)
                .ifPresent(applied -> notifyMuteApplied(mute.playerId(), applied)));
    }

    @Override
    public void mutePlayer(UUID playerId, Instant until, String reason) {
        runOnServerThread(() -> runtime.ensureAutomaticTemporaryMute(
                        playerId, until, reason, "automatic", "unknown")
                .ifPresent(applied -> notifyMuteApplied(playerId, applied)));
    }

    private void notifyMuteApplied(UUID playerId, com.maxello1.chatautomod.core.model.MuteState mute) {
        String safeReason = safeText(mute.reason());
        if (mute.kind() == MuteKind.PERMANENT) {
            notifyPlayer(playerId, "You have been permanently muted. Reason: " + safeReason);
        } else {
            notifyPlayer(playerId, "You have been muted until " + mute.mutedUntil()
                    + ". Reason: " + safeReason);
        }
    }

    @Override
    public void kickPlayer(UUID playerId, String reason) {
        runOnServerThread(() -> {
            MinecraftServer server = runtime.server();
            if (server == null) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.connection.disconnect(Component.literal(safeText(reason)));
            }
        });
    }

    @Override
    public void executeServerCommand(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        if (normalized.isBlank() || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return;
        }
        runOnServerThread(() -> {
            MinecraftServer server = runtime.server();
            if (server != null) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), normalized);
            }
        });
    }

    private void runOnServerThread(Runnable task) {
        MinecraftServer server = runtime.server();
        if (server == null) {
            return;
        }
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    static String compactDuration(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder value = new StringBuilder();
        if (days > 0) value.append(days).append("d ");
        if (hours > 0) value.append(hours).append("h ");
        if (minutes > 0) value.append(minutes).append("m ");
        if (seconds > 0 || value.isEmpty()) value.append(seconds).append("s");
        return value.toString().trim();
    }

    static String safeText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), 512));
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && (codePoint < 0xD800 || codePoint > 0xDFFF)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(512)
                .forEach(result::appendCodePoint);
        return result.toString();
    }
}
