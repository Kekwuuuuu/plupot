package com.plupot.core;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;

/** Base class for every screen (menu, game, ...). */
public abstract class Screen {

    protected final GameContext ctx;

    protected Screen(GameContext ctx) {
        this.ctx = ctx;
    }

    public void onEnter() {
    }

    public void onExit() {
    }

    public abstract void update(double dt);

    public abstract void render(Graphics2D g, int width, int height);

    public void onKeyPressed(int keyCode) {
    }

    public void onKeyReleased(int keyCode) {
    }

    /** Convenience for subclasses. */
    protected boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_SPACE;
    }
}