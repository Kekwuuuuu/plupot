package com.plupot.core;

import com.plupot.data.SaveData;
import com.plupot.game.OverlayWindow;
import com.plupot.ui.FlashEffect;
import com.plupot.ui.Palette;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;

/**
 * The game loop. Owns the current screen, the flash overlay and all input.
 * Also owns the lifecycle of the fullscreen transparent gameplay overlay.
 */
public final class GamePanel extends JPanel implements GameContext, ActionListener {

    private static final int FRAME_MILLIS = 16;
    private static final double MAX_DT = 0.1;

    private static final int[] BOUND_KEYS = {
            KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D,
            KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE
    };

    private final SaveData save;
    private final FlashEffect flashEffect = new FlashEffect();
    private final Timer timer;
    private final Set<Integer> heldKeys = new HashSet<>();

    private Screen screen;
    private long lastNanos;

    /** Deferred action to run once the flash finishes. */
    private Runnable pendingFlashAction;

    /** Non-null while the fullscreen overlay is open. */
    private OverlayWindow overlayWindow;

    public GamePanel(SaveData save) {
        this.save = save;

        setBackground(Palette.C1);
        setOpaque(true);
        setFocusable(true);
        setPreferredSize(new Dimension(1024, 720));

        bindKeys();

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                heldKeys.clear();
            }
        });

        timer = new Timer(FRAME_MILLIS, this);
        timer.setCoalesce(true);
    }

    public void start(Screen initial) {
        showScreen(initial);
        lastNanos = System.nanoTime();
        timer.start();
        requestFocusInWindow();
    }

    // ---------------------------------------------------------------- loop

    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt > MAX_DT) {
            dt = MAX_DT;
        }

        if (screen != null) {
            screen.update(dt);
        }
        flashEffect.update(dt);

        if (pendingFlashAction != null && flashEffect.isFinished()) {
            Runnable r = pendingFlashAction;
            pendingFlashAction = null;
            SwingUtilities.invokeLater(r);
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int w = getWidth();
        int h = getHeight();

        g.setColor(Palette.C1);
        g.fillRect(0, 0, w, h);

        if (screen != null) {
            screen.render(g, w, h);
        }

        float alpha = flashEffect.alpha();
        if (alpha > 0f) {
            g.setColor(new Color(1f, 1f, 1f, alpha));
            g.fillRect(0, 0, w, h);
        }

        g.dispose();
    }

    // -------------------------------------------------------------- context

    @Override
    public int getHighScore() {
        return save.getHighScore();
    }

    @Override
    public void setHighScore(int value) {
        save.setHighScore(value);
    }

    @Override
    public void showScreen(Screen next) {
        if (screen != null) {
            screen.onExit();
        }
        screen = next;
        if (screen != null) {
            screen.onEnter();
        }
    }

    @Override
    public void flash() {
        flashEffect.trigger();
    }

    @Override
    public void flashAndThen(Runnable action) {
        this.pendingFlashAction = action;
        flashEffect.trigger();
    }

    @Override
    public void openOverlay() {
        if (overlayWindow != null) {
            return;
        }

        // Pause our own loop — the menu is about to be hidden.
        timer.stop();

        // Hide the menu so its opaque content doesn't show through
        // the transparent parts of the overlay.
        Window menu = SwingUtilities.getWindowAncestor(this);
        if (menu != null) {
            menu.setVisible(false);
        }

        overlayWindow = new OverlayWindow(this);
        overlayWindow.setVisible(true);
        overlayWindow.toFront();
        overlayWindow.requestFocus();
    }

    @Override
    public void closeOverlay() {
        if (overlayWindow == null) {
            return;
        }
        overlayWindow.dispose();
        overlayWindow = null;

        Window menu = SwingUtilities.getWindowAncestor(this);
        if (menu != null) {
            menu.setVisible(true);
            menu.toFront();
            // Treat re-showing the menu as a fresh "enter" so screens can
            // reset any transient state (e.g. MenuScreen's busy flag).
            if (screen != null) {
                screen.onEnter();
            }
            SwingUtilities.invokeLater(this::requestFocusInWindow);
        }

        // Resume the loop; reset the clock so dt doesn't spike.
        lastNanos = System.nanoTime();
        timer.start();
    }

    @Override
    public void quit() {
        save.save();
        timer.stop();
        if (overlayWindow != null) {
            overlayWindow.dispose();
            overlayWindow = null;
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispose();
        }
        System.exit(0);
    }

    // ---------------------------------------------------------------- input

    private void bindKeys() {
        InputMap inputMap = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = getActionMap();

        for (int key : BOUND_KEYS) {
            String pressId = "press-" + key;
            inputMap.put(KeyStroke.getKeyStroke(key, 0, false), pressId);
            actionMap.put(pressId, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // heldKeys filters OS key-repeat so holding a key fires once.
                    if (heldKeys.add(key) && screen != null) {
                        screen.onKeyPressed(key);
                    }
                }
            });

            String releaseId = "release-" + key;
            inputMap.put(KeyStroke.getKeyStroke(key, 0, true), releaseId);
            actionMap.put(releaseId, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    heldKeys.remove(key);
                    if (screen != null) {
                        screen.onKeyReleased(key);
                    }
                }
            });
        }
    }
}