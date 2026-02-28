# Backend Documentation

## Technology Stack

- **Java 21** (virtual threads, records, pattern matching switch)
- **Spring Boot 3.3.5** (embedded Tomcat, WebSocket, Jackson)
- **Gradle 8.10** (Kotlin DSL)
- **JNA 5.14** (Java Native Access — for TurboJPEG binding)
- **HTTPS** with auto-generated self-signed PKCS12 keystore

## Package Structure

```
com.vnc
├── VncApplication.java            # Entry point
├── config/
│   └── WebSocketConfig.java       # WSS endpoint + container tuning
├── model/
│   └── LockStatusMessage.java     # Lock state record (JSON)
├── service/
│   ├── CaptureService.java        # Screen capture + tile diff + binary framing
│   ├── BroadcastService.java      # Client registry + binary/text dispatch
│   ├── ControlLockService.java    # Single-controller lock
│   └── RemoteControlService.java  # Input simulation
├── swing/
│   ├── SwingApp.java              # JFrame lifecycle + CardLayout page switching
│   ├── AnimatedPanel.java         # Demo animation (bouncing balls)
│   └── LoadingPanel.java          # Loading spinner animation
├── util/
│   └── JpegCodec.java             # TurboJPEG native encoder + ImageIO fallback
└── websocket/
    └── VncWebSocketHandler.java   # WebSocket message router
```

---

## Services

### `RemoteControlService`

The public API for interacting with the Swing application. All methods are thread-safe.

#### `byte[] getSnapshot()`

Captures the current content pane as a JPEG byte array.

- Paints the content pane into a 1280x720 `BufferedImage` on the EDT via `invokeAndWait()`.
- Encodes to JPEG at quality 0.6.
- Returns an empty array if the frame is unavailable or the calling thread is interrupted.

#### `void click(int x, int y)`

Simulates a full mouse click (press → release → click) at content-pane coordinates.

- Finds the deepest component at `(x, y)` using `SwingUtilities.getDeepestComponentAt()`.
- Converts coordinates to the target component's local space.
- Dispatches `MOUSE_PRESSED`, `MOUSE_RELEASED`, `MOUSE_CLICKED` events on the EDT via `invokeLater()`.

#### `void press(char key)`

Simulates a full key stroke (press → typed → release).

- Targets the currently focused component, or the content pane if nothing has focus.
- Derives the key code from the character via `KeyEvent.getExtendedKeyCodeForChar()`.
- Dispatches `KEY_PRESSED`, `KEY_TYPED`, `KEY_RELEASED` events on the EDT via `invokeLater()`.

---

### `CaptureService`

The 30 FPS screen capture engine. Implements `SmartLifecycle` (phase 1).

#### Capture Loop

Runs on a dedicated daemon thread (`vnc-capture`) via `ScheduledExecutorService.scheduleAtFixedRate()` at 33ms intervals.

Each tick:
1. **Guard:** `AtomicBoolean.compareAndSet(false, true)` prevents re-entry.
2. **Skip if idle:** Returns early if `BroadcastService` has no connected clients.
3. **Buffer swap:** `previousFrame` and `currentFrame` references are swapped.
4. **Paint:** `SwingUtilities.invokeAndWait()` paints the content pane into `currentFrame`.
5. **Diff:** Each 32x32 tile is compared using `Arrays.mismatch()` on pre-allocated `int[]` buffers.
6. **Threshold:** If changed tiles exceed 40% of total, or the forced-full interval (every 300 frames ≈ 10s) is reached, a full frame is produced.
7. **Encode:** Changed tiles are JPEG-encoded into raw `byte[]` via `JpegCodec` (no Base64).
8. **Frame build:** Tiles are packed into a compact binary frame (see Protocol docs).
9. **Broadcast:** The `byte[]` frame is handed to `BroadcastService.broadcastFrame()`.

#### Binary Frame Assembly

The `buildBinaryFrame()` method packs all encoded tiles into a single `byte[]`:

```
[0]      uint8   flags (bit 0 = full frame)
[1-2]    uint16  tile count (big-endian)
Per tile:
  [0]    uint8   column
  [1]    uint8   row
  [2-5]  uint32  JPEG byte length (big-endian)
  [6..]  raw JPEG bytes
```

