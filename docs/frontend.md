# Frontend Documentation

## Technology Stack

- **Angular 19.2** (standalone components, signals, zone.js)
- **TypeScript 5.7**
- **Native WebSocket API** (no RxJS WebSocket wrapper)
- **WebCodecs API** (`VideoDecoder`) for hardware-accelerated H.264 decoding
- **Canvas 2D API** for video frame rendering

## Source Structure

```
frontend/src/app/
├── app.component.ts            # Root component (layout + lock button)
├── app.component.html          # Root template
├── app.component.css           # Root styles
├── app.config.ts               # Application bootstrap config
├── services/
│   └── vnc.service.ts          # WebSocket + H.264 frame parsing + state
└── components/
    └── vnc-canvas/
        └── vnc-canvas.component.ts  # WebCodecs decoder + canvas renderer + input forwarding
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
    codec: 'avc1.42001e',       // H.264 Baseline Level 3.0
    codedWidth: 1280,
    codedHeight: 720,
    description: spsAndPps      // AVCC format from server
})
    │
    ▼
H.264 frame (Uint8Array) → EncodedVideoChunk
    │                          type: 'key' | 'delta'
    │                          timestamp: microseconds
    ▼
VideoDecoder.decode(chunk)
    │
    ▼
output callback: (frame: VideoFrame) => {
    ctx.drawImage(frame, 0, 0)  // Canvas 2D natively accepts VideoFrame
    frame.close()               // Release GPU memory
}
```

**Decoder lifecycle:**

1. On receiving a codec config message (first byte 0xFF), the component stores the AVCC data and calls `initDecoder()`
2. `initDecoder()` creates a new `VideoDecoder` with output/error callbacks, then calls `configure()` with the codec string and description
3. Any keyframes received before the decoder is configured are buffered in `pendingFrames` and flushed after configuration
4. Each H.264 frame is wrapped in an `EncodedVideoChunk` and fed to `decode()`
5. On disconnect or component destruction, the decoder is closed to free resources

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
        ├── event.data instanceof ArrayBuffer → handleBinaryMessage()
        └── otherwise → handleTextMessage() for lockStatus
```

**Binary message parsing (`handleBinaryMessage`):**

Distinguishes message types by the first byte:

| First byte | Message type     | Action                              |
|------------|------------------|-------------------------------------|
| `0xFF`     | Codec config     | Extract bytes [1..], invoke `configCallback` |
| Other      | H.264 frame      | Parse flags, timestamp, data, invoke `frameCallback` |

Frame parsing:
1. Read `flags` (uint8) — bit 0 indicates keyframe
2. Read `timestamp` (uint32 big-endian) — milliseconds from encoder start
3. Slice remaining bytes as H.264 access unit data
4. Pass `{ keyframe, timestamp, data }` to the registered frame callback

**Reconnect logic:**

On close, a 2-second timer schedules a new `connect()` call. The timer is cleared on successful open or manual disconnect. Cleanup nullifies all event handlers before closing to prevent the close handler from triggering reconnect during intentional disconnect.

**URL construction:**

```typescript
const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
const url = `${protocol}//${location.host}/ws`;
```

This works in both development (proxied through Angular dev server) and production (same-origin deployment).

**Callbacks:**

| Method                      | Description                                     |
|-----------------------------|-------------------------------------------------|
| `onFrame(callback)`         | Register H.264 frame callback                   |
| `onConfig(callback)`        | Register codec config callback                   |

**Public API:**

| Method                      | Description                           |
|-----------------------------|---------------------------------------|
| `connect()`                 | Establish WebSocket connection        |
| `disconnect()`              | Close connection, stop reconnect      |
| `onFrame(callback)`         | Register frame render callback        |
| `onConfig(callback)`        | Register codec config callback        |
| `sendClick(x, y)`           | Send click command (rounded coords)   |
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
  keyframe: boolean;     // true = IDR frame, false = P-frame
  timestamp: number;     // milliseconds from encoder start
  data: Uint8Array;      // H.264 access unit (AVCC format)
}
```

---

## WebCodecs Browser Support

The frontend requires the **WebCodecs API** for H.264 video decoding:

| Browser        | Minimum Version | Notes                                     |
|----------------|-----------------|-------------------------------------------|
| Chrome         | 94+             | Full support                              |
| Edge           | 94+             | Full support (Chromium-based)             |
| Safari         | 16.4+           | Full support                              |
| Firefox        | Behind flag     | `dom.media.webcodecs.enabled`             |
| Opera          | 80+             | Full support (Chromium-based)             |

The `VideoDecoder` is configured with codec string `'avc1.42001e'` (H.264 Baseline, Level 3.0), which has the broadest hardware decoder support across devices.

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
