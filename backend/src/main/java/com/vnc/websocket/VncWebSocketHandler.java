package com.vnc.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vnc.model.LockStatusMessage;
import com.vnc.service.BroadcastService;
import com.vnc.service.ControlLockService;
import com.vnc.service.RemoteControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class VncWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VncWebSocketHandler.class);

    private final BroadcastService broadcastService;
    private final RemoteControlService remoteControlService;
    private final ControlLockService controlLockService;
    private final ObjectMapper objectMapper;

    public VncWebSocketHandler(BroadcastService broadcastService,
                               RemoteControlService remoteControlService,
                               ControlLockService controlLockService,
                               ObjectMapper objectMapper) {
        this.broadcastService = broadcastService;
        this.remoteControlService = remoteControlService;
        this.controlLockService = controlLockService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Client connected: {}", session.getId());
        broadcastService.addClient(session.getId(), session);
        sendLockStatusTo(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Client disconnected: {} ({})", session.getId(), status);
        broadcastService.removeClient(session.getId());
        if (controlLockService.unlock(session.getId())) {
            broadcastLockStatusToAll();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.has("type") ? node.get("type").asText() : "";

        switch (type) {
            case "click" -> {
                if (controlLockService.isController(session.getId())) {
                    int x = node.get("x").asInt();
                    int y = node.get("y").asInt();
                    remoteControlService.click(x, y);
                }
            }
            case "key" -> {
                if (controlLockService.isController(session.getId())) {
                    String keyStr = node.get("key").asText();
                    if (!keyStr.isEmpty()) {
                        remoteControlService.press(keyStr.charAt(0));
                    }
                }
            }
            case "lock" -> {
                if (controlLockService.tryLock(session.getId())) {
                    broadcastLockStatusToAll();
                }
            }
            case "unlock" -> {
                if (controlLockService.unlock(session.getId())) {
                    broadcastLockStatusToAll();
                }
            }
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error for {}: {}", session.getId(), exception.getMessage());
    }

    private void sendLockStatusTo(String sessionId) {
        boolean locked = controlLockService.isLocked();
        boolean isController = controlLockService.isController(sessionId);
        broadcastService.sendTo(sessionId, LockStatusMessage.of(locked, isController));
    }

    private void broadcastLockStatusToAll() {
        boolean locked = controlLockService.isLocked();
        for (String id : broadcastService.getClientIds()) {
            boolean isController = controlLockService.isController(id);
            broadcastService.sendTo(id, LockStatusMessage.of(locked, isController));
        }
    }
}
