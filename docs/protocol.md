# WebSocket Protocol Specification

## Connection

- **Endpoint:** `wss://localhost:8443/ws`
- **Transport:** WebSocket over TLS (WSS)
- **Encoding:** JSON text frames
- **Max message size:** 2 MB

## Message Types

### Server → Client

#### `frame`

Sent at ~30 FPS when the screen changes.

```json
{
  "type": "frame",
  "full": false,
  "tiles": [
    { "x": 12, "y": 5, "data": "/9j/4AAQSkZJ..." },
    { "x": 13, "y": 5, "data": "/9j/4AAQSkZJ..." }
  ]
}
```

| Field        | Type       | Description                                                |
|--------------|------------|------------------------------------------------------------|
| `type`       | `"frame"`  | Message discriminator                                      |
| `full`       | `boolean`  | `true` = complete frame (all tiles), `false` = diff only   |
| `tiles`      | `TileData[]` | Array of changed (or all) tiles                          |
| `tiles[].x`  | `integer`  | Tile column index (0–39)                                   |
| `tiles[].y`  | `integer`  | Tile row index (0–22)                                      |
| `tiles[].data`| `string`  | Base64-encoded JPEG of the 32x32 pixel tile                |

**Tile grid:** 1280x720 resolution divided into 32x32 tiles = 40 columns × 23 rows (920 tiles total). The bottom row tiles are 32x16 pixels.

**Full vs diff:**
- **Diff frame** (`full: false`): contains only tiles that changed since the last capture. The client draws them on top of the existing canvas.
- **Full frame** (`full: true`): contains all 920 tiles. Sent when >40% of tiles changed, or every ~10 seconds as a forced resync. The client should replace the entire canvas atomically.

#### `lockStatus`

Sent to each client individually when lock state changes, and on initial connection.

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

## Connection Lifecycle

```
Client                          Server
  │                                │
  │──── WebSocket CONNECT ────────▶│
  │                                │ addClient(sessionId)
  │◀──── lockStatus ──────────────│ (initial lock state)
  │◀──── frame (cached full) ─────│ (if available)
  │                                │
  │◀──── frame (diff) ────────────│ (30 FPS stream)
  │◀──── frame (diff) ────────────│
  │                                │
  │──── { type: "lock" } ────────▶│ tryLock(sessionId)
  │◀──── lockStatus (you: true) ──│
  │                                │
  │──── { type: "click" } ───────▶│ remoteControlService.click()
  │──── { type: "key" } ─────────▶│ remoteControlService.press()
  │                                │
  │──── { type: "unlock" } ──────▶│ unlock(sessionId)
  │◀──── lockStatus (you: false) ─│
  │                                │
  │──── WebSocket CLOSE ──────────▶│
  │                                │ removeClient(sessionId)
  │                                │ auto-unlock if controller
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
