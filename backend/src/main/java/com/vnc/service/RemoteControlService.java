package com.vnc.service;

import com.vnc.swing.SwingApp;
import com.vnc.util.JpegCodec;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

@Service
public class RemoteControlService {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final float JPEG_QUALITY = 0.6f;

    private final SwingApp swingApp;

    public RemoteControlService(SwingApp swingApp) {
        this.swingApp = swingApp;
    }

    public byte[] getSnapshot() {
        JFrame frame = swingApp.getFrame();
        if (frame == null) {
            return new byte[0];
        }
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        try {
            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = image.createGraphics();
                frame.getContentPane().paint(g);
                g.dispose();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (InvocationTargetException e) {
            return new byte[0];
        }
        return JpegCodec.encode(image, JPEG_QUALITY);
    }

    public void click(int x, int y) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = swingApp.getFrame();
            if (frame == null) return;

            Container contentPane = frame.getContentPane();
            Component target = SwingUtilities.getDeepestComponentAt(contentPane, x, y);
            if (target == null) target = contentPane;

            Point local = SwingUtilities.convertPoint(contentPane, x, y, target);
            long now = System.currentTimeMillis();

            target.dispatchEvent(new MouseEvent(
                    target, MouseEvent.MOUSE_PRESSED, now, 0,
                    local.x, local.y, 1, false, MouseEvent.BUTTON1));
            target.dispatchEvent(new MouseEvent(
                    target, MouseEvent.MOUSE_RELEASED, now, 0,
                    local.x, local.y, 1, false, MouseEvent.BUTTON1));
            target.dispatchEvent(new MouseEvent(
                    target, MouseEvent.MOUSE_CLICKED, now, 0,
                    local.x, local.y, 1, false, MouseEvent.BUTTON1));
        });
    }

    public void press(char key) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = swingApp.getFrame();
            if (frame == null) return;

            Component target = frame.getFocusOwner();
            if (target == null) target = frame.getContentPane();

            long now = System.currentTimeMillis();
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(key);

            target.dispatchEvent(new KeyEvent(
                    target, KeyEvent.KEY_PRESSED, now, 0, keyCode, key));
            target.dispatchEvent(new KeyEvent(
                    target, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, key));
            target.dispatchEvent(new KeyEvent(
                    target, KeyEvent.KEY_RELEASED, now, 0, keyCode, key));
        });
    }
}
