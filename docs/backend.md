# Backend Documentation

## Technology Stack

- **Java 21** (virtual threads, records, pattern matching switch)
- **Spring Boot 3.3.5** (embedded Tomcat, WebSocket, Jackson)
- **Gradle 8.10** (Kotlin DSL)
- **JavaCV 1.5.11** (FFmpeg bindings — H.264 encoding via libopenh264/libx264)
- **HTTPS** with auto-generated self-signed PKCS12 keystore

## Package Structure

```
com.vnc
├── VncApplication.java            # Entry point
├── config/
│   └── WebSocketConfig.java       # WSS endpoint + container tuning
├── controller/
│   └── AppController.java         # REST API: GET /api/apps
├── model/
│   └── LockStatusMessage.java     # Lock state record (JSON)
├── service/
│   ├── AppRegistry.java           # Multi-app lifecycle manager
│   ├── AppInstance.java           # Per-app service bundle + capture loop
│   ├── H264EncoderService.java    # H.264 video encoder (JavaCV/FFmpeg)
│   ├── BroadcastService.java      # Client registry + binary/text dispatch
│   ├── ControlLockService.java    # Single-controller lock
│   └── RemoteControlService.java  # Input simulation
├── swing/
│   ├── SwingApp.java              # JFrame creation (per-app)
│   └── AnimatedPanel.java         # Demo animation (bouncing balls)
└── websocket/
    └── VncWebSocketHandler.java   # WebSocket message router (multi-app)
```

---

## Multi-App Architecture

### `AppRegistry`

`@Service` implementing `SmartLifecycle` (phase 1). The central registry that creates, starts, and stops all app instances.

On `start()`:
1. Creates 3 `AppInstance` objects with IDs `"1"`, `"2"`, `"3"` and descriptive names
2. Calls `start()` on each instance (initializes Swing, encoder, capture thread)
3. Stores them in a `ConcurrentHashMap<String, AppInstance>`

**Public API:**

| Method         | Return Type            | Description                     |
|----------------|------------------------|---------------------------------|
| `get(id)`      | `AppInstance` or `null`| Look up by app ID               |
| `listApps()`   | `List<AppInfo>`        | All apps as `{id, name}` pairs  |

`AppInfo` is a `record AppInfo(String id, String name)` nested inside `AppRegistry`.

### `AppInstance`

A plain Java object (not a Spring bean) that bundles all per-app services:

| Field                  | Type                     | Description                            |
|------------------------|--------------------------|----------------------------------------|
| `id`                   | `String`                 | Unique app identifier (e.g. `"1"`)     |
| `name`                 | `String`                 | Display name (e.g. `"Bouncing Balls"`) |
| `swingApp`             | `SwingApp`               | JFrame for this app                    |
| `encoder`              | `H264EncoderService`     | H.264 encoder for this app             |
| `broadcastService`     | `BroadcastService`       | Client sessions for this app           |
| `controlLockService`   | `ControlLockService`     | Lock for this app                      |
| `remoteControlService` | `RemoteControlService`   | Input simulation for this app          |

**Lifecycle:**

- `start()` — Creates the Swing frame on EDT, starts the encoder, starts a `ScheduledExecutorService` capture thread
- `stop()` — Shuts down the capture thread, stops the encoder, disposes the Swing frame

Each `AppInstance` has its own `ScheduledExecutorService` named `app-{id}-capture` running at 50ms intervals.

---

### `AppController`

`@RestController` at `/api`.

| Endpoint        | Method | Response                  | Description              |
|-----------------|--------|---------------------------|--------------------------|
| `/api/apps`     | GET    | `List<AppInfo>` (JSON)    | Lists all running apps   |

Example response:
```json
[
  {"id": "1", "name": "Bouncing Balls"},
  {"id": "2", "name": "Bouncing Balls 2"},
  {"id": "3", "name": "Bouncing Balls 3"}
]
```

---

## Per-App Services

### `H264EncoderService`

Wraps FFmpeg's H.264 encoder via JavaCV. One instance per `AppInstance` — not a Spring bean.

#### Codec Selection

At startup, the encoder probes for available H.264 encoders:

1. **libx264** — GPL-licensed, highest quality, supports CRF/preset/tune options
2. **libopenh264** — Cisco's BSD-licensed codec, bundled with JavaCV
3. **Any H.264 encoder** — fallback via `avcodec_find_encoder(AV_CODEC_ID_H264)`

#### Encoder Configuration

| Setting          | libx264 value      | libopenh264 value        |
|------------------|--------------------|--------------------------|
| Profile          | baseline (string)  | 66 (numeric, baseline)   |
| Preset           | ultrafast          | N/A                      |
| Tune             | zerolatency        | N/A                      |
| Quality          | CRF 28             | 400 kbps bitrate         |
| GOP size         | 40 frames (2s)     | 40 frames (2s)           |
| B-frames         | 0                  | 0                        |
| Pixel format     | YUV420P            | YUV420P                  |

