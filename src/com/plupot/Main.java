package com.plupot;

import com.plupot.core.GameWindow;

import javax.swing.SwingUtilities;

public final class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GameWindow::new);
    }

    private Main() {
    }
}