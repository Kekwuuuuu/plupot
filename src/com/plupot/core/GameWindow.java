package com.plupot.core;

import com.plupot.data.SaveData;
import com.plupot.ui.AppIcon;
import com.plupot.ui.MenuScreen;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;

public final class GameWindow extends JFrame {

    public GameWindow() {
        super("Plupot");

        SaveData save = new SaveData();
        GamePanel panel = new GamePanel(save);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(panel);
        setMinimumSize(new Dimension(720, 520));
        AppIcon.applyTo(this);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        panel.start(new MenuScreen(panel));
    }
}