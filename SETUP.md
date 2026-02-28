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
│       ├── java/com/vnc/       # Application source
│       └── resources/
│           ├── application.yml
│           └── keystore.p12    # Auto-generated
├── frontend/                   # Angular 19
│   ├── angular.json
│   ├── package.json
│   ├── proxy.conf.json
│   └── src/app/
│       ├── services/           # VncService
│       └── components/         # VncCanvasComponent
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
- A Swing JFrame (1280×720) with animated bouncing balls
- A WSS WebSocket endpoint at `/ws`
- 20 FPS H.264-encoded screen capture broadcast via binary WebSocket frames

### 3. Verify

Visit `https://localhost:8443` in a browser and accept the self-signed certificate warning. You should see a blank page (no static content is served). The Swing window should be visible on your desktop.

Check the logs for encoder initialization:
```
INFO  c.v.service.H264EncoderService - Using H.264 encoder: libopenh264
INFO  c.v.service.H264EncoderService - H.264 encoder started – 1280x720 @ 20 FPS, extradata 30 bytes
```

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

The Angular dev server proxies `/ws` to `wss://localhost:8443` automatically via `proxy.conf.json`, so there are no self-signed certificate issues in the browser.

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

Then open **`http://localhost:4200`** in one or more browser tabs.

---

## Usage

1. **View** — The VNC canvas renders the Swing application in real time at ~20 FPS via H.264 video stream.
2. **Take Control** — Click the "Take Control" button. Only one client may hold the lock at a time. Others see a disabled button.
3. **Interact** — While holding control, click on the canvas to click Swing components (buttons, text field). Type on the canvas (click it first to focus) to send key presses.
4. **Release Control** — Click "Release Control" or close the tab (auto-releases).

---

## Configuration Reference

### Backend (`application.yml`)

| Property                     | Default           | Description                              |
|------------------------------|-------------------|------------------------------------------|
| `server.port`                | `8443`            | HTTPS listen port                        |
| `server.ssl.key-store`       | `classpath:keystore.p12` | PKCS12 keystore location          |
| `server.ssl.key-store-password` | `changeit`     | Keystore password                        |
| `logging.level.com.vnc`      | `INFO`            | Application log level                    |

### Capture Tuning (compile-time constants in `CaptureService`)

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

---

## Troubleshooting

### "Canvas stays black"

- Ensure the backend is running and the Swing window is visible on the desktop.
- Check browser DevTools → Console for `VideoDecoder error` messages.
- Verify your browser supports the WebCodecs API (Chrome 94+, Safari 16.4+).
- If using the production build (not dev server), you must accept the self-signed cert at `https://localhost:8443` first.

### "VideoDecoder error: NotSupportedError"

- The browser may not support the H.264 Baseline profile. Try Chrome or Edge.
- Check that the backend encoder is producing valid extradata (look for `extradata XX bytes` in the logs).

### "WebSocket connection refused"

- Verify the backend started successfully on port 8443.
- If running the frontend dev server, ensure `proxy.conf.json` is configured and the `angular.json` serve target includes `"proxyConfig": "proxy.conf.json"`.

### "Cannot find Java" during build

- Ensure `JAVA_HOME` points to a JDK 21 installation.
- On macOS with Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

### "Take Control button is always disabled"

- Another client already holds the lock. Only one controller is allowed at a time.
- If the previous controller disconnected uncleanly, the server auto-unlocks after detecting the closed session.
