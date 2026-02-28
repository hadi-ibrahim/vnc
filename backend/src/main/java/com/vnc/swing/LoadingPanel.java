package com.vnc.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;

public class LoadingPanel extends JPanel {

    private static final int SPINNER_SIZE = 48;
    private static final int STROKE_WIDTH = 5;
    private static final Color TRACK_COLOR = new Color(255, 255, 255, 25);
    private static final Color ARC_COLOR = new Color(100, 180, 255);

    private double angle;

    public LoadingPanel() {
        setBackground(new Color(25, 25, 40));

        Timer timer = new Timer(16, e -> {
            angle = (angle + 5) % 360;
            repaint();
        });
        timer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setPaint(new GradientPaint(
                0, 0, new Color(25, 25, 50),
                getWidth(), getHeight(), new Color(50, 25, 50)));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int cx = getWidth() / 2;
        int cy = getHeight() / 2 - 16;
        int half = SPINNER_SIZE / 2;

        g2.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(TRACK_COLOR);
        g2.drawOval(cx - half, cy - half, SPINNER_SIZE, SPINNER_SIZE);

        g2.setColor(ARC_COLOR);
        g2.draw(new Arc2D.Double(
                cx - half, cy - half, SPINNER_SIZE, SPINNER_SIZE,
                angle, 90, Arc2D.OPEN));

        g2.setColor(new Color(255, 255, 255, 140));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        String text = "Loading\u2026";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2, cy + half + 30);

        g2.dispose();
    }
}
