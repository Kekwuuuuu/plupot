package com.plupot.core;

/** Everything a Screen is allowed to ask the game to do. */
public interface GameContext {

    int getHighScore();

    void setHighScore(int value);

    void showScreen(Screen screen);

    /** Trigger the white flash. */
    void flash();

    /** Trigger the white flash, then run {@code action} once the flash has fully faded. */
    void flashAndThen(Runnable action);

    /** Hide the menu, open the fullscreen transparent overlay. */
    void openOverlay();

    /** Close the overlay (if open) and restore the menu. */
    void closeOverlay();

    void quit();
}