This is built once and shared across all client sends, avoiding per-client serialization.

#### Tile Grid

```
1280 / 32 = 40 columns
720  / 32 = 22.5 → 23 rows (bottom row is 32×16 pixels)
Total: 920 tiles
```

#### Tile Comparison

Uses `Arrays.mismatch(int[], fromIndex, toIndex, int[], fromIndex, toIndex)` which is intrinsified by the JVM into SIMD instructions on supported hardware. This is significantly faster than element-wise comparison.

---

### `BroadcastService`

Manages WebSocket client sessions and dispatches messages. Uses a **dual-format** approach: binary frames for screen data, JSON text frames for control messages.

#### Client Management

- `addClient(id, session)` — registers the session and sends the cached last full frame (if available) as a `BinaryMessage` for immediate canvas initialization.
- `removeClient(id)` — unregisters the session.
- `getClientIds()` — returns an immutable snapshot of current client IDs.
- `hasClients()` — fast emptiness check to skip capture work when idle.

#### `broadcastFrame(byte[] frame, boolean isFull)`

1. Wraps the `byte[]` in a `BinaryMessage` **once** (shared across all clients).
2. Caches the `BinaryMessage` if it is a full frame.
3. For each client, attempts `inFlight.compareAndSet(false, true)`:
   - **Success:** submits a virtual thread to send the message.
   - **Failure:** the frame is dropped for this client (backpressure).
4. The virtual thread synchronizes on the session, sends, and clears `inFlight`.

#### `sendTo(String sessionId, Object message)`

Sends a JSON text message to a specific client. Used for lock status updates. Serializes via Jackson `ObjectMapper`. Does not use the `inFlight` guard (lock messages are small and should never be dropped).

---

### `ControlLockService`

Single-controller lock implemented with `AtomicReference<String>`.

| Method                    | CAS Operation              | Description                        |
|---------------------------|----------------------------|------------------------------------|
| `tryLock(sessionId)`      | `CAS(null → sessionId)`   | Acquire lock if free               |
| `unlock(sessionId)`       | `CAS(sessionId → null)`   | Release lock if held by this session |
| `isController(sessionId)` | Read + equals              | Check if this session holds the lock |
| `isLocked()`              | Read != null               | Check if any session holds the lock |

All operations are lock-free and wait-free.

---

### `VncWebSocketHandler`

Extends `TextWebSocketHandler`. Registered at the `/ws` endpoint. Handles incoming **text** messages from clients; outgoing frames are sent via `BroadcastService` (binary for screen data, text for lock status).

#### Connection Events

| Event                        | Action                                               |
|------------------------------|------------------------------------------------------|
| `afterConnectionEstablished` | Register client, send initial `lockStatus`           |
| `afterConnectionClosed`      | Unregister client, auto-unlock if controller, broadcast updated `lockStatus` |
| `handleTransportError`       | Log warning                                          |

#### Message Routing

Incoming JSON is parsed with Jackson `readTree()`. The `type` field determines the handler:

| `type`     | Auth Required | Action                                    |
|------------|---------------|-------------------------------------------|
| `"click"`  | Controller    | `remoteControlService.click(x, y)`        |
| `"key"`    | Controller    | `remoteControlService.press(key.charAt(0))` |
| `"lock"`   | Any           | `controlLockService.tryLock(sessionId)`   |
| `"unlock"` | Controller    | `controlLockService.unlock(sessionId)`    |

"Controller" means the message is silently ignored if the sender does not hold the control lock.

---

## Swing UI

### `SwingApp`

SmartLifecycle (phase 0). Creates and manages the 1280x720 JFrame on the EDT.

Uses `CardLayout` with two switchable pages:

| Page key     | Panel            | Description                              |
|--------------|------------------|------------------------------------------|
| `"main"`     | `AnimatedPanel`  | Bouncing balls demo + control bar        |
| `"loading"`  | `LoadingPanel`   | Centered spinning arc with "Loading..." text |

