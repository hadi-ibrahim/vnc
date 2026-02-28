package com.vnc.service;

import com.vnc.model.FrameMessage;
import com.vnc.model.TileData;
import com.vnc.swing.SwingApp;
import com.vnc.util.JpegCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CaptureService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int TILE_SIZE = 32;
    static final int COLS = WIDTH / TILE_SIZE;
    static final int ROWS = (HEIGHT + TILE_SIZE - 1) / TILE_SIZE;
    private static final float FULL_FRAME_THRESHOLD = 0.4f;
    private static final float JPEG_QUALITY = 0.6f;
    private static final long CAPTURE_INTERVAL_MS = 33;
    private static final int FORCE_FULL_INTERVAL = 60;

    private final SwingApp swingApp;
    private final BroadcastService broadcastService;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final int[] currentPixels = new int[TILE_SIZE * TILE_SIZE];
    private final int[] previousPixels = new int[TILE_SIZE * TILE_SIZE];

    private ScheduledExecutorService scheduler;
    private BufferedImage currentFrame;
    private BufferedImage previousFrame;
    private volatile boolean running;
    private int frameCount;

    public CaptureService(SwingApp swingApp, BroadcastService broadcastService) {
        this.swingApp = swingApp;
        this.broadcastService = broadcastService;
    }

    @Override
    public void start() {
        currentFrame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        previousFrame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vnc-capture");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::captureAndBroadcast, 200, CAPTURE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Capture started – {}ms interval, {}x{} tiles",
                CAPTURE_INTERVAL_MS, COLS, ROWS);
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

            BufferedImage temp = previousFrame;
            previousFrame = currentFrame;
            currentFrame = temp;

            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = currentFrame.createGraphics();
                frame.getContentPane().paint(g);
                g.dispose();
            });

            frameCount++;
            boolean forceFullFrame = frameCount % FORCE_FULL_INTERVAL == 0;

            List<TileData> changedTiles = new ArrayList<>();
            int totalTiles = COLS * ROWS;

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int tx = col * TILE_SIZE;
                    int ty = row * TILE_SIZE;
                    int tw = Math.min(TILE_SIZE, WIDTH - tx);
                    int th = Math.min(TILE_SIZE, HEIGHT - ty);

                    if (isTileChanged(tx, ty, tw, th)) {
                        changedTiles.add(encodeTile(col, row, tx, ty, tw, th));
                    }
                }
            }

            boolean full = forceFullFrame
                    || (float) changedTiles.size() / totalTiles > FULL_FRAME_THRESHOLD;

            List<TileData> tilesToSend;
            if (full) {
                tilesToSend = encodeAllTiles();
            } else if (!changedTiles.isEmpty()) {
                tilesToSend = changedTiles;
            } else {
                return;
            }

            broadcastService.broadcast(new FrameMessage(full, tilesToSend), full);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Capture error", e);
        } finally {
            capturing.set(false);
        }
    }

    private boolean isTileChanged(int tx, int ty, int tw, int th) {
        int count = tw * th;
        currentFrame.getRGB(tx, ty, tw, th, currentPixels, 0, tw);
        previousFrame.getRGB(tx, ty, tw, th, previousPixels, 0, tw);
        return Arrays.mismatch(currentPixels, 0, count, previousPixels, 0, count) >= 0;
    }

    private TileData encodeTile(int col, int row, int tx, int ty, int tw, int th) {
        BufferedImage tile = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.drawImage(currentFrame, 0, 0, tw, th, tx, ty, tx + tw, ty + th, null);
        g.dispose();
        byte[] jpeg = JpegCodec.encode(tile, JPEG_QUALITY);
        return new TileData(col, row, Base64.getEncoder().encodeToString(jpeg));
    }

    private List<TileData> encodeAllTiles() {
        List<TileData> tiles = new ArrayList<>(COLS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int tx = col * TILE_SIZE;
                int ty = row * TILE_SIZE;
                int tw = Math.min(TILE_SIZE, WIDTH - tx);
                int th = Math.min(TILE_SIZE, HEIGHT - ty);
                tiles.add(encodeTile(col, row, tx, ty, tw, th));
            }
        }
        return tiles;
    }
}
