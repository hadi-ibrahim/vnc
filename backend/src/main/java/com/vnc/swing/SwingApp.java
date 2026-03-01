package com.vnc.swing;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;

public class SwingApp {

    private final String title;
    private volatile JFrame frame;

    public SwingApp(String title) {
        this.title = title;
    }

    public void start() {
        try {
            SwingUtilities.invokeAndWait(this::createAndShowGui);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while creating Swing GUI", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Failed to create Swing GUI", e.getCause());
        }
    }

    private void createAndShowGui() {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);
        frame.setLayout(new BorderLayout());

        AnimatedPanel animatedPanel = new AnimatedPanel();
        frame.add(animatedPanel, BorderLayout.CENTER);

        JPanel controlPanel = buildControlPanel(animatedPanel);
        frame.add(controlPanel, BorderLayout.SOUTH);

        frame.getContentPane().setPreferredSize(new Dimension(1280, 720));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildControlPanel(AnimatedPanel animatedPanel) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        panel.setBackground(new Color(45, 45, 45));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(80, 80, 80)));

        JButton colorBtn = styledButton("Change Color");
        colorBtn.addActionListener(e -> animatedPanel.cycleColor());

        JButton resetBtn = styledButton("Reset");
        resetBtn.addActionListener(e -> animatedPanel.reset());

        JButton speedBtn = styledButton("Toggle Speed");
        speedBtn.addActionListener(e -> animatedPanel.toggleSpeed());

        JTextField textField = new JTextField("Hello VNC!", 20);
        textField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        textField.setBackground(new Color(60, 60, 60));
        textField.setForeground(Color.WHITE);
        textField.setCaretColor(Color.WHITE);
        textField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(100, 100, 100)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        panel.add(colorBtn);
        panel.add(resetBtn);
        panel.add(speedBtn);
        panel.add(textField);
        return panel;
    }

    private static JButton styledButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 13));
        btn.setBackground(new Color(70, 130, 180));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public JFrame getFrame() {
        return frame;
    }

    public void stop() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.setVisible(false);
                frame.dispose();
            }
        });
    }
}
