package com.plupot.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persists the highest point value in ~/.plupot/save.properties */
public final class SaveData {

    private static final String DIR_NAME = ".plupot";
    private static final String FILE_NAME = "save.properties";
    private static final String KEY_HIGH_SCORE = "highScore";

    private final Path file;
    private int highScore;

    public SaveData() {
        Path home = Path.of(System.getProperty("user.home", "."));
        this.file = home.resolve(DIR_NAME).resolve(FILE_NAME);
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            highScore = Math.max(0, Integer.parseInt(props.getProperty(KEY_HIGH_SCORE, "0").trim()));
        } catch (IOException | NumberFormatException e) {
            System.err.println("[Plupot] Could not read save file: " + e.getMessage());
            highScore = 0;
        }
    }

    public int getHighScore() {
        return highScore;
    }

    public void setHighScore(int value) {
        if (value > highScore) {
            highScore = value;
            save();
        }
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty(KEY_HIGH_SCORE, Integer.toString(highScore));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Plupot save data");
            }
        } catch (IOException e) {
            System.err.println("[Plupot] Could not write save file: " + e.getMessage());
        }
    }
}