Page switching is exposed via `showPage(String name)`, which safely schedules the switch on the EDT via `invokeLater()`.

The control bar on the main page includes a **"Loading Page"** button to switch to the loading page.

### `AnimatedPanel`

60 FPS Swing Timer animation with bouncing balls, gradient background, and grid overlay. Provides `cycleColor()`, `reset()`, and `toggleSpeed()` methods controlled by the button bar.

### `LoadingPanel`

Minimal loading screen with a rotating 90-degree arc spinner (48px diameter) and "Loading..." label. Animates at 60 FPS via Swing Timer. Same gradient background as `AnimatedPanel` for visual consistency.

---

## Configuration

### `WebSocketConfig`

- Registers `VncWebSocketHandler` at `/ws` with `allowedOrigins("*")`.
- Configures `ServletServerContainerFactoryBean`:
  - Text message buffer: 2 MB (for lock status JSON).
  - Binary message buffer: 2 MB (accommodates full-frame binary with ~920 JPEG tiles).
  - Session idle timeout: 1 hour.

### SSL Keystore

Auto-generated by the `generateKeystore` Gradle task if `src/main/resources/keystore.p12` does not exist:

- Algorithm: RSA 2048-bit
- Format: PKCS12
- Validity: 10 years
- Subject: `CN=localhost`
- Password: `changeit`

---

## Model Records

### `LockStatusMessage`

```java
public record LockStatusMessage(String type, boolean locked, boolean you)
```

Factory method `LockStatusMessage.of(locked, isController)` sets `type` to `"lockStatus"`. Serialized to JSON and sent as a text WebSocket frame.

Note: `FrameMessage` and `TileData` records were removed — frame data is now packed directly into a binary wire format by `CaptureService` (see Binary Frame Assembly above).

---

## Utility

### `JpegCodec`

JPEG encoder with **TurboJPEG native acceleration** and automatic ImageIO fallback.

```java
public static byte[] encode(BufferedImage image, float quality)
```

#### TurboJPEG Path (preferred)

- Uses **JNA** (Java Native Access) to call `libturbojpeg` directly — no JNI wrapper JAR needed.
- Probes multiple platform-specific library paths on class load:
  - `/opt/homebrew/opt/jpeg-turbo/lib/libturbojpeg.dylib` (macOS Homebrew ARM)
  - `/opt/homebrew/lib/libturbojpeg.dylib` (macOS Homebrew symlink)
  - `/usr/local/opt/jpeg-turbo/lib/libturbojpeg.dylib` (macOS Homebrew Intel)
  - `/usr/local/lib/libturbojpeg.so` (Linux manual install)
  - `/usr/lib/x86_64-linux-gnu/libturbojpeg.so.0` (Debian/Ubuntu x86_64)
  - `/usr/lib/aarch64-linux-gnu/libturbojpeg.so.0` (Debian/Ubuntu ARM64)
- Uses `ThreadLocal<Pointer>` for per-thread `TJCompressor` handles (thread-safe without synchronization).
- Converts `BufferedImage` `TYPE_INT_RGB` pixels to packed RGB bytes, encodes with `TJSAMP_420` chroma subsampling.
- ~2-6x faster than ImageIO on SIMD-capable hardware.

#### ImageIO Fallback

- Used automatically if `libturbojpeg` is not found on the system.
- Acquires an `ImageWriter` for the `"jpeg"` format.
- Sets explicit compression mode with the given quality (0.0–1.0).
- Writes to a `ByteArrayOutputStream` via `ImageOutputStream`.
- Disposes the writer in a `finally` block to prevent native resource leaks.

#### Install TurboJPEG

TurboJPEG is optional but recommended for production workloads:

```bash
# macOS
brew install jpeg-turbo

# Debian/Ubuntu
sudo apt install libturbojpeg0-dev

# RHEL/Fedora
sudo dnf install libjpeg-turbo-devel
```

The application logs whether TurboJPEG was loaded at startup:
```
INFO  c.v.util.JpegCodec - TurboJPEG native acceleration enabled
```
or:
```
INFO  c.v.util.JpegCodec - TurboJPEG not available — using ImageIO fallback
```
