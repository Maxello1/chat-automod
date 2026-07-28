package com.maxello1.chatautomod.fabric1211.discord;

import java.util.concurrent.CompletableFuture;

interface DiscordAlertDestination {
    CompletableFuture<String> send(DiscordAlertMessage message);
}
