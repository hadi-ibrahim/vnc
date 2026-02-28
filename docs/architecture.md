# Architecture Overview

## System Diagram

```
┌─────────────────────────────────────────────────────┐
│                   Spring Boot JVM                    │
│                                                     │
│  ┌──────────┐     ┌────────────────┐                │
│  │ SwingApp │────▶│ AnimatedPanel  │  (EDT, 60 FPS) │
│  │ (JFrame) │     │ (bouncing balls│                │
│  └────┬─────┘     └────────────────┘                │
│       │ paint(g)                                    │
│       ▼                                             │
│  ┌──────────────┐    ┌──────────────────┐           │
│  │CaptureService│───▶│ BroadcastService │           │
│  │ (30 FPS loop)│    │ (virtual threads)│           │
│  └──────────────┘    └───────┬──────────┘           │
│       │                      │                      │
│       │ tile diff + JPEG     │ JSON over WSS        │
│       │                      ▼                      │
│  ┌────────────────┐  ┌──────────────────┐           │
│  │RemoteControl   │  │VncWebSocketHandler│◀── WSS   │
│  │Service         │  │  (routes msgs)   │           │
│  └────────────────┘  └──────────────────┘           │
│       ▲                      │                      │
│       │ click/key            │ lock/unlock           │
│       │                      ▼                      │
│       │              ┌──────────────────┐           │
│       └──────────────│ControlLockService│           │
│                      │ (AtomicReference)│           │
│                      └──────────────────┘           │
└─────────────────────────────────────────────────────┘
         │ WSS (JSON frames)
         ▼
┌─────────────────────────────────────────────────────┐
│              Browser (Angular 19)                    │
│                                                     │
│  ┌──────────┐    ┌───────────────────┐              │
│  │VncService│───▶│VncCanvasComponent │              │
│  │(WebSocket│    │(Canvas 1280x720)  │              │
│  │ signals) │    │ tile renderer     │              │
│  └──────────┘    │ double-buffered   │              │
│                  └───────────────────┘              │
└─────────────────────────────────────────────────────┘
```

## Component Responsibilities

### Backend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `VncApplication`       | Entry point. Disables AWT headless mode for Swing.     |
| `SwingApp`             | SmartLifecycle (phase 0). Creates JFrame on EDT.       |
| `AnimatedPanel`        | 60 FPS Swing Timer animation with bouncing balls.      |
| `CaptureService`       | SmartLifecycle (phase 1). 30 FPS scheduled capture, tile diffing, JPEG encoding. |
| `BroadcastService`     | Client session registry. Serializes once, sends to all via virtual threads. |
| `ControlLockService`   | Single-controller lock via CAS on AtomicReference.     |
| `RemoteControlService` | `getSnapshot()`, `click(x,y)`, `press(key)`. EDT-safe. |
| `VncWebSocketHandler`  | WSS endpoint. Routes incoming messages, manages connect/disconnect lifecycle. |
| `WebSocketConfig`      | Registers `/ws` handler. Sets buffer and timeout limits.|
| `JpegCodec`            | Stateless JPEG encoder with configurable quality.      |

### Frontend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `AppComponent`         | Top-level layout. Connection status, lock button.      |
| `VncCanvasComponent`   | Canvas renderer. Tile patching, double-buffered full frames, click/key forwarding. |
| `VncService`           | WebSocket lifecycle, auto-reconnect, Angular signals for reactive state. |

## Data Flow

### Frame Pipeline (Server → Clients)

```
EDT repaint (60 FPS)
    │
    ▼
CaptureService.captureAndBroadcast() — scheduled every 33ms
    │
    ├── SwingUtilities.invokeAndWait()  → paint content pane into BufferedImage
    │
    ├── Tile diff (32x32 grid, 40 cols × 23 rows = 920 tiles)
    │   └── Arrays.mismatch() on pre-allocated int[] buffers
    │
    ├── If >40% changed OR forced interval → full frame (encode all 920 tiles)
    │   Otherwise → diff frame (encode only changed tiles)
    │
    ├── JPEG encode each tile at quality 0.6 → Base64 string
    │
    └── BroadcastService.broadcast(FrameMessage, isFull)
        │
        ├── Serialize to JSON once (shared across all clients)
        ├── Cache if full frame (for new client init)
        │
        └── For each client:
            ├── AtomicBoolean.compareAndSet(false, true) → if fails, DROP frame
            └── Virtual thread: synchronized(session) { sendMessage() }
                └── finally: inFlight.set(false)
```

### Control Pipeline (Client → Server)

```
Browser canvas click/keydown
    │
    ├── VncService.sendClick(x, y) / sendKey(key)
    │   └── WebSocket.send(JSON)
    │
    ▼
VncWebSocketHandler.handleTextMessage()
    │
    ├── Check: controlLockService.isController(sessionId)?
    │   └── If not controller → silently ignored
    │
    └── RemoteControlService.click(x, y) / press(key)
        └── SwingUtilities.invokeLater()  → dispatch MouseEvent / KeyEvent on EDT
```

### Lock Protocol

```
Client sends:   { "type": "lock" }
    │
    ▼
Server: controlLockService.tryLock(sessionId)  — CAS(null → sessionId)
    │
    ├── Success → broadcast lockStatus to ALL clients
    │             (each gets personalized { you: true/false })
    │
    └── Failure → no response (another client holds the lock)

Client disconnect:
    │
    ▼
Server: controlLockService.unlock(sessionId)  — CAS(sessionId → null)
    └── broadcast lockStatus (unlocked) to remaining clients
```

## Lifecycle Ordering

Spring `SmartLifecycle` phases control startup order:

| Phase | Component       | What Happens                                 |
|-------|-----------------|----------------------------------------------|
| 0     | `SwingApp`      | `invokeAndWait()` → JFrame created and visible |
| 1     | `CaptureService`| `scheduleAtFixedRate()` → capture loop starts  |

The 200ms initial delay on the capture scheduler provides additional safety margin after the JFrame becomes visible.
