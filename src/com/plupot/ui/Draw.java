package com.plupot.ui;

import java.awt.FontMetrics;
import java.awt.Graphics2D;

/** Small drawing helpers. All text is drawn with a baseline Y. */
public final class Draw {

    public static void centered(Graphics2D g, String text, float centerX, float baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, centerX - fm.stringWidth(text) / 2f, baselineY);
    }

    public static float width(Graphics2D g, String text) {
        return g.getFontMetrics().stringWidth(text);
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private Draw() {
    }
}