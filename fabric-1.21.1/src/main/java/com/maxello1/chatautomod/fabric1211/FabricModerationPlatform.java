package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MinecraftPlatformAdapter;
import com.maxello1.chatautomod.core.api.PlatformPlayer;
import com.maxello1.chatautomod.core.api.StaffRecipient;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.RuleCategory;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricModerationPlatform implements MinecraftPlatformAdapter {
    private static final int DEFAULT_STAFF_OPERATOR_LEVEL = 3;
    private static final Duration MUTE_NOTICE_COOLDOWN = Duration.ofSeconds(2);

    private final MinecraftServer server;
    private final FabricPermissionService permissions;
    private final Map<UUID, MuteState> activeMutes = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastMuteNotices = new ConcurrentHashMap<>();

    public FabricModerationPlatform(MinecraftServer server, FabricPermissionService permissions) {
        this.server = server;
        this.permissions = permissions;
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatautomod");
    }

    @Override
    public Path worldDataDirectory() {
        return server.getWorldPath(LevelResource.ROOT).resolve("chatautomod");
    }

    @Override
    public Collection<StaffRecipient> onlineStaff() {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> permissions.hasPermission(player, "chatautomod.alerts", DEFAULT_STAFF_OPERATOR_LEVEL))
                .map(player -> new StaffRecipient(
                        player.getUUID(),
                        player.getGameProfile().getName(),
                        permissions.hasPermission(player, "chatautomod.inspect", DEFAULT_STAFF_OPERATOR_LEVEL)))
                .toList();
    }

    @Override
    public Optional<PlatformPlayer> findPlayer(UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player == null
                ? Optional.empty()
                : Optional.of(new PlatformPlayer(uuid, player.getGameProfile().getName()));
    }

    public BypassProfile bypassProfile(ServerPlayer player) {
        if (permissions.hasPermission(player, "chatautomod.bypass", DEFAULT_STAFF_OPERATOR_LEVEL)) {
            return BypassProfile.ALL;
        }
        EnumSet<RuleCategory> categories = EnumSet.noneOf(RuleCategory.class);
        if (permissions.hasPermission(player, "chatautomod.bypass.spam", DEFAULT_STAFF_OPERATOR_LEVEL)) {
            categories.add(RuleCategory.SPAM);
            categories.add(RuleCategory.DUPLICATE);
            categories.add(RuleCategory.SIMILARITY);
            categories.add(RuleCategory.FLOODING);
        }
        if (permissions.hasPermission(player, "chatautomod.bypass.filter", DEFAULT_STAFF_OPERATOR_LEVEL)) {
            categories.add(RuleCategory.FILTERED_CONTENT);
        }
        if (permissions.hasPermission(player, "chatautomod.bypass.advertising", DEFAULT_STAFF_OPERATOR_LEVEL)) {
            categories.add(RuleCategory.ADVERTISING);
        }
        return categories.isEmpty() ? BypassProfile.NONE : new BypassProfile(false, categories);
    }

    @Override
    public void notifyPlayer(UUID playerId, String message) {
        runOnServerThread(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal(message));
            }
        });
    }

    @Override
    public void notifyStaff(ModerationAction.NotifyStaff alert) {
        String ruleIds = alert.ruleIds().isEmpty() ? "unknown" : String.join(", ", alert.ruleIds());
        Component message = Component.translatable(
                "chatautomod.staff.alert",
                alert.playerName(),
                ruleIds,
                alert.pointsAdded(),
                alert.scoreAfter()
        );
        runOnServerThread(() -> server.getPlayerList().getPlayers().stream()
                .filter(player -> permissions.hasPermission(player, "chatautomod.alerts", DEFAULT_STAFF_OPERATOR_LEVEL))
                .forEach(player -> player.sendSystemMessage(message)));
    }

    @Override
    public void mutePlayer(UUID playerId, Instant until, String reason) {
        String resolvedReason = reason == null || reason.isBlank() ? "Muted by Chat AutoMod" : reason;
        activeMutes.put(playerId, new MuteState(until, resolvedReason, "automatic"));
        runOnServerThread(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.sendSystemMessage(Component.translatable(
                        "chatautomod.mute.applied",
                        until.toString(),
                        resolvedReason
                ));
            }
        });
    }

    @Override
    public void kickPlayer(UUID playerId, String reason) {
        String resolvedReason = reason == null || reason.isBlank() ? "Removed by Chat AutoMod" : reason;
        runOnServerThread(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.connection.disconnect(Component.literal(resolvedReason));
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
        runOnServerThread(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), normalized));
    }

    public boolean blockMutedMessage(ServerPlayer player, Instant now) {
        UUID playerId = player.getUUID();
        MuteState mute = activeMutes.get(playerId);
        if (mute == null) {
            return false;
        }
        if (!mute.activeAt(now)) {
            activeMutes.remove(playerId, mute);
            lastMuteNotices.remove(playerId);
            return false;
        }

        Instant previousNotice = lastMuteNotices.get(playerId);
        if (previousNotice == null || !previousNotice.plus(MUTE_NOTICE_COOLDOWN).isAfter(now)) {
            lastMuteNotices.put(playerId, now);
            long seconds = Math.max(1L, Duration.between(now, mute.mutedUntil()).toSeconds());
            player.sendSystemMessage(Component.translatable("chatautomod.mute.active", seconds, mute.reason()));
        }
        return true;
    }

    private void runOnServerThread(Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
