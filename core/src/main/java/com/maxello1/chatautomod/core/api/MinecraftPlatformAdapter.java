package com.maxello1.chatautomod.core.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface MinecraftPlatformAdapter extends ModerationPlatform {
    Path configDirectory();
    Path worldDataDirectory();
    Collection<StaffRecipient> onlineStaff();
    Optional<PlatformPlayer> findPlayer(UUID uuid);
}