#### Annex B → AVCC Conversion

WebCodecs `VideoDecoder` requires AVCC format. FFmpeg may output Annex B format (start-code prefixed). The encoder handles this transparently:

- **Extradata (SPS+PPS):** `ensureAvcc()` parses Annex B start codes, extracts SPS and PPS NAL units, and builds an `AVCDecoderConfigurationRecord`
- **Encoded packets:** `annexBToAvccPacket()` replaces start codes with 4-byte length prefixes

---

### `BroadcastService`

Per-app client session registry. Not a Spring bean — instantiated per `AppInstance` with `ObjectMapper` passed via constructor.

#### Binary Frame Format

```
[0]      uint8   flags (bit 0 = keyframe)
[1-4]    uint32  timestamp (big-endian, ms from encoder start)
[5..]    bytes   H.264 access unit (AVCC format)
```

Codec config:
```
[0]      uint8   0xFF (config marker)
[1..]    bytes   AVCDecoderConfigurationRecord
```

#### Client Management

- `addClient(id, session)` — registers session, sends cached codec config + last keyframe
- `removeClient(id)` — unregisters session
- `hasClients()` — skip capture when no viewers

#### Backpressure

Per-client `AtomicBoolean inFlight` — at most 1 frame in-flight per client. Frames are dropped for slow clients.

---

### `RemoteControlService`

Per-app input simulation targeting a specific `SwingApp` instance.

| Method             | Description                                              |
|--------------------|----------------------------------------------------------|
| `getSnapshot()`    | Paint content pane into JPEG via `ImageIO` on EDT        |
| `click(x, y)`     | Dispatch mouse events on EDT to the deepest component    |
| `press(key)`       | Dispatch key events on EDT to the focused component      |

---

### `ControlLockService`

Per-app single-controller lock via `AtomicReference<String>`. Each app has its own lock — Client A can control App 1 while Client B controls App 2.

| Method                    | CAS Operation              |
|---------------------------|----------------------------|
| `tryLock(sessionId)`      | `CAS(null → sessionId)`   |
| `unlock(sessionId)`       | `CAS(sessionId → null)`   |
| `isController(sessionId)` | Read + equals              |
| `isLocked()`              | Read != null               |

---

## WebSocket Handler

### `VncWebSocketHandler`

`@Component` extending `TextWebSocketHandler`. Registered at `/ws/*`.

#### Multi-App Routing

On connection, the handler extracts the app ID from the URI path (e.g. `/ws/2` → `"2"`), looks up the `AppInstance` from `AppRegistry`, and stores a `session → AppInstance` mapping.

All subsequent messages from that session are routed to the correct app:

| `type`     | Auth Required | Action                                          |
|------------|---------------|--------------------------------------------------|
| `"click"`  | Controller    | `app.getRemoteControlService().click(x, y)`     |
| `"key"`    | Controller    | `app.getRemoteControlService().press(key)`       |
| `"lock"`   | Any           | `app.getControlLockService().tryLock(sessionId)` |
| `"unlock"` | Controller    | `app.getControlLockService().unlock(sessionId)`  |

On disconnect, the handler removes the client from the app's `BroadcastService` and auto-unlocks if the disconnecting client held the lock.

---

## Swing UI

### `SwingApp`

Not a Spring bean. Constructor accepts a `String title` for the JFrame. Each `AppInstance` creates its own `SwingApp`.

### `AnimatedPanel`

60 FPS Swing Timer animation with bouncing balls, gradient background, and grid overlay. Shared across all app instances (each gets its own `AnimatedPanel` instance with independent animation state).

---

## Configuration

### `WebSocketConfig`

- Registers `VncWebSocketHandler` at `/ws/*` with `allowedOrigins("*")`.
- Configures `ServletServerContainerFactoryBean`:
  - Text message buffer: 2 MB
  - Binary message buffer: 2 MB
  - Session idle timeout: 1 hour

### SSL Keystore

Auto-generated by the `generateKeystore` Gradle task if `src/main/resources/keystore.p12` does not exist.

---

## Model Records

### `LockStatusMessage`

```java
public record LockStatusMessage(String type, boolean locked, boolean you)
```

Factory method `LockStatusMessage.of(locked, isController)` sets `type` to `"lockStatus"`.

---

## Dependencies

### `javacv-platform` (1.5.11)

Provides Java bindings to FFmpeg's `avcodec`, `avutil`, and `swscale` libraries. The `-platform` artifact bundles native libraries for all major platforms.

Key classes used:
- `AVCodec` / `AVCodecContext` — encoder configuration and lifecycle
- `AVFrame` — raw video frames (BGRA input, YUV420P output)
- `AVPacket` — encoded video packets
- `SwsContext` — color space conversion (BGRA → YUV420P)
- `AVDictionary` — codec-specific option strings

The native libraries are loaded automatically by JavaCV at first use. No manual installation required.
