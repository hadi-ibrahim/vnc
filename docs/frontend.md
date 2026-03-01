# Frontend Documentation

## Technology Stack

- **Angular 19.2** (standalone components, signals, zone.js)
- **TypeScript 5.7**
- **Angular Router** (path-based routing for screen manager + viewer)
- **Native WebSocket API** (no RxJS WebSocket wrapper)
- **WebCodecs API** (`VideoDecoder`) for hardware-accelerated H.264 decoding
- **Canvas 2D API** for video frame rendering

## Source Structure

```
frontend/src/app/
├── app.component.ts            # Root component (router outlet shell)
├── app.config.ts               # Application bootstrap config (provideRouter)
├── app.routes.ts               # Route definitions
├── services/
│   └── vnc.service.ts          # WebSocket + H.264 frame parsing + state
└── components/
    ├── screen-manager/
    │   └── screen-manager.component.ts  # App selection grid (fetches /api/apps)
    ├── viewer/
    │   └── viewer.component.ts          # VNC viewer page (header + canvas)
    └── vnc-canvas/
        └── vnc-canvas.component.ts      # WebCodecs decoder + canvas renderer
```

---

## Routing

Defined in `app.routes.ts`:

| Path         | Component              | Description                           |
|--------------|------------------------|---------------------------------------|
| `/`          | `ScreenManagerComponent` | Lists available apps from backend   |
| `/app/:id`   | `ViewerComponent`      | Streams the selected app             |

`AppComponent` is a minimal shell containing only `<router-outlet />`.

---

## Components

### `ScreenManagerComponent`

The landing page. Fetches the list of available apps from `GET /api/apps` and displays them as a responsive grid of clickable cards.

**Lifecycle:**

| Phase       | Action                                            |
|-------------|---------------------------------------------------|
| `ngOnInit`  | Fetches `/api/apps`, populates `apps` signal      |
| Template    | Renders a card for each app with a `[routerLink]` to `/app/{id}` |

**Signals:**

| Signal    | Type            | Description                           |
|-----------|-----------------|---------------------------------------|
| `apps`    | `AppInfo[]`     | Array of `{id, name}` from backend    |
| `loading` | `boolean`       | `true` while fetching, `false` after  |

**Interface:**
```typescript
interface AppInfo {
  id: string;
  name: string;
}
```

---

### `ViewerComponent`

The VNC viewer page for a specific app. Wraps the canvas component with a header bar.

**Lifecycle:**

| Phase        | Action                                             |
|--------------|----------------------------------------------------|
| `ngOnInit`   | Reads `:id` from route, calls `vnc.connect(id)`   |
| `ngOnDestroy` | Calls `vnc.disconnect()`                          |

**Template elements:**

| Element              | Binding                            | Description                          |
|----------------------|------------------------------------|--------------------------------------|
| Back button          | `routerLink="/"`                   | Returns to screen manager            |
| Status pill          | `vnc.connected()`                  | Green/red connection indicator       |
| Lock button          | `vnc.isController()`              | "Take Control" / "Release Control"   |
| Canvas               | `<app-vnc-canvas>`                | Embedded VNC canvas component        |

---

### `VncCanvasComponent`

Renders the H.264 video stream onto an HTML5 Canvas using the WebCodecs `VideoDecoder` API.

**Canvas setup:**
- Fixed dimensions: 1280×720 (matches server resolution)
- CSS `max-width: 100%` with `height: auto` for responsive scaling
- `tabindex="0"` for keyboard focus

**Video decoding pipeline:**

```
Codec config (Uint8Array)
    │
    ▼
VideoDecoder.configure({
    codec: 'avc1.42001e',
    codedWidth: 1280,
    codedHeight: 720,
    description: spsAndPps
})
    │
    ▼
H.264 frame → EncodedVideoChunk → VideoDecoder.decode()
    │
    ▼
output callback: (frame: VideoFrame) => {
    ctx.drawImage(frame, 0, 0)
    frame.close()
}
```

**Input forwarding:**

| Event     | Condition       | Action                                        |
|-----------|-----------------|-----------------------------------------------|
| `click`   | Is controller   | Scale canvas coords to 1280×720, send `click` |
| `keydown` | Is controller   | If `key.length === 1`, send `key`             |

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

**`connect(appId: string)`**

Establishes a WebSocket connection to `ws://host/ws/{appId}`. The `appId` parameter routes the connection to the correct app on the backend.

```typescript
const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
const url = `${protocol}//${location.host}/ws/${appId}`;
```

**Reconnect logic:**

On close, a 2-second timer schedules a new `connect(appId)` call using the stored `currentAppId`. Cleared on successful open or manual disconnect.

**Binary message parsing:**

| First byte | Message type     | Action                              |
|------------|------------------|-------------------------------------|
| `0xFF`     | Codec config     | Extract bytes [1..], invoke config callback |
| Other      | H.264 frame      | Parse flags, timestamp, data, invoke frame callback |

**Public API:**

| Method                      | Description                           |
|-----------------------------|---------------------------------------|
| `connect(appId)`            | Connect to a specific app             |
| `disconnect()`              | Close connection, stop reconnect      |
| `onFrame(callback)`         | Register frame render callback        |
| `onConfig(callback)`        | Register codec config callback        |
| `sendClick(x, y)`           | Send click command                    |
| `sendKey(key)`              | Send key command                      |
| `requestLock()`             | Send lock request                     |
| `releaseLock()`             | Send unlock request                   |

---

## TypeScript Interfaces

```typescript
export interface LockStatusMessage {
  type: 'lockStatus';
  locked: boolean;
  you: boolean;
}

export interface H264Frame {
  keyframe: boolean;
  timestamp: number;
  data: Uint8Array;
}

interface AppInfo {
  id: string;
  name: string;
}
```

---

## WebCodecs Browser Support

| Browser        | Minimum Version | Notes                                     |
|----------------|-----------------|-------------------------------------------|
| Chrome         | 94+             | Full support                              |
| Edge           | 94+             | Full support (Chromium-based)             |
| Safari         | 16.4+           | Full support                              |
| Firefox        | Behind flag     | `dom.media.webcodecs.enabled`             |
| Opera          | 80+             | Full support (Chromium-based)             |

---

## Development Proxy

`proxy.conf.json` configures the Angular dev server to proxy both WebSocket and REST API requests:

```json
{
  "/ws": {
    "target": "https://localhost:8443",
    "ws": true,
    "secure": false,
    "changeOrigin": true
  },
  "/api": {
    "target": "https://localhost:8443",
    "secure": false,
    "changeOrigin": true
  }
}
```

| Route   | Purpose                                      |
|---------|----------------------------------------------|
| `/ws`   | Proxies WebSocket upgrade to backend (WSS)   |
| `/api`  | Proxies REST API calls (app listing)         |

---

## Production Deployment

Build the Angular app:

```bash
cd frontend
npx ng build
```

The output in `dist/frontend/browser/` can be:

1. **Served by Spring Boot** — Copy contents to `backend/src/main/resources/static/` and rebuild.
2. **Served by a reverse proxy** (nginx, Caddy) — Proxy `/ws` and `/api` to the backend, serve static files directly.
3. **Served by any static file server** — The WebSocket URL auto-detects the host via `location.host`.
