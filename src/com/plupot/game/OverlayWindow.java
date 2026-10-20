package com.plupot.game;

import com.plupot.core.GameContext;
import com.plupot.ui.Palette;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * Borderless, full-screen, always-on-top window whose empty regions are
 * per-pixel transparent — the user's desktop shows through, except where
 * OverlayPanel paints the C1 road.
 *
 * Not exclusive fullscreen (that mode cannot be transparent); we instead
 * size an undecorated window to the whole screen and pin it on top.
 */
public final class OverlayWindow extends JFrame {

    public OverlayWindow(GameContext ctx) {
        super("Plupot");

        setUndecorated(true);
        setResizable(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        boolean perPixel = isPerPixelTranslucencySupported();
        if (perPixel) {
            // Must be set before the window is first shown for per-pixel
            // translucency to take effect.
            setBackground(new Color(0, 0, 0, 0));
        } else {
            System.err.println(
                    "[Plupot] Per-pixel translucency is not supported on this system. "
                            + "The overlay will appear as a solid dark window.");
            setBackground(Palette.C1);
        }

        GraphicsDevice device = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        setBounds(bounds);

        setAlwaysOnTop(true);

        OverlayPanel panel = new OverlayPanel(ctx, perPixel);
        setContentPane(panel);

        // No cursor while the overlay is up — this is the game.
        setCursor(invisibleCursor());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                panel.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                // Alt+F4 should *close the overlay*, not kill the app.
                ctx.closeOverlay();
            }
        });
    }

    private static boolean isPerPixelTranslucencySupported() {
        GraphicsDevice gd = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice();
        return gd.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    private static Cursor invisibleCursor() {
        BufferedImage blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        return Toolkit.getDefaultToolkit()
                .createCustomCursor(blank, new Point(0, 0), "invisible");
    }
}