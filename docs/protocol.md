# WebSocket Protocol Specification

## Connection

- **Endpoint:** `wss://localhost:8443/ws`
- **Transport:** WebSocket over TLS (WSS)
- **Frame encoding:** Binary frames for screen data, JSON text frames for control messages
- **Client `binaryType`:** `arraybuffer`
- **Max message size:** 2 MB

## Message Types

### Server → Client

#### Frame (Binary)

Sent as a **WebSocket binary frame** at ~30 FPS when the screen changes. This avoids the overhead of JSON serialization and Base64 encoding.

**Wire format:**

```
Header (3 bytes):
  [0]      uint8   flags — bit 0: 1 = full frame, 0 = diff frame
  [1-2]    uint16  tile count (big-endian)

Per tile (variable length, repeated `tileCount` times):
  [0]      uint8   column index (0–39)
  [1]      uint8   row index (0–22)
  [2-5]    uint32  JPEG byte length (big-endian)
  [6..]    bytes   raw JPEG data (not Base64-encoded)
```

| Field          | Type     | Description                                              |
|----------------|----------|----------------------------------------------------------|
| `flags`        | `uint8`  | Bit 0 = full frame                                       |
| `tileCount`    | `uint16` | Number of tiles in this frame (big-endian)               |
| `tile.col`     | `uint8`  | Tile column index (0–39)                                 |
| `tile.row`     | `uint8`  | Tile row index (0–22)                                    |
| `tile.jpegLen` | `uint32` | Length of the following JPEG data in bytes (big-endian)   |
| `tile.jpeg`    | `bytes`  | Raw JPEG-compressed tile pixels                          |

**Tile grid:** 1280x720 resolution divided into 32x32 tiles = 40 columns × 23 rows (920 tiles total). The bottom row tiles are 32x16 pixels.

**Full vs diff:**
- **Diff frame** (flags bit 0 = 0): contains only tiles that changed since the last capture. The client draws them on top of the existing canvas.
- **Full frame** (flags bit 0 = 1): contains all 920 tiles. Sent when >40% of tiles changed, or every ~10 seconds as a forced resync. The client should replace the entire canvas atomically.

**Example (hex) — diff frame with 2 tiles:**
```
00 00 02                          ← flags=0 (diff), tileCount=2
0C 05 00 00 03 E8 [1000 bytes]   ← col=12, row=5, jpegLen=1000, jpeg...
0D 05 00 00 04 10 [1040 bytes]   ← col=13, row=5, jpegLen=1040, jpeg...
```

#### `lockStatus` (JSON Text)

Sent as a **WebSocket text frame** to each client individually when lock state changes, and on initial connection.

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
| `locked` | `boolean`   | `true` if any client currently holds the control lock |
| `you`    | `boolean`   | `true` if THIS client is the controller             |

### Client → Server

All client-to-server messages are **JSON text frames**.

#### `click`

Simulate a mouse click at the given coordinates.

```json
{
  "type": "click",
  "x": 640,
  "y": 360
}
```

| Field  | Type       | Description                               |
|--------|------------|-------------------------------------------|
| `type` | `"click"`  | Message discriminator                     |
| `x`    | `integer`  | X coordinate (0–1279) in content pane space |
| `y`    | `integer`  | Y coordinate (0–719) in content pane space  |

Ignored if the sender does not hold the control lock.

#### `key`

Simulate a key press.

```json
{
  "type": "key",
  "key": "a"
}
```

| Field  | Type      | Description                                        |
|--------|-----------|----------------------------------------------------|
| `type` | `"key"`   | Message discriminator                              |
| `key`  | `string`  | Single character to type (first char is used)      |

Ignored if the sender does not hold the control lock.

#### `lock`

Request exclusive control.

```json
{
  "type": "lock"
}
```

Succeeds only if no other client holds the lock. On success, the server broadcasts `lockStatus` to all clients. On failure, no response is sent.

#### `unlock`

Release exclusive control.

```json
{
  "type": "unlock"
}
```

Succeeds only if the sender currently holds the lock. On success, the server broadcasts `lockStatus` to all clients.

## Client Message Dispatch

The client distinguishes server messages by WebSocket frame type:

```
ws.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer)  → parse binary frame header + tiles
    else                                    → JSON.parse() for lockStatus
}
```

## Connection Lifecycle

```
Client                            Server
  │                                  │
  │──── WebSocket CONNECT ──────────▶│
  │     (binaryType: arraybuffer)    │ addClient(sessionId)
  │◀──── lockStatus (text JSON) ────│ (initial lock state)
  │◀──── frame (binary, full) ──────│ (cached, if available)
  │                                  │
  │◀──── frame (binary, diff) ──────│ (30 FPS stream)
  │◀──── frame (binary, diff) ──────│
  │                                  │
  │──── { type: "lock" } (text) ───▶│ tryLock(sessionId)
  │◀──── lockStatus (text JSON) ────│
  │                                  │
  │──── { type: "click" } (text) ──▶│ remoteControlService.click()
  │──── { type: "key" } (text) ────▶│ remoteControlService.press()
  │                                  │
  │──── { type: "unlock" } (text) ─▶│ unlock(sessionId)
  │◀──── lockStatus (text JSON) ────│
  │                                  │
  │──── WebSocket CLOSE ────────────▶│
  │                                  │ removeClient(sessionId)
  │                                  │ auto-unlock if controller
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
