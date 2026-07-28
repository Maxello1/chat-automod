package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.state.MuteService;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

final class MinecraftPunishmentService {
    private final FabricRuntime runtime;
    private final MuteService mutes;

    MinecraftPunishmentService(FabricRuntime runtime, MuteService mutes) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.mutes = Objects.requireNonNull(mutes, "mutes");
    }

    PunishmentResult muteTemporary(
            UUID playerId,
            String playerName,
            Duration duration,
            String reason,
            ExternalModerator moderator
    ) {
        requireServerThread();
        try {
            var config = runtime.currentConfig();
            MuteState mute = mutes.muteTemporary(
                    playerId,
                    playerName,
                    duration,
                    config.mutes().maximumDuration(),
                    FabricRuntime.safeReason(reason),
                    muteSource(moderator),
                    muteRuleReference(reason, moderator),
                    minecraftModeratorId(moderator),
                    config.state().maximumTrackedPlayers());
            runtime.scheduleSnapshot();
            runtime.platform().notifyPlayer(
                    playerId,
                    "You have been muted until " + mute.mutedUntil() + ". Reason: " + mute.reason());
            return PunishmentResult.muted(
                    "Muted " + FabricModerationPlatform.safeText(playerName)
                            + " until " + mute.mutedUntil() + ".",
                    mute.mutedUntil());
        } catch (IllegalArgumentException exception) {
            return PunishmentResult.failure(
                    FabricModerationPlatform.safeText(exception.getMessage()),
                    true);
        }
    }

    PunishmentResult mutePermanent(
            UUID playerId,
            String playerName,
            String reason,
            ExternalModerator moderator
    ) {
        requireServerThread();
        try {
            var config = runtime.currentConfig();
            MuteState mute = mutes.mutePermanent(
                    playerId,
                    playerName,
                    FabricRuntime.safeReason(reason),
                    muteSource(moderator),
                    muteRuleReference(reason, moderator),
                    minecraftModeratorId(moderator),
                    config.state().maximumTrackedPlayers());
            runtime.scheduleSnapshot();
            runtime.platform().notifyPlayer(
                    playerId,
                    "You have been permanently muted. Reason: " + mute.reason());
            return PunishmentResult.success(
                    "Permanently muted " + FabricModerationPlatform.safeText(playerName) + ".");
        } catch (IllegalArgumentException exception) {
            return PunishmentResult.failure(
                    FabricModerationPlatform.safeText(exception.getMessage()),
                    true);
        }
    }

    PunishmentResult banPermanent(
            UUID playerId,
            String playerName,
            String reason,
            ExternalModerator moderator
    ) {
        MinecraftServer server = requireServerThread();
        GameProfile profile = resolveProfile(server, playerId, playerName);
        UserBanList bans = server.getPlayerList().getBans();
        if (bans.isBanned(profile)) {
            return PunishmentResult.success(
                    FabricModerationPlatform.safeText(playerName) + " is already permanently banned.");
        }

        String safeReason = FabricRuntime.safeReason(reason);
        String source = FabricRuntime.boundedText(
                moderator.displayName() + " (Discord " + moderator.id() + ")",
                256);
        bans.add(new UserBanListEntry(
                profile,
                Date.from(runtime.clock().instant()),
                source,
                null,
                safeReason));
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            online.connection.disconnect(Component.literal("You have been permanently banned. Reason: "
                    + safeReason));
        }
        try {
            bans.save();
        } catch (IOException exception) {
            return PunishmentResult.failure("The ban was applied but could not be confirmed on disk.", false);
        }

        return PunishmentResult.success(
                "Permanently banned " + FabricModerationPlatform.safeText(playerName) + ".");
    }

    private MinecraftServer requireServerThread() {
        MinecraftServer server = runtime.server();
        if (server == null || !runtime.ready()) {
            throw new IllegalStateException("Minecraft server is unavailable");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Minecraft punishment called outside the server thread");
        }
        return server;
    }

    private static GameProfile resolveProfile(
            MinecraftServer server,
            UUID playerId,
            String playerName
    ) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile();
        }
        return server.getProfileCache().get(playerId)
                .orElseGet(() -> new GameProfile(playerId, playerName));
    }

    private static UUID minecraftModeratorId(ExternalModerator moderator) {
        if (!"minecraft".equals(moderator.source())) {
            return null;
        }
        try {
            return UUID.fromString(moderator.id());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String muteSource(ExternalModerator moderator) {
        return "discord".equals(moderator.source()) ? "discord" : "manual";
    }

    private static String muteRuleReference(String reason, ExternalModerator moderator) {
        return "discord".equals(moderator.source())
                ? FabricRuntime.boundedText(reason, 256)
                : "manual";
    }
}
