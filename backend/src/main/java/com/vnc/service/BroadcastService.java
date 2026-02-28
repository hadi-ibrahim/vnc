package com.vnc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private final ConcurrentMap<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile TextMessage lastFullFrame;

    public BroadcastService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void addClient(String id, WebSocketSession session) {
        clients.put(id, new ClientSession(session));
        TextMessage cached = lastFullFrame;
        if (cached != null) {
            sendExecutor.submit(() -> {
                try {
                    synchronized (session) {
                        session.sendMessage(cached);
                    }
                } catch (IOException e) {
                    log.debug("Failed to send initial frame to {}", id);
                }
            });
        }
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

    public void broadcast(Object message, boolean isFullFrame) {
        if (clients.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize frame message", e);
            return;
        }

        TextMessage textMessage = new TextMessage(json);
        if (isFullFrame) {
            lastFullFrame = textMessage;
        }

        clients.forEach((id, client) -> {
            if (client.inFlight.compareAndSet(false, true)) {
                sendExecutor.submit(() -> {
                    try {
                        synchronized (client.session) {
                            client.session.sendMessage(textMessage);
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
