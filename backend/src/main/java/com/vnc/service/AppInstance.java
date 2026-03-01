package com.vnc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnc.swing.SwingApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AppInstance {

    private static final Logger log = LoggerFactory.getLogger(AppInstance.class);

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 20;
    private static final long CAPTURE_INTERVAL_MS = 1000 / FPS;

    private final String id;
    private final String name;
    private final SwingApp swingApp;
    private final H264EncoderService encoder;
    private final BroadcastService broadcastService;
    private final ControlLockService controlLockService;
    private final RemoteControlService remoteControlService;
    private final AtomicBoolean capturing = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private BufferedImage captureBuffer;

    public AppInstance(String id, String name, ObjectMapper objectMapper) {
        this.id = id;
        this.name = name;
        this.swingApp = new SwingApp(name);
        this.encoder = new H264EncoderService();
        this.broadcastService = new BroadcastService(objectMapper);
        this.controlLockService = new ControlLockService();
        this.remoteControlService = new RemoteControlService(swingApp);
    }

    public void start() {
        swingApp.start();

        captureBuffer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        encoder.start(WIDTH, HEIGHT, FPS);

        byte[] config = encoder.getCodecConfig();
        if (config != null) {
            broadcastService.setCodecConfig(config);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vnc-capture-" + id);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::captureAndBroadcast, 200, CAPTURE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("App '{}' (id={}) started – {}ms capture interval", name, id, CAPTURE_INTERVAL_MS);
    }

    public void stop() {
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
        swingApp.stop();
        log.info("App '{}' (id={}) stopped", name, id);
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
            log.error("Capture error for app {}", id, e);
        } finally {
            capturing.set(false);
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public BroadcastService getBroadcastService() { return broadcastService; }
    public ControlLockService getControlLockService() { return controlLockService; }
    public RemoteControlService getRemoteControlService() { return remoteControlService; }
}
