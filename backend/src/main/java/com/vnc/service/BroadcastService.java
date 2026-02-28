package com.vnc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private static final byte CONFIG_MARKER = (byte) 0xFF;

    private final ConcurrentMap<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile BinaryMessage cachedCodecConfig;
    private volatile BinaryMessage cachedKeyframe;

    public BroadcastService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setCodecConfig(byte[] config) {
        ByteBuffer buf = ByteBuffer.allocate(1 + config.length);
        buf.put(CONFIG_MARKER);
        buf.put(config);
        buf.flip();
        cachedCodecConfig = new BinaryMessage(buf);
    }

    public void addClient(String id, WebSocketSession session) {
        clients.put(id, new ClientSession(session));
        sendExecutor.submit(() -> {
            try {
                BinaryMessage config = cachedCodecConfig;
                if (config != null) {
                    synchronized (session) {
                        session.sendMessage(config);
                    }
                }
                BinaryMessage keyframe = cachedKeyframe;
                if (keyframe != null) {
                    synchronized (session) {
                        session.sendMessage(keyframe);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to send initial data to {}", id);
            }
        });
    }

    public void removeClient(String id) {
        clients.remove(id);
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public Set<String> getClientIds() {
        return Set.copyOf(clients.keySet());
    }

    public void broadcastFrame(byte[] h264Data, boolean keyframe, long timestampMs) {
        if (clients.isEmpty()) return;

        ByteBuffer buf = ByteBuffer.allocate(5 + h264Data.length);
        buf.put((byte) (keyframe ? 1 : 0));
        buf.putInt((int) timestampMs);
        buf.put(h264Data);
        buf.flip();

        BinaryMessage message = new BinaryMessage(buf);

        if (keyframe) {
            cachedKeyframe = message;
        }

        clients.forEach((id, client) -> {
            if (client.inFlight.compareAndSet(false, true)) {
                sendExecutor.submit(() -> {
                    try {
                        synchronized (client.session) {
                            client.session.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.debug("Send failed for client {}", id);
                    } finally {
                        client.inFlight.set(false);
                    }
                });
            }
        });
    }

    public void sendTo(String sessionId, Object message) {
        ClientSession client = clients.get(sessionId);
        if (client == null) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            return;
        }

        sendExecutor.submit(() -> {
            try {
                synchronized (client.session) {
                    client.session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.debug("Send failed for client {}", sessionId);
            }
        });
    }

    private static class ClientSession {
        final WebSocketSession session;
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        ClientSession(WebSocketSession session) {
            this.session = session;
        }
    }
}
