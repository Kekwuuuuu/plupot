package com.plupot.ui;

/** Quick white flash + fade back to transparent (the "screenshot" effect). */
public final class FlashEffect {

    private static final double UP_TIME = 0.06;    // fade in to full white
    private static final double HOLD_TIME = 0.03;  // stay white
    private static final double DOWN_TIME = 0.28;  // fade back out

    private double time = -1;

    public void trigger() {
        time = 0;
    }

    public boolean isActive() {
        return time >= 0;
    }

    /** True when no flash is playing (including never-triggered). */
    public boolean isFinished() {
        return time < 0;
    }

    public void update(double dt) {
        if (time < 0) {
            return;
        }
        time += dt;
        if (time > UP_TIME + HOLD_TIME + DOWN_TIME) {
            time = -1;
        }
    }

    /** 0..1 opacity of the white overlay. */
    public float alpha() {
        if (time < 0) {
            return 0f;
        }
        if (time < UP_TIME) {
            return (float) (time / UP_TIME);
        }
        if (time < UP_TIME + HOLD_TIME) {
            return 1f;
        }
        double down = (time - UP_TIME - HOLD_TIME) / DOWN_TIME;
        return (float) Math.max(0, 1.0 - down);
    }
}