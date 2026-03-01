# VNC Streaming System — Setup Guide

## Prerequisites

| Tool         | Version  | Check              | Required |
|--------------|----------|--------------------|----------|
| Java (JDK)   | 21+     | `java -version`    | Yes      |
| Gradle       | 8.10+   | `gradle -v`        | Yes      |
| Node.js      | 20+     | `node -v`          | Yes      |
| npm          | 10+     | `npm -v`           | Yes      |

### macOS (Homebrew)

```bash
brew install openjdk@21 gradle node
```

If `openjdk@21` is keg-only, export it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Add this to `~/.zshrc` to persist across sessions.

### FFmpeg / H.264 Encoding

The backend uses **JavaCV** (bundled FFmpeg natives via `javacv-platform`) for H.264 video encoding. No separate FFmpeg installation is required — the Gradle dependency includes platform-specific native libraries for macOS (ARM + Intel), Linux, and Windows.

The bundled encoder is **libopenh264** (Cisco's open-source H.264 codec). If `libx264` is available on the system, it will be preferred automatically.

---

## Project Structure

```
vnc/
├── backend/                    # Spring Boot 3.3 + Java 21
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradlew / gradlew.bat
│   └── src/main/
│       ├── java/com/vnc/
│       │   ├── service/        # AppRegistry, AppInstance, per-app services
│       │   ├── controller/     # REST API (AppController)
│       │   ├── swing/          # SwingApp, AnimatedPanel
│       │   └── websocket/      # VncWebSocketHandler
│       └── resources/
│           ├── application.yml
│           └── keystore.p12    # Auto-generated
├── frontend/                   # Angular 19
│   ├── angular.json
│   ├── package.json
│   ├── proxy.conf.json
│   └── src/app/
│       ├── services/           # VncService
│       └── components/
│           ├── screen-manager/ # App selection grid
│           ├── viewer/         # VNC viewer (header + canvas)
│           └── vnc-canvas/     # Canvas renderer (WebCodecs)
└── docs/                       # Documentation
```

---

## Backend Setup

### 1. Build

```bash
cd backend
./gradlew build
```

This will:
- Download Gradle 8.10 (first run only via wrapper)
- Download JavaCV + FFmpeg natives (~300 MB, cached in Gradle home)
- Auto-generate a self-signed PKCS12 keystore at `src/main/resources/keystore.p12` via `keytool`
- Compile all Java sources
- Package the Spring Boot fat JAR

### 2. Run

```bash
./gradlew bootRun
```

The backend starts on **`https://localhost:8443`** with:
- **3 Swing JFrames** (1280×720 each), each with animated bouncing balls
- A WSS WebSocket endpoint at `/ws/{appId}` for per-app streaming
- A REST endpoint at `GET /api/apps` listing available apps
- 20 FPS H.264-encoded screen capture per app via binary WebSocket frames

### 3. Verify

Check the logs for 3 app instances starting:
```
INFO  c.v.service.AppInstance - App 'Bouncing Balls' (id=1) started – 50ms capture interval
INFO  c.v.service.AppInstance - App 'Bouncing Balls 2' (id=2) started – 50ms capture interval
INFO  c.v.service.AppInstance - App 'Bouncing Balls 3' (id=3) started – 50ms capture interval
INFO  c.v.service.AppRegistry - AppRegistry started – 3 apps
```

Test the REST API:
```bash
curl -k https://localhost:8443/api/apps
```
Returns: `[{"id":"1","name":"Bouncing Balls"},{"id":"2","name":"Bouncing Balls 2"},{"id":"3","name":"Bouncing Balls 3"}]`

---

## Frontend Setup

### 1. Install Dependencies

```bash
cd frontend
npm install
```

### 2. Run (Development)

```bash
npx ng serve
```

Opens on **`http://localhost:4200`**.

The Angular dev server proxies `/ws` and `/api` to `https://localhost:8443` automatically via `proxy.conf.json`.

### 3. Build (Production)

```bash
npx ng build
```

Output goes to `frontend/dist/frontend/`. These static files can be served by any web server or copied into the Spring Boot `static/` resources for single-origin deployment.

### Browser Requirements

The frontend uses the **WebCodecs API** (`VideoDecoder`) for hardware-accelerated H.264 decoding. This requires:
- Chrome 94+ / Edge 94+ / Opera 80+
- Firefox (behind `dom.media.webcodecs.enabled` flag as of 2026)
- Safari 16.4+

---

## Running Both Together

Open two terminals:

**Terminal 1 — Backend:**
```bash
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew bootRun
```

**Terminal 2 — Frontend:**
```bash
cd frontend
npx ng serve
```

Then open **`http://localhost:4200`** — you will see the **Screen Manager** with 3 available apps.

---

## Usage

1. **Screen Manager** — On load, the frontend shows a card grid of available Swing apps fetched from `GET /api/apps`. Click any card to connect.
2. **View** — The viewer page streams the selected Swing app in real time at ~20 FPS via H.264 video.
3. **Take Control** — Click "Take Control". Only one client per app may hold the lock at a time.
4. **Interact** — While holding control, click on the canvas to click Swing components. Type on the canvas to send key presses.
5. **Release Control** — Click "Release Control" or close the tab (auto-releases).
6. **Back** — Click the "Back" button in the viewer header to return to the screen manager. This disconnects from the current app.

---

## Configuration Reference

### Backend (`application.yml`)

| Property                     | Default           | Description                              |
|------------------------------|-------------------|------------------------------------------|
| `server.port`                | `8443`            | HTTPS listen port                        |
| `server.ssl.key-store`       | `classpath:keystore.p12` | PKCS12 keystore location          |
| `server.ssl.key-store-password` | `changeit`     | Keystore password                        |
| `logging.level.com.vnc`      | `INFO`            | Application log level                    |

### App Configuration (compile-time in `AppRegistry`)

The number and names of apps are hardcoded in `AppRegistry.start()`. To add/remove apps, edit the `createApp()` calls.

### Capture Tuning (compile-time constants in `AppInstance`)

| Constant               | Value  | Description                                    |
|------------------------|--------|------------------------------------------------|
| `FPS`                  | 20     | Target capture frame rate                      |
| `CAPTURE_INTERVAL_MS`  | 50     | Capture period (1000 / FPS)                    |

### H.264 Encoder Settings (compile-time in `H264EncoderService`)

| Setting              | Value          | Description                                         |
|----------------------|----------------|-----------------------------------------------------|
| Codec                | libx264 or libopenh264 | Prefers libx264 if available                 |
| Profile              | Baseline       | Maximum browser compatibility                       |
| Preset               | ultrafast      | Lowest encoding latency (libx264 only)              |
| Tune                 | zerolatency    | Disables look-ahead buffering (libx264 only)        |
| CRF                  | 28             | Quality level (libx264 only)                        |
| Bitrate              | 400 kbps       | Target bitrate (libopenh264 only)                   |
| GOP size             | 40 frames      | Keyframe every 2 seconds at 20 FPS                  |
| B-frames             | 0              | No bidirectional frames (lowest latency)            |

### WebSocket Limits (`WebSocketConfig`)

| Setting                     | Value    | Description                              |
|-----------------------------|----------|------------------------------------------|
| Max text message buffer     | 2 MB     | For lock status JSON                     |
| Max binary message buffer   | 2 MB     | Accommodates H.264 keyframes             |
| Max session idle timeout    | 1 hour   | Keep-alive for viewers                   |

---

## Bandwidth

The H.264 video codec uses temporal compression (inter-frame prediction), dramatically reducing bandwidth compared to per-tile JPEG encoding:

| Metric              | Old (JPEG tiles)          | New (H.264)                |
|---------------------|---------------------------|----------------------------|
| Frame size (avg)    | ~30 KB                    | ~1-3 KB (delta frames)     |
| Bandwidth/client    | ~600 KB/s                 | ~40 KB/s                   |
| 20 clients          | ~12 MB/s                  | ~800 KB/s                  |
| Keyframe size       | ~30 KB (full frame)       | ~15-25 KB (every 2s)       |

Note: each app has its own independent encoder and broadcast pipeline, so total bandwidth scales with the number of apps that have active viewers.

---

## Troubleshooting

### "Canvas stays black"

- Ensure the backend is running and the Swing windows are visible on the desktop.
- Check browser DevTools → Console for `VideoDecoder error` messages.
- Verify your browser supports the WebCodecs API (Chrome 94+, Safari 16.4+).

### "VideoDecoder error: NotSupportedError"

- The browser may not support the H.264 Baseline profile. Try Chrome or Edge.
- Check that the backend encoder is producing valid extradata (look for `extradata XX bytes` in the logs).

### "WebSocket connection refused"

- Verify the backend started successfully on port 8443.
- If running the frontend dev server, ensure `proxy.conf.json` is configured and the `angular.json` serve target includes `"proxyConfig": "proxy.conf.json"`.

### "Screen Manager shows empty / 'Loading screens...'"

- Verify the backend is running and the `/api/apps` endpoint responds.
- Check the browser console for fetch errors. The proxy must forward `/api` to the backend.

### "Cannot find Java" during build

- Ensure `JAVA_HOME` points to a JDK 21 installation.
- On macOS with Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

### "Take Control button is always disabled"

- Another client already holds the lock for that app. Each app has its own independent lock.
- If the previous controller disconnected uncleanly, the server auto-unlocks after detecting the closed session.
