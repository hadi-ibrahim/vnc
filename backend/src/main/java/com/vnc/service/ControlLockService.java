package com.vnc.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class ControlLockService {

    private final AtomicReference<String> controllerId = new AtomicReference<>();

    public boolean tryLock(String sessionId) {
        return controllerId.compareAndSet(null, sessionId);
    }

    public boolean unlock(String sessionId) {
        return controllerId.compareAndSet(sessionId, null);
    }

    public boolean isController(String sessionId) {
        return sessionId.equals(controllerId.get());
    }

    public boolean isLocked() {
        return controllerId.get() != null;
    }
}
