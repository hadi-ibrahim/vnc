# Frontend Documentation

## Technology Stack

- **Angular 19.2** (standalone components, signals, zone.js)
- **TypeScript 5.7**
- **Native WebSocket API** (no RxJS WebSocket wrapper)
- **Canvas 2D API** for tile-based rendering
- **`createImageBitmap`** for off-main-thread JPEG decoding

## Source Structure

```
frontend/src/app/
├── app.component.ts            # Root component (layout + lock button)
├── app.component.html          # Root template
├── app.component.css           # Root styles
├── app.config.ts               # Application bootstrap config
├── services/
│   └── vnc.service.ts          # WebSocket + binary frame parsing + state
└── components/
    └── vnc-canvas/
        └── vnc-canvas.component.ts  # Canvas renderer + input forwarding
```

---

## Components

### `AppComponent`

The root shell. Provides the header bar with connection status and lock control.

**Template bindings:**

| Element              | Binding                            | Description                          |
|----------------------|------------------------------------|--------------------------------------|
| Status pill          | `vnc.connected()`                  | Green/red connection indicator       |
| Lock button text     | `vnc.isController()`              | "Take Control" / "Release Control"   |
| Lock button disabled | `!connected \|\| (locked && !you)` | Disabled when disconnected or another client holds the lock |

**Methods:**

| Method         | Description                                      |
|----------------|--------------------------------------------------|
| `ngOnInit()`   | Calls `vnc.connect()` to establish WebSocket      |
| `ngOnDestroy()`| Calls `vnc.disconnect()` to clean up              |
| `toggleLock()` | Sends lock or unlock based on current state       |

---

### `VncCanvasComponent`

Renders the VNC stream onto an HTML5 Canvas and forwards user input.

**Canvas setup:**
- Fixed dimensions: 1280×720 (matches server resolution)
- CSS `max-width: 100%` with `height: auto` for responsive scaling
- `tabindex="0"` for keyboard focus

**Rendering modes:**

#### Diff Frames (`full: false`)

Each tile's raw JPEG bytes (received as `Uint8Array` from the binary frame) are decoded off-main-thread and drawn directly onto the visible canvas:

```typescript
private decodeTile(jpeg: Uint8Array): Promise<ImageBitmap> {
    return createImageBitmap(new Blob([jpeg], { type: 'image/jpeg' }));
}
```

Tile-by-tile updates are imperceptible to the user since only a few tiles change per frame.

#### Full Frames (`full: true`)

Uses double-buffering to prevent visible flicker:

1. The current visible canvas is copied to an offscreen canvas.
2. All 920 tiles are decoded via `createImageBitmap` and drawn onto the offscreen canvas.
3. `Promise.all()` waits for all tile decodes to complete.
4. The offscreen canvas is blitted atomically to the visible canvas via `drawImage()`.

This prevents the "progressive refresh" artifact that would otherwise occur when hundreds of tiles decode at slightly different times.

**Input forwarding:**

| Event     | Condition       | Action                                        |
|-----------|-----------------|-----------------------------------------------|
| `click`   | Is controller   | Scale canvas coords to 1280×720, send `click` |
| `keydown` | Is controller   | If `key.length === 1`, send `key`             |

Coordinate scaling accounts for CSS-based canvas resizing:
```typescript
const scaleX = 1280 / rect.width;
const scaleY = 720 / rect.height;
```

---

## Services

### `VncService`

Singleton service (`providedIn: 'root'`) managing WebSocket lifecycle and application state.

**State (Angular Signals):**

| Signal          | Type      | Description                               |
|-----------------|-----------|-------------------------------------------|
| `connected`     | `boolean` | WebSocket is in OPEN state                |
| `isController`  | `boolean` | This client holds the control lock        |
| `isLocked`      | `boolean` | Any client holds the control lock         |

Signals integrate with Angular's change detection — template bindings update automatically without manual subscriptions or `OnPush` workarounds.

**WebSocket Lifecycle:**

