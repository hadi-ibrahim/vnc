# Backend Documentation

## Technology Stack

- **Java 21** (virtual threads, records, pattern matching switch)
- **Spring Boot 3.3.5** (embedded Tomcat, WebSocket, Jackson)
- **Gradle 8.10** (Kotlin DSL)
- **HTTPS** with auto-generated self-signed PKCS12 keystore

## Package Structure

```
com.vnc
├── VncApplication.java            # Entry point
├── config/
│   └── WebSocketConfig.java       # WSS endpoint + container tuning
├── model/
│   ├── FrameMessage.java          # Outbound frame record
│   ├── TileData.java              # Single tile record
│   └── LockStatusMessage.java     # Lock state record
├── service/
│   ├── CaptureService.java        # Screen capture + tile diff engine
│   ├── BroadcastService.java      # Client registry + message dispatch
│   ├── ControlLockService.java    # Single-controller lock
│   └── RemoteControlService.java  # Input simulation
├── swing/
│   ├── SwingApp.java              # JFrame lifecycle
│   └── AnimatedPanel.java         # Demo animation
├── util/
│   └── JpegCodec.java             # JPEG encoder
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
7. **Encode:** Changed tiles (or all tiles for full frames) are JPEG-encoded and Base64-wrapped into `TileData` records.
8. **Broadcast:** The `FrameMessage` is handed to `BroadcastService`.

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

Manages WebSocket client sessions and dispatches messages.

#### Client Management

- `addClient(id, session)` — registers the session and sends the cached last full frame (if available) for immediate canvas initialization.
- `removeClient(id)` — unregisters the session.
- `getClientIds()` — returns an immutable snapshot of current client IDs.
- `hasClients()` — fast emptiness check to skip capture work when idle.

#### `broadcast(Object message, boolean isFullFrame)`

1. Serializes the message to JSON **once** via Jackson `ObjectMapper`.
2. Caches the `TextMessage` if it is a full frame.
3. For each client, attempts `inFlight.compareAndSet(false, true)`:
   - **Success:** submits a virtual thread to send the message.
   - **Failure:** the frame is dropped for this client (backpressure).
4. The virtual thread synchronizes on the session, sends, and clears `inFlight`.

#### `sendTo(String sessionId, Object message)`

Sends a message to a specific client. Used for lock status updates. Does not use the `inFlight` guard (lock messages are small and should never be dropped).

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

Extends `TextWebSocketHandler`. Registered at the `/ws` endpoint.

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

## Configuration

### `WebSocketConfig`

- Registers `VncWebSocketHandler` at `/ws` with `allowedOrigins("*")`.
- Configures `ServletServerContainerFactoryBean`:
  - Text message buffer: 2 MB (accommodates full-frame JSON with ~920 Base64 tiles).
  - Binary message buffer: 2 MB.
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

### `FrameMessage`

```java
public record FrameMessage(String type, boolean full, List<TileData> tiles)
```

Convenience constructor `FrameMessage(boolean full, List<TileData> tiles)` sets `type` to `"frame"`.

### `TileData`

```java
public record TileData(int x, int y, String data)
```

`x`/`y` are tile grid coordinates (not pixel coordinates). `data` is a Base64-encoded JPEG.

### `LockStatusMessage`

```java
public record LockStatusMessage(String type, boolean locked, boolean you)
```

Factory method `LockStatusMessage.of(locked, isController)` sets `type` to `"lockStatus"`.

---

## Utility

### `JpegCodec`

Stateless JPEG encoder.

```java
public static byte[] encode(BufferedImage image, float quality)
```

- Acquires an `ImageWriter` for the `"jpeg"` format.
- Sets explicit compression mode with the given quality (0.0–1.0).
- Writes to a `ByteArrayOutputStream` via `ImageOutputStream`.
- Disposes the writer in a `finally` block to prevent native resource leaks.
