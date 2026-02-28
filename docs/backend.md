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
├── model/
│   └── LockStatusMessage.java     # Lock state record (JSON)
├── service/
│   ├── H264EncoderService.java    # H.264 video encoder (JavaCV/FFmpeg)
│   ├── CaptureService.java        # Screen capture + H.264 encoding pipeline
│   ├── BroadcastService.java      # Client registry + binary/text dispatch
│   ├── ControlLockService.java    # Single-controller lock
│   └── RemoteControlService.java  # Input simulation
├── swing/
│   ├── SwingApp.java              # JFrame lifecycle
│   └── AnimatedPanel.java         # Demo animation (bouncing balls)
└── websocket/
    └── VncWebSocketHandler.java   # WebSocket message router
```

---

## Services

### `H264EncoderService`

Wraps FFmpeg's H.264 encoder via JavaCV's low-level `avcodec` API. Handles the full pipeline from `BufferedImage` to encoded H.264 bytes in AVCC format.

#### Codec Selection

At startup, the encoder probes for available H.264 encoders in order of preference:

1. **libx264** — GPL-licensed, highest quality, supports CRF/preset/tune options
2. **libopenh264** — Cisco's BSD-licensed codec, bundled with JavaCV
3. **Any H.264 encoder** — fallback via `avcodec_find_encoder(AV_CODEC_ID_H264)`

The application logs which encoder is selected:
```
INFO  c.v.service.H264EncoderService - Using H.264 encoder: libopenh264
```

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
| Global header    | Yes (AVCC extradata) | Yes (AVCC extradata)   |

#### `start(int width, int height, int fps)`

1. Finds the best available H.264 encoder
2. Configures `AVCodecContext` with resolution, framerate, GOP, and codec-specific options
3. Opens the encoder via `avcodec_open2()`
4. Extracts SPS+PPS from `extradata` and converts to AVCC format if needed (`ensureAvcc()`)
5. Initializes `SwsContext` for BGRA→YUV420P color space conversion
6. Pre-allocates `AVFrame` (RGB + YUV) and `AVPacket`

#### `byte[] encode(BufferedImage image)`

1. Extracts pixel data via `DataBufferInt` (zero-copy access to the image's backing array)
2. Packs `int[]` pixels into a BGRA `ByteBuffer` and writes to the RGB `AVFrame`
3. Runs `sws_scale()` to convert BGRA→YUV420P into the YUV `AVFrame`
4. Sends the frame to the encoder via `avcodec_send_frame()`
5. Receives the encoded packet via `avcodec_receive_packet()`
6. Converts the packet from Annex B to AVCC format (`annexBToAvccPacket()`)
7. Returns the encoded bytes (or `null` if the encoder needs more input)

#### Annex B → AVCC Conversion

WebCodecs `VideoDecoder` requires NAL units with 4-byte length prefixes (AVCC format). FFmpeg's libopenh264 wrapper outputs Annex B format (start-code prefixed). The encoder handles this transparently:

- **Extradata (SPS+PPS):** `ensureAvcc()` parses Annex B start codes, extracts SPS and PPS NAL units, and builds an `AVCDecoderConfigurationRecord`
- **Encoded packets:** `annexBToAvccPacket()` replaces start codes with 4-byte length prefixes, stripping any inline SPS/PPS

#### `stop()`

Flushes the encoder, frees all native resources (`AVCodecContext`, `SwsContext`, `AVFrame`, `AVPacket`). Called during Spring context shutdown via `CaptureService.stop()`.

---

### `CaptureService`

The 20 FPS screen capture engine. Implements `SmartLifecycle` (phase 1).

#### Capture Loop

Runs on a dedicated daemon thread (`vnc-capture`) via `ScheduledExecutorService.scheduleAtFixedRate()` at 50ms intervals.

Each tick:
1. **Guard:** `AtomicBoolean.compareAndSet(false, true)` prevents re-entry.
2. **Skip if idle:** Returns early if `BroadcastService` has no connected clients.
3. **Paint:** `SwingUtilities.invokeAndWait()` paints the content pane into `captureBuffer`.
4. **Encode:** `H264EncoderService.encode(captureBuffer)` produces H.264 bytes.
5. **Broadcast:** `BroadcastService.broadcastFrame(encoded, isKeyframe, timestamp)` sends to all clients.

The pipeline is dramatically simpler than the previous tile-based approach — no tile grid, no per-tile JPEG encoding, no binary frame assembly. The H.264 encoder handles all spatial and temporal compression internally.

---

### `BroadcastService`

Manages WebSocket client sessions and dispatches messages. Sends **binary frames** for H.264 video data and codec config, **JSON text frames** for control messages.

#### Binary Frame Format

Each H.264 frame is wrapped in a 5-byte header before broadcast:

```
[0]      uint8   flags (bit 0 = keyframe)
[1-4]    uint32  timestamp (big-endian, ms from encoder start)
[5..]    bytes   H.264 access unit (AVCC format)
```

Codec config messages use a different marker:

```
[0]      uint8   0xFF (config marker)
[1..]    bytes   AVCDecoderConfigurationRecord
```

#### Client Management

- `setCodecConfig(byte[])` — wraps SPS+PPS in a `BinaryMessage` with 0xFF prefix, caches for new clients
- `addClient(id, session)` — registers the session, sends cached codec config + last keyframe
- `removeClient(id)` — unregisters the session
- `getClientIds()` — returns an immutable snapshot of current client IDs
- `hasClients()` — fast emptiness check to skip capture work when idle

#### `broadcastFrame(byte[] h264Data, boolean keyframe, long timestampMs)`

1. Builds the 5-byte header + payload into a `BinaryMessage` **once** (shared across all clients)
2. Caches the message if it is a keyframe
3. For each client, attempts `inFlight.compareAndSet(false, true)`:
   - **Success:** submits a virtual thread to send the message
   - **Failure:** the frame is dropped for this client (backpressure)
4. The virtual thread synchronizes on the session, sends, and clears `inFlight`

#### `sendTo(String sessionId, Object message)`

Sends a JSON text message to a specific client. Used for lock status updates. Serializes via Jackson `ObjectMapper`. Does not use the `inFlight` guard (lock messages are small and should never be dropped).

---

### `RemoteControlService`

The public API for interacting with the Swing application. All methods are thread-safe.

#### `byte[] getSnapshot()`

Captures the current content pane as a JPEG byte array (for snapshot/REST endpoints, not for streaming).

- Paints the content pane into a 1280×720 `BufferedImage` on the EDT via `invokeAndWait()`.
- Encodes to JPEG at quality 0.6 using `javax.imageio.ImageIO`.
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

Extends `TextWebSocketHandler`. Registered at the `/ws` endpoint. Handles incoming **text** messages from clients; outgoing frames are sent via `BroadcastService` (binary for H.264 video, text for lock status).

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

SmartLifecycle (phase 0). Creates and manages the 1280×720 JFrame on the EDT.

### `AnimatedPanel`

60 FPS Swing Timer animation with bouncing balls, gradient background, and grid overlay. Provides `cycleColor()`, `reset()`, and `toggleSpeed()` methods controlled by the button bar.

---

## Configuration

### `WebSocketConfig`

- Registers `VncWebSocketHandler` at `/ws` with `allowedOrigins("*")`.
- Configures `ServletServerContainerFactoryBean`:
  - Text message buffer: 2 MB (for lock status JSON).
  - Binary message buffer: 2 MB (accommodates H.264 keyframes).
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

---

## Dependencies

### `javacv-platform` (1.5.11)

Provides Java bindings to FFmpeg's `avcodec`, `avutil`, and `swscale` libraries. The `-platform` artifact bundles native libraries for all major platforms (macOS ARM/Intel, Linux x86_64/ARM64, Windows x86_64).

Key classes used:
- `AVCodec` / `AVCodecContext` — encoder configuration and lifecycle
- `AVFrame` — raw video frames (BGRA input, YUV420P output)
- `AVPacket` — encoded video packets
- `SwsContext` — color space conversion (BGRA → YUV420P)
- `AVDictionary` — codec-specific option strings

The native libraries are loaded automatically by JavaCV at first use. No manual installation required.
