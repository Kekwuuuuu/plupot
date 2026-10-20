package com.plupot.ui;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads the bundled application icon and applies it to a window. */
public final class AppIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private AppIcon() {
    }

    public static void applyTo(JFrame frame) {
        List<BufferedImage> images = loadAll();
        if (images.isEmpty()) {
            return;
        }
        frame.setIconImage(images.get(0));
        frame.setIconImages(new ArrayList<Image>(images));
    }

    public static List<BufferedImage> loadAll() {
        List<BufferedImage> out = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage img = load("/com/plupot/res/icon_" + size + ".png");
            if (img != null) {
                out.add(img);
            }
        }
        if (out.isEmpty()) {
            BufferedImage fallback = load("/com/plupot/res/icon.png");
            if (fallback != null) {
                out.add(fallback);
            }
        }
        return out;
    }

    private static BufferedImage load(String path) {
        try (InputStream in = AppIcon.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            System.err.println("[Plupot] Could not load icon " + path + ": " + e.getMessage());
            return null;
        }
    }
}