```
connect()
  │
  ├── cleanup() — close existing connection, clear handlers
  ├── new WebSocket(url)
  ├── ws.binaryType = 'arraybuffer'
  │
  ├── onopen  → connected.set(true), clearReconnect()
  ├── onclose → connected.set(false), scheduleReconnect()
  ├── onerror → ws.close()  (triggers onclose)
  └── onmessage:
        ├── event.data instanceof ArrayBuffer → handleBinaryFrame()
        └── otherwise → JSON.parse() for lockStatus
```

**Binary frame parsing (`handleBinaryFrame`):**

Parses the binary wire format using `DataView`:

1. Read `flags` (uint8) and `tileCount` (uint16 big-endian) from the 3-byte header.
2. For each tile: read column (uint8), row (uint8), JPEG length (uint32 big-endian).
3. Slice the `ArrayBuffer` to get a `Uint8Array` view of the raw JPEG bytes.
4. Pass the parsed `FrameMessage` to the registered frame callback.

No Base64 decoding, no JSON parsing, no string allocation — just `DataView` reads and `Uint8Array` slices.

**Reconnect logic:**

On close, a 2-second timer schedules a new `connect()` call. The timer is cleared on successful open or manual disconnect. Cleanup nullifies all event handlers before closing to prevent the close handler from triggering reconnect during intentional disconnect.

**URL construction:**

```typescript
const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
const url = `${protocol}//${location.host}/ws`;
```

This works in both development (proxied through Angular dev server) and production (same-origin deployment).

**Public API:**

| Method                      | Description                           |
|-----------------------------|---------------------------------------|
| `connect()`                 | Establish WebSocket connection        |
| `disconnect()`              | Close connection, stop reconnect      |
| `onFrame(callback)`         | Register frame render callback        |
| `sendClick(x, y)`           | Send click command (rounded coords)   |
| `sendKey(key)`              | Send key command                      |
| `requestLock()`             | Send lock request                     |
| `releaseLock()`             | Send unlock request                   |

**Message handling:**

| Server Message      | Frame Type | Action                                                |
|---------------------|------------|-------------------------------------------------------|
| Frame data          | Binary     | Parse header + tiles, invoke `frameCallback`          |
| `lockStatus`        | Text       | JSON parse, update `isLocked` and `isController` signals |

---

## TypeScript Interfaces

```typescript
export interface TileData {
  x: number;       // tile column index
  y: number;       // tile row index
  jpeg: Uint8Array; // raw JPEG bytes (sliced from the binary frame)
}

export interface FrameMessage {
  full: boolean;    // true = all tiles, false = diff only
  tiles: TileData[];
}
```

Note: `TileData.jpeg` is a `Uint8Array` view into the original `ArrayBuffer` — no data copying occurs during parsing.

---

## Development Proxy

`proxy.conf.json` configures the Angular dev server to proxy WebSocket connections:

```json
{
  "/ws": {
    "target": "https://localhost:8443",
    "ws": true,
    "secure": false,
    "changeOrigin": true
  }
}
```

| Option          | Value                     | Purpose                                    |
|-----------------|---------------------------|--------------------------------------------|
| `target`        | `https://localhost:8443`  | Backend HTTPS endpoint                     |
| `ws`            | `true`                    | Proxy WebSocket upgrade requests           |
| `secure`        | `false`                   | Accept self-signed certificates            |
| `changeOrigin`  | `true`                    | Rewrite the Host header                    |

With this configuration, the browser connects to `ws://localhost:4200/ws` (the dev server), which transparently proxies to `wss://localhost:8443/ws` (the backend). No self-signed certificate warnings appear in the browser.

---

## Production Deployment

Build the Angular app:

```bash
cd frontend
npx ng build
```

The output in `dist/frontend/browser/` can be:

1. **Served by Spring Boot** — Copy contents to `backend/src/main/resources/static/` and rebuild. The entire app is then served from `https://localhost:8443/`.
2. **Served by a reverse proxy** (nginx, Caddy) — Proxy `/ws` to the backend, serve static files directly.
3. **Served by any static file server** — The WebSocket URL auto-detects the host via `location.host`.
