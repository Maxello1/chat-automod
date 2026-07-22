package com.maxello1.chatautomod.core.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicJsonFileStore {
    public void write(Path target, String json) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Path backup = absolute.resolveSibling(absolute.getFileName() + ".bak");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        if (Files.exists(absolute)) Files.copy(absolute, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String readWithBackup(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        try {
            return Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException primary) {
            Path backup = absolute.resolveSibling(absolute.getFileName() + ".bak");
            if (Files.exists(backup)) return Files.readString(backup, StandardCharsets.UTF_8);
            throw primary;
        }
    }
}
