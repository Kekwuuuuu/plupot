package com.plupot.share;

import com.plupot.ui.Draw;
import com.plupot.ui.Fonts;
import com.plupot.ui.Palette;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Renders the 1024x1024 share card and writes it into the user's Downloads folder. */
public final class ShareCard {

    public static final int SIZE = 1024;

    private static final float BORDER = 24f;                 // was 8f, "a bit thick"
    private static final float TITLE_BASELINE = 380f;        // upper center
    private static final float SCORE_BASELINE = 820f;        // bottom center
    private static final float TITLE_SIZE = 190f;
    private static final float SCORE_SIZE = 120f;            // smaller than title

    private ShareCard() {
    }

    public static Path save(int highScore) throws IOException {
        BufferedImage image = render(highScore);
        Path dir = downloadsDir();
        Files.createDirectories(dir);

        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path target = unique(dir.resolve("plupot_" + stamp + ".png"));

        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    private static BufferedImage render(int highScore) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // background
        g.setColor(Palette.C1);
        g.fillRect(0, 0, SIZE, SIZE);

        // C2 outline: sharp box, flush with the very edge, thick.
        // Insetting the path by BORDER/2 makes the stroke's outer half
        // clip off the image edge, so the *visible* border is exactly BORDER px.
        g.setColor(Palette.C2);
        g.setStroke(new BasicStroke(BORDER, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new Rectangle2D.Float(
                BORDER / 2f, BORDER / 2f,
                SIZE - BORDER, SIZE - BORDER));

        // title — upper center
        g.setColor(Palette.C2);
        g.setFont(Fonts.bold(TITLE_SIZE));
        Draw.centered(g, "Plupot", SIZE / 2f, TITLE_BASELINE);

        // highest score — bottom center, color tiers apply
        g.setColor(Palette.scoreColor(highScore));
        g.setFont(Fonts.bold(SCORE_SIZE));
        Draw.centered(g, Integer.toString(highScore), SIZE / 2f, SCORE_BASELINE);

        g.dispose();
        return img;
    }

    private static Path downloadsDir() {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path downloads = home.resolve("Downloads");
        if (!Files.isDirectory(downloads)) {
            try {
                Files.createDirectories(downloads);
            } catch (IOException ignored) {
                // fall through and use home
            }
        }
        return Files.isDirectory(downloads) ? downloads : home;
    }

    private static Path unique(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            Path candidate = target.resolveSibling(stem + "_" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return target;
    }
}