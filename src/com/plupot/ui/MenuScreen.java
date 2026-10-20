package com.plupot.ui;

import com.plupot.core.GameContext;
import com.plupot.core.Screen;
import com.plupot.share.ShareCard;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.geom.Path2D;
import java.nio.file.Path;

/**
 * Title / high score / three keyboard-only buttons.
 * Mouse is intentionally not wired to anything.
 */
public final class MenuScreen extends Screen {

    // 开始 / 分享 / 退出  (Start / Share / Exit)
    private static final String[] LABELS = {"开始", "分享", "退出"};
    private static final int IDX_LAUNCH = 0;
    private static final int IDX_SHARE = 1;
    private static final int IDX_EXIT = 2;

    private static final double STATUS_SECONDS = 4.0;

    private int selected;
    private String status = "";
    private double statusLeft;

    /** True while the Launch flash is playing (input is ignored). */
    private boolean busy;

    public MenuScreen(GameContext ctx) {
        super(ctx);
    }

    @Override
    public void onEnter() {
        // Called on initial show AND when returning from the overlay.
        busy = false;
    }

    @Override
    public void update(double dt) {
        if (statusLeft > 0) {
            statusLeft -= dt;
            if (statusLeft <= 0) {
                statusLeft = 0;
                status = "";
            }
        }
    }

    @Override
    public void onKeyPressed(int keyCode) {
        if (busy) {
            return;
        }
        switch (keyCode) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> selected = wrap(selected - 1);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> selected = wrap(selected + 1);
            case KeyEvent.VK_ENTER -> activate();
            default -> {
            }
        }
    }

    private int wrap(int index) {
        return (index + LABELS.length) % LABELS.length;
    }

    private void activate() {
        switch (selected) {
            case IDX_LAUNCH -> {
                busy = true;
                // Flash first, then switch to the fullscreen overlay.
                ctx.flashAndThen(ctx::openOverlay);
            }
            case IDX_SHARE -> shareNow();
            case IDX_EXIT -> ctx.quit();
            default -> {
            }
        }
    }

    private void shareNow() {
        ctx.flash();
        try {
            Path file = ShareCard.save(ctx.getHighScore());
            setStatus("Saved to " + file.getFileName());
        } catch (Exception e) {
            setStatus("Could not save image");
            System.err.println("[Plupot] Share failed: " + e);
        }
    }

    private void setStatus(String message) {
        status = message;
        statusLeft = STATUS_SECONDS;
    }

    @Override
    public void render(Graphics2D g, int width, int height) {
        float cx = width / 2f;

        float titleSize = Draw.clamp(height * 0.150f, 40f, 190f);
        float scoreSize = Draw.clamp(height * 0.062f, 18f, 76f);
        float buttonSize = Draw.clamp(height * 0.056f, 18f, 70f);

        float titleY = height * 0.255f;
        float firstButtonY = height * 0.540f;
        float buttonGapY = height * 0.092f;
        // Exact vertical midpoint between title baseline and first button baseline.
        float scoreY = (titleY + firstButtonY) / 2f;

        g.setColor(Palette.C2);
        g.setFont(Fonts.bold(titleSize));
        Draw.centered(g, "Plupot", cx, titleY);

        // Score color depends on its value.
        g.setColor(Palette.scoreColor(ctx.getHighScore()));
        g.setFont(Fonts.bold(scoreSize));
        Draw.centered(g, Integer.toString(ctx.getHighScore()), cx, scoreY);

        g.setColor(Palette.C2);
        for (int i = 0; i < LABELS.length; i++) {
            String label = LABELS[i];
            float baseline = firstButtonY + i * buttonGapY;

            // Per-label font so the Chinese glyphs render instead of tofu boxes.
            g.setFont(Fonts.boldFor(label, buttonSize));
            float textWidth = Draw.width(g, label);
            float left = cx - textWidth / 2f;
            g.drawString(label, left, baseline);

            if (i == selected) {
                drawIndicator(g, left - buttonSize * 0.75f, baseline);
            }
        }

        if (statusLeft > 0 && !status.isEmpty()) {
            g.setColor(Palette.C2);
            float statusSize = Draw.clamp(height * 0.026f, 11f, 26f);
            g.setFont(Fonts.boldFor(status, statusSize));
            Draw.centered(g, status, cx, height * 0.945f);
        }
    }

    /** Filled triangle pointing right, vertically centered on the button text. */
    private void drawIndicator(Graphics2D g, float tipX, float baseline) {
        FontMetrics fm = g.getFontMetrics();
        float cy = baseline - fm.getAscent() * 0.46f;
        float size = fm.getAscent() * 0.30f;

        Path2D.Float triangle = new Path2D.Float();
        triangle.moveTo(tipX - size * 1.4f, cy - size);
        triangle.lineTo(tipX - size * 1.4f, cy + size);
        triangle.lineTo(tipX, cy);
        triangle.closePath();

        g.fill(triangle);
    }
}