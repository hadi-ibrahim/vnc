# Frontend Documentation

## Technology Stack

- **Angular 19.2** (standalone components, signals, zone.js)
- **TypeScript 5.7**
- **Native WebSocket API** (no RxJS WebSocket wrapper)
- **Canvas 2D API** for tile-based rendering

## Source Structure

```
frontend/src/app/
├── app.component.ts            # Root component (layout + lock button)
├── app.component.html          # Root template
├── app.component.css           # Root styles
├── app.config.ts               # Application bootstrap config
├── services/
│   └── vnc.service.ts          # WebSocket + state management
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

Each tile is decoded independently and drawn directly onto the visible canvas:

```typescript
const img = new Image();
img.onload = () => this.ctx.drawImage(img, dx, dy);
img.src = 'data:image/jpeg;base64,' + tile.data;
```

Tile-by-tile updates are imperceptible to the user since only a few tiles change per frame.

#### Full Frames (`full: true`)

Uses double-buffering to prevent visible flicker:

1. The current visible canvas is copied to an offscreen canvas.
2. All 920 tiles are decoded and drawn onto the offscreen canvas.
3. A counter tracks remaining tile loads.
4. When the last tile's `onload` fires, the offscreen canvas is blitted atomically to the visible canvas via `drawImage()`.

This prevents the "progressive refresh" artifact that would otherwise occur when hundreds of tiles load at slightly different times.

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
  │
  ├── onopen  → connected.set(true), clearReconnect()
  ├── onclose → connected.set(false), scheduleReconnect()
  ├── onerror → ws.close()  (triggers onclose)
  └── onmessage → handleMessage(JSON.parse(data))
```

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

| Server Message  | Action                                                |
|-----------------|-------------------------------------------------------|
| `frame`         | Invoke `frameCallback` (registered by canvas component)|
| `lockStatus`    | Update `isLocked` and `isController` signals          |

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
