package com.vnc.swing;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class AnimatedPanel extends JPanel {

    private static final int BALL_RADIUS = 25;
    private static final Color[] PALETTE = {
            new Color(0, 200, 255),
            new Color(255, 100, 100),
            new Color(100, 255, 100),
            new Color(255, 200, 50),
            new Color(200, 100, 255),
            new Color(255, 150, 200)
    };

    private final List<Ball> balls = new ArrayList<>();
    private int colorIndex;
    private boolean fast;

    public AnimatedPanel() {
        setBackground(new Color(25, 25, 40));
        resetBalls();

        Timer timer = new Timer(16, e -> {
            updateBalls();
            repaint();
        });
        timer.start();
    }

    private void resetBalls() {
        balls.clear();
        balls.add(new Ball(200, 150, 3.0, 2.0, PALETTE[0]));
        balls.add(new Ball(500, 300, -2.5, 3.0, PALETTE[1]));
        balls.add(new Ball(800, 200, 2.0, -2.5, PALETTE[2]));
    }

    private void updateBalls() {
        double speed = fast ? 2.0 : 1.0;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (Ball b : balls) {
            b.x += b.vx * speed;
            b.y += b.vy * speed;
            if (b.x - BALL_RADIUS < 0) {
                b.x = BALL_RADIUS;
                b.vx = Math.abs(b.vx);
            }
            if (b.x + BALL_RADIUS > w) {
                b.x = w - BALL_RADIUS;
                b.vx = -Math.abs(b.vx);
            }
            if (b.y - BALL_RADIUS < 0) {
                b.y = BALL_RADIUS;
                b.vy = Math.abs(b.vy);
            }
            if (b.y + BALL_RADIUS > h) {
                b.y = h - BALL_RADIUS;
                b.vy = -Math.abs(b.vy);
            }
        }
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

        g2.setColor(new Color(255, 255, 255, 15));
        for (int x = 0; x < getWidth(); x += 40) g2.drawLine(x, 0, x, getHeight());
        for (int y = 0; y < getHeight(); y += 40) g2.drawLine(0, y, getWidth(), y);

        for (Ball b : balls) {
            g2.setColor(new Color(b.color.getRed(), b.color.getGreen(), b.color.getBlue(), 40));
            g2.fillOval((int) (b.x - BALL_RADIUS * 2), (int) (b.y - BALL_RADIUS * 2),
                    BALL_RADIUS * 4, BALL_RADIUS * 4);

            g2.setColor(b.color);
            g2.fillOval((int) (b.x - BALL_RADIUS), (int) (b.y - BALL_RADIUS),
                    BALL_RADIUS * 2, BALL_RADIUS * 2);

            g2.setColor(new Color(255, 255, 255, 80));
            int hx = (int) (b.x - BALL_RADIUS * 0.4);
            int hy = (int) (b.y - BALL_RADIUS * 0.6);
            g2.fillOval(hx, hy, (int) (BALL_RADIUS * 0.7), (int) (BALL_RADIUS * 0.45));
        }

        g2.setColor(new Color(255, 255, 255, 120));
        g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g2.drawString("Balls: " + balls.size() + " | Speed: " + (fast ? "Fast" : "Normal"), 10, 20);
        g2.dispose();
    }

    public void cycleColor() {
        colorIndex = (colorIndex + 1) % PALETTE.length;
        for (int i = 0; i < balls.size(); i++) {
            balls.get(i).color = PALETTE[(colorIndex + i) % PALETTE.length];
        }
    }

    public void reset() {
        resetBalls();
        colorIndex = 0;
        fast = false;
    }

    public void toggleSpeed() {
        fast = !fast;
    }

    private static class Ball {
        double x, y, vx, vy;
        Color color;

        Ball(double x, double y, double vx, double vy, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
        }
    }
}
