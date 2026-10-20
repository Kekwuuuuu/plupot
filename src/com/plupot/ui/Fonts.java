package com.plupot.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/** Resolves one bold family across platforms so the game looks the same everywhere. */
public final class Fonts {

    private static final List<String> PREFERRED = Arrays.asList(
            "Segoe UI", "Inter", "Helvetica Neue", "Arial", "DejaVu Sans", "Liberation Sans");

    /** CJK-capable families, used only when the primary family lacks a glyph. */
    private static final List<String> CJK_PREFERRED = Arrays.asList(
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Hiragino Sans GB",
            "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei",
            "SimHei", "SimSun");

    private static String family;
    private static String cjkFamily;

    public static synchronized String family() {
        if (family == null) {
            List<String> available = availableFamilies();
            for (String candidate : PREFERRED) {
                if (available.contains(candidate)) {
                    family = candidate;
                    break;
                }
            }
            if (family == null) {
                family = Font.SANS_SERIF;
            }
        }
        return family;
    }

    public static synchronized String cjkFamily() {
        if (cjkFamily == null) {
            List<String> available = availableFamilies();
            cjkFamily = "";
            for (String candidate : CJK_PREFERRED) {
                if (available.contains(candidate)) {
                    cjkFamily = candidate;
                    break;
                }
            }
        }
        return cjkFamily;
    }

    public static Font bold(float size) {
        return new Font(family(), Font.BOLD, 12).deriveFont(size);
    }

    /**
     * Bold font guaranteed to contain every glyph in {@code text}: uses the
     * standard family when it can render the whole string, otherwise switches
     * to a CJK-capable family (needed for the Chinese menu labels).
     */
    public static Font boldFor(String text, float size) {
        Font base = bold(size);
        if (text == null || text.isEmpty() || base.canDisplayUpTo(text) < 0) {
            return base;
        }
        String cjk = cjkFamily();
        if (!cjk.isEmpty()) {
            Font f = new Font(cjk, Font.BOLD, 12).deriveFont(size);
            if (f.canDisplayUpTo(text) < 0) {
                return f;
            }
        }
        return base;
    }

    private static List<String> availableFamilies() {
        return Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    }

    private Fonts() {
    }
}