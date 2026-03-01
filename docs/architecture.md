# Architecture Overview

## System Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                      Spring Boot JVM                          │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │  AppRegistry (@Service, SmartLifecycle phase 1)      │    │
│  │                                                      │    │
│  │  ┌─────────────────────────────────────────────┐     │    │
│  │  │  AppInstance (id=1, "Bouncing Balls")        │     │    │
│  │  │  ┌──────────┐  ┌───────────┐  ┌──────────┐ │     │    │
│  │  │  │ SwingApp │  │ H264Enc.  │  │Broadcast │ │     │    │
│  │  │  └──────────┘  └───────────┘  └──────────┘ │     │    │
│  │  │  ┌──────────┐  ┌───────────┐  ┌──────────┐ │     │    │
│  │  │  │RemoteCtrl│  │ LockServ. │  │ Capture  │ │     │    │
│  │  │  └──────────┘  └───────────┘  │  Thread  │ │     │    │
│  │  │                               └──────────┘ │     │    │
│  │  └─────────────────────────────────────────────┘     │    │
│  │  ┌─────────────────────────────────────────────┐     │    │
│  │  │  AppInstance (id=2, "Bouncing Balls 2")      │     │    │
│  │  │  ... (same per-app services) ...             │     │    │
│  │  └─────────────────────────────────────────────┘     │    │
│  │  ┌─────────────────────────────────────────────┐     │    │
│  │  │  AppInstance (id=3, "Bouncing Balls 3")      │     │    │
│  │  │  ... (same per-app services) ...             │     │    │
│  │  └─────────────────────────────────────────────┘     │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌──────────────────┐   ┌──────────────────────────────┐    │
│  │ AppController    │   │ VncWebSocketHandler           │    │
│  │ GET /api/apps    │   │ /ws/{appId}                   │    │
│  │ (REST)           │   │ routes to correct AppInstance │    │
│  └──────────────────┘   └──────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
         │ REST + WSS
         ▼
┌──────────────────────────────────────────────────────────────┐
│                    Browser (Angular 19)                        │
│                                                              │
│  Route "/"                    Route "/app/:id"               │
│  ┌──────────────────┐        ┌──────────────────────────┐   │
│  │ScreenManager     │───────▶│ ViewerComponent          │   │
│  │ fetches /api/apps│        │ connects to /ws/{id}     │   │
│  │ card grid        │◀───────│ VncCanvasComponent       │   │
│  └──────────────────┘  back  │ WebCodecs VideoDecoder   │   │
│                              └──────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

### Backend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `VncApplication`       | Entry point. Disables AWT headless mode for Swing.     |
| `AppRegistry`          | `@Service`, `SmartLifecycle` (phase 1). Creates and manages multiple `AppInstance` objects. Provides lookup by ID and listing for the REST API. |
| `AppInstance`           | Per-app POJO bundling: `SwingApp`, `H264EncoderService`, `BroadcastService`, `ControlLockService`, `RemoteControlService`, and a capture loop thread. |
| `SwingApp`             | Creates a JFrame with a given title on the EDT. Not a Spring bean — instantiated per-app. |
| `AnimatedPanel`        | 60 FPS Swing Timer animation with bouncing balls.      |
| `H264EncoderService`   | Wraps FFmpeg's H.264 encoder via JavaCV. Per-app instance. |
| `BroadcastService`     | Per-app client session registry. Sends binary H.264 frames + codec config, JSON text for lock status. |
| `ControlLockService`   | Per-app single-controller lock via CAS on AtomicReference. |
| `RemoteControlService` | Per-app `getSnapshot()`, `click(x,y)`, `press(key)`. EDT-safe. |
| `AppController`        | `@RestController`. `GET /api/apps` returns the list of available apps. |
| `VncWebSocketHandler`  | `@Component`. Registered at `/ws/*`. Extracts app ID from the WebSocket path, looks up the correct `AppInstance`, routes all messages to that instance's services. |
| `WebSocketConfig`      | Registers handler at `/ws/*`. Sets buffer and timeout limits. |

### Frontend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `AppComponent`         | Router shell (`<router-outlet>`).                      |
| `ScreenManagerComponent` | Fetches `GET /api/apps`, displays a card grid of available apps. Links to `/app/:id`. |
| `ViewerComponent`      | Reads `:id` from route, calls `vncService.connect(id)`, provides header (back button, status, lock), embeds `VncCanvasComponent`. |
| `VncCanvasComponent`   | Canvas renderer. Uses WebCodecs `VideoDecoder` for H.264 decoding. Click/key forwarding. |
| `VncService`           | WebSocket lifecycle with app ID, binary H.264 frame parsing, codec config handling, auto-reconnect, Angular signals. |

## Data Flow

### App Discovery (REST)

```
ScreenManagerComponent
    │
    ├── fetch('GET /api/apps')
    │
    ▼
AppController.listApps()
    │
    └── AppRegistry.listApps()
        └── Returns [{id: "1", name: "Bouncing Balls"}, ...]
```

### Frame Pipeline (Server → Clients)

```
EDT repaint (60 FPS per SwingApp)
    │
    ▼
AppInstance.captureAndBroadcast() — per-app capture thread, every 50ms
    │
    ├── SwingUtilities.invokeAndWait()  → paint content pane into BufferedImage
    │
    ├── H264EncoderService.encode(bufferedImage)
    │   └── Returns encoded byte[] + isKeyframe flag
    │
    └── BroadcastService.broadcastFrame(encoded, isKeyframe, timestamp)
        └── Sends to all clients connected to THIS app only
```

### WebSocket Connection

```
Browser navigates to /app/2
    │
    ├── VncService.connect("2")
    │   └── WebSocket to ws://host/ws/2
    │
    ▼
VncWebSocketHandler.afterConnectionEstablished()
    │
    ├── Extract appId "2" from URI path
    ├── AppRegistry.get("2") → AppInstance
    ├── Store session → AppInstance mapping
    └── AppInstance.getBroadcastService().addClient(session)
```

### Control Pipeline (Client → Server)

```
Browser canvas click/keydown
    │
    ├── VncService.sendClick(x, y) / sendKey(key)
    │   └── WebSocket.send(JSON text)
    │
    ▼
VncWebSocketHandler.handleTextMessage()
    │
    ├── Look up AppInstance for this session
    ├── Check: app.getControlLockService().isController(sessionId)?
    │
    └── app.getRemoteControlService().click(x, y) / press(key)
        └── SwingUtilities.invokeLater()  → dispatch to the correct JFrame
```

### Lock Protocol

Each app has its own independent `ControlLockService`. A client can hold the lock on App 1 while a different client holds the lock on App 2.

```
Client sends:   { "type": "lock" }  (on /ws/2)
    │
    ▼
Server: app2.getControlLockService().tryLock(sessionId)
    └── broadcast lockStatus to all clients connected to app 2 only
```

## Lifecycle Ordering

Spring `SmartLifecycle` phases control startup order:

| Phase | Component       | What Happens                                 |
|-------|-----------------|----------------------------------------------|
| 1     | `AppRegistry`   | Creates 3 `AppInstance` objects, each starting its own `SwingApp` (EDT), `H264EncoderService`, and capture thread |

Each `AppInstance.start()`:
1. `SwingApp.start()` → `invokeAndWait()` → JFrame created and visible
2. `H264EncoderService.start()` → encoder initialized
3. Capture thread scheduled at 50ms intervals

## Bandwidth Comparison

```
Old (JPEG tiles):   30 KB/frame × 20 FPS = 600 KB/s/client  (20 clients = 12 MB/s)
New (H.264):        ~2 KB/frame × 20 FPS =  40 KB/s/client  (20 clients = 800 KB/s)
```

With 3 apps, each having its own independent encoder, the total server bandwidth is proportional to the number of apps with active viewers.
