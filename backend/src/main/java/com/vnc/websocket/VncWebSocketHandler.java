package com.vnc.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnc.model.LockStatusMessage;
import com.vnc.service.AppInstance;
import com.vnc.service.AppRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class VncWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VncWebSocketHandler.class);

    private final AppRegistry appRegistry;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, AppInstance> sessionToApp = new ConcurrentHashMap<>();

    public VncWebSocketHandler(AppRegistry appRegistry, ObjectMapper objectMapper) {
        this.appRegistry = appRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String appId = extractAppId(session);
        AppInstance app = appRegistry.get(appId);
        if (app == null) {
            log.warn("Client {} connected to unknown app '{}'", session.getId(), appId);
            try { session.close(CloseStatus.BAD_DATA); } catch (Exception ignored) {}
            return;
        }

        sessionToApp.put(session.getId(), app);
        log.info("Client {} connected to app '{}'", session.getId(), appId);
        app.getBroadcastService().addClient(session.getId(), session);
        sendLockStatusTo(session.getId(), app);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AppInstance app = sessionToApp.remove(session.getId());
        if (app == null) return;

        log.info("Client {} disconnected from app '{}' ({})", session.getId(), app.getId(), status);
        app.getBroadcastService().removeClient(session.getId());
        if (app.getControlLockService().unlock(session.getId())) {
            broadcastLockStatusToAll(app);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        AppInstance app = sessionToApp.get(session.getId());
        if (app == null) return;

        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.has("type") ? node.get("type").asText() : "";

        switch (type) {
            case "click" -> {
                if (app.getControlLockService().isController(session.getId())) {
                    int x = node.get("x").asInt();
                    int y = node.get("y").asInt();
                    app.getRemoteControlService().click(x, y);
                }
            }
            case "key" -> {
                if (app.getControlLockService().isController(session.getId())) {
                    String keyStr = node.get("key").asText();
                    if (!keyStr.isEmpty()) {
                        app.getRemoteControlService().press(keyStr.charAt(0));
                    }
                }
            }
            case "lock" -> {
                if (app.getControlLockService().tryLock(session.getId())) {
                    broadcastLockStatusToAll(app);
                }
            }
            case "unlock" -> {
                if (app.getControlLockService().unlock(session.getId())) {
                    broadcastLockStatusToAll(app);
                }
            }
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for {}: {}", session.getId(), exception.getMessage());
    }

    private void sendLockStatusTo(String sessionId, AppInstance app) {
        boolean locked = app.getControlLockService().isLocked();
        boolean isController = app.getControlLockService().isController(sessionId);
        app.getBroadcastService().sendTo(sessionId, LockStatusMessage.of(locked, isController));
    }

    private void broadcastLockStatusToAll(AppInstance app) {
        boolean locked = app.getControlLockService().isLocked();
        for (String id : app.getBroadcastService().getClientIds()) {
            boolean isController = app.getControlLockService().isController(id);
            app.getBroadcastService().sendTo(id, LockStatusMessage.of(locked, isController));
        }
    }

    private String extractAppId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return "";
        String path = uri.getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
