package com.plupot.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build-time helper: removes the persisted save file so every fresh build
 * starts at a high score of 0. Mirrors SaveData's path resolution.
 */
public final class ResetSave {

    public static void main(String[] args) throws IOException {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path file = home.resolve(".plupot").resolve("save.properties");

        boolean deleted = Files.deleteIfExists(file);
        if (deleted) {
            System.out.println("[Plupot] High score reset: removed " + file);
        } else {
            System.out.println("[Plupot] High score already 0 (no save file at " + file + ")");
        }
    }

    private ResetSave() {
    }
}