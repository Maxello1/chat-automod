package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.fabric1211.PunishmentRequest;
import com.maxello1.chatautomod.fabric1211.PunishmentResult;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface DiscordPunishmentHandler {
    CompletableFuture<PunishmentResult> execute(PunishmentRequest request);
}
