# WebSocket Protocol Specification

## Connection

- **Endpoint:** `wss://localhost:8443/ws/{appId}` (e.g. `/ws/1`, `/ws/2`, `/ws/3`)
- **Transport:** WebSocket over TLS (WSS)
- **Frame encoding:** Binary frames for H.264 video data + codec config, JSON text frames for control messages
- **Client `binaryType`:** `arraybuffer`
- **Max message size:** 2 MB
- **App isolation:** Each WebSocket connection is bound to a specific app. Clients, lock state, and frame broadcasts are scoped to the connected app.

## REST API

### `GET /api/apps`

Returns the list of available apps.

```json
[
  { "id": "1", "name": "Bouncing Balls" },
  { "id": "2", "name": "Bouncing Balls 2" },
  { "id": "3", "name": "Bouncing Balls 3" }
]
```

Used by the frontend Screen Manager to populate the app selection grid.

## Message Types

### Server → Client

#### Codec Config (Binary)

Sent once when the encoder starts and to each new client on connection. Contains the H.264 SPS and PPS parameters needed to initialize the decoder.

**Wire format:**

```
[0]      uint8   0xFF (config marker — distinguishes from frame messages)
[1..]    bytes   AVCDecoderConfigurationRecord (SPS + PPS in AVCC format)
```

The AVCC record structure:

```
[0]   version (0x01)
[1]   profile_idc (e.g. 0x42 = Baseline)
[2]   constraint_set flags
[3]   level_idc (e.g. 0x1F = Level 3.1)
[4]   0xFF (4-byte NAL length prefix)
[5]   0xE1 (1 SPS)
[6-7] SPS length (big-endian uint16)
[8..] SPS NAL unit data
[..]  0x01 (1 PPS)
[..]  PPS length (big-endian uint16)
[..]  PPS NAL unit data
```

This is passed directly to the WebCodecs `VideoDecoder.configure()` `description` parameter.

#### Frame (Binary)

Sent as a **WebSocket binary frame** at ~20 FPS. Contains one H.264 access unit (one frame's encoded NAL units in AVCC length-prefixed format).

**Wire format:**

```
[0]      uint8   flags — bit 0: 1 = keyframe (IDR), 0 = delta frame (P-frame)
[1-4]    uint32  timestamp (big-endian, milliseconds from stream start)
[5..]    bytes   H.264 access unit (NAL units with 4-byte length prefixes)
```

| Field       | Type     | Description                                              |
|-------------|----------|----------------------------------------------------------|
| `flags`     | `uint8`  | Bit 0 = keyframe (IDR frame)                             |
| `timestamp` | `uint32` | Milliseconds since encoder start (big-endian)            |
| `data`      | `bytes`  | H.264 NAL units in AVCC format (4-byte length prefixed)  |

**Keyframe vs delta:**
- **Keyframe (IDR):** Self-contained frame. Sent every 2 seconds (GOP size = 40 at 20 FPS) and cached per-app for new client initialization.
- **Delta frame (P-frame):** Encodes only differences from the previous frame. Typically 1-3 KB.

#### `lockStatus` (JSON Text)

Sent as a **WebSocket text frame** to each client individually when lock state changes, and on initial connection. Scoped to the connected app.

```json
{
  "type": "lockStatus",
  "locked": true,
  "you": false
}
```

| Field    | Type        | Description                                         |
|----------|-------------|-----------------------------------------------------|
| `type`   | `"lockStatus"` | Message discriminator                            |
| `locked` | `boolean`   | `true` if any client holds the lock for this app    |
| `you`    | `boolean`   | `true` if THIS client is the controller             |

### Client → Server

All client-to-server messages are **JSON text frames**.

#### `click`

Simulate a mouse click at the given coordinates on the connected app's JFrame.

```json
{
  "type": "click",
  "x": 640,
  "y": 360
}
```

Ignored if the sender does not hold the control lock for this app.

#### `key`

Simulate a key press on the connected app's JFrame.

```json
{
  "type": "key",
  "key": "a"
}
```

Ignored if the sender does not hold the control lock for this app.

#### `lock`

Request exclusive control of the connected app.

```json
{ "type": "lock" }
```

Succeeds only if no other client holds the lock for this app.

#### `unlock`

Release exclusive control of the connected app.

```json
{ "type": "unlock" }
```

## Client Message Dispatch

```
ws.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer) {
        const view = new Uint8Array(event.data);
        if (view[0] === 0xFF)  → codec config (SPS+PPS)
        else                   → H.264 video frame
    } else {
        → JSON.parse() for lockStatus
    }
}
```

## Connection Lifecycle

```
Client                            Server
  │                                  │
  │──── WebSocket CONNECT ──────────▶│  /ws/2
  │     (binaryType: arraybuffer)    │  extract appId="2"
  │                                  │  AppRegistry.get("2") → AppInstance
  │                                  │  addClient(sessionId)
  │◀──── lockStatus (text JSON) ────│  (initial lock state for app 2)
  │◀──── codec config (binary) ─────│  (SPS+PPS for app 2's encoder)
  │◀──── keyframe (binary) ─────────│  (cached, if available)
  │                                  │
  │◀──── frame (binary, delta) ─────│  (20 FPS H.264 stream from app 2)
  │◀──── frame (binary, delta) ─────│
  │◀──── frame (binary, key) ───────│  (every ~2 seconds)
  │                                  │
  │──── { type: "lock" } (text) ───▶│  app2.tryLock(sessionId)
  │◀──── lockStatus (text JSON) ────│
  │                                  │
  │──── { type: "click" } (text) ──▶│  app2.remoteControlService.click()
  │                                  │
  │──── WebSocket CLOSE ────────────▶│
  │                                  │  app2.removeClient(sessionId)
  │                                  │  auto-unlock if controller
```

## Backpressure

Each client has a per-session in-flight flag (`AtomicBoolean`). When a new frame is ready to broadcast:

1. `inFlight.compareAndSet(false, true)` — if this succeeds, the frame is queued for sending.
2. If the CAS fails (previous frame still in transit), the frame is **dropped** for this client.
3. After the send completes (or fails), `inFlight` is set back to `false`.

This guarantees:
- At most 1 frame in-flight per client
- No unbounded queue buildup
- Slow clients lose frames rather than causing memory pressure

Since H.264 delta frames depend on previous frames, dropped frames can cause brief visual artifacts until the next keyframe (every ~2 seconds) resyncs the decoder.
