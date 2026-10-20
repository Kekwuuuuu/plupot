package com.plupot.ui;

import java.awt.Color;

/**
 * The entire game uses exactly these three colors for UI.
 * Score text has a small tier palette on top of that.
 *   C1 - dark gray     (backgrounds, the road)
 *   C2 - deepseek blue (text, UI, player)
 *   C3 - matching red  (gameplay hazards, high score tier)
 */
public final class Palette {

    public static final Color C1 = new Color(0x1E1F22);
    public static final Color C2 = new Color(0x4D6BFE);
    public static final Color C3 = new Color(0xFF4D5A);

    /** Light gray - used for gameplay obstacle bars. */
    public static final Color LIGHT_GRAY = new Color(0xA0A6B0);

    /** White - used for obstacle text. */
    public static final Color WHITE = new Color(0xFFFFFF);

    // Score-only tier colors (private).
    private static final Color SCORE_DARKER_THAN_C1 = new Color(0x121316);
    private static final Color SCORE_YELLOW = new Color(0xFFD54D);
    private static final Color SCORE_ORANGE = new Color(0xFF9F43);

    /**
     * Highest score tier color:
     *       0 -  999    darker than C1
     *   1,000 - 3,499    light gray
     *   3,500 - 7,999    white
     *   8,000 - 14,999   yellow
     *  15,000 - 49,999   C3 (red)
     *  50,000+           orange
     */
    public static Color scoreColor(int score) {
        if (score < 1_000) {
            return SCORE_DARKER_THAN_C1;
        }
        if (score < 3_500) {
            return LIGHT_GRAY;
        }
        if (score < 8_000) {
            return WHITE;
        }
        if (score < 15_000) {
            return SCORE_YELLOW;
        }
        if (score < 50_000) {
            return C3;
        }
        return SCORE_ORANGE;
    }

    private Palette() {
    }
}