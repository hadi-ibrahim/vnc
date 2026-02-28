package com.vnc.service;

import com.vnc.swing.SwingApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CaptureService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    private static final int FPS = 20;
    private static final long CAPTURE_INTERVAL_MS = 1000 / FPS;

    private final SwingApp swingApp;
    private final BroadcastService broadcastService;
    private final H264EncoderService encoder;
    private final AtomicBoolean capturing = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private BufferedImage captureBuffer;
    private volatile boolean running;

    public CaptureService(SwingApp swingApp, BroadcastService broadcastService, H264EncoderService encoder) {
        this.swingApp = swingApp;
        this.broadcastService = broadcastService;
        this.encoder = encoder;
    }

    @Override
    public void start() {
        captureBuffer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        encoder.start(WIDTH, HEIGHT, FPS);

        byte[] config = encoder.getCodecConfig();
        if (config != null) {
            broadcastService.setCodecConfig(config);
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vnc-capture");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::captureAndBroadcast, 200, CAPTURE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Capture started – {}ms interval, H.264 encoding", CAPTURE_INTERVAL_MS);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        encoder.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 1;
    }

    private void captureAndBroadcast() {
        if (!capturing.compareAndSet(false, true)) return;
        try {
            if (!broadcastService.hasClients()) return;

            JFrame frame = swingApp.getFrame();
            if (frame == null || !frame.isVisible()) return;

            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = captureBuffer.createGraphics();
                frame.getContentPane().paint(g);
                g.dispose();
            });

            byte[] encoded = encoder.encode(captureBuffer);
            if (encoded == null) return;

            boolean keyframe = encoder.isLastFrameKeyframe();
            long timestamp = encoder.getTimestamp();
            broadcastService.broadcastFrame(encoded, keyframe, timestamp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Capture error", e);
        } finally {
            capturing.set(false);
        }
    }
}
