# VNC Streaming System — Setup Guide

## Prerequisites

| Tool       | Version  | Check              |
|------------|----------|--------------------|
| Java (JDK) | 21+     | `java -version`    |
| Gradle     | 8.10+   | `gradle -v`        |
| Node.js    | 20+     | `node -v`          |
| npm        | 10+     | `npm -v`           |

### macOS (Homebrew)

```bash
brew install openjdk@21 gradle node
```

If `openjdk@21` is keg-only, export it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Add this to `~/.zshrc` to persist across sessions.

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
- Auto-generate a self-signed PKCS12 keystore at `src/main/resources/keystore.p12` via `keytool`
- Compile all Java sources
- Package the Spring Boot fat JAR

### 2. Run

```bash
./gradlew bootRun
```

The backend starts on **`https://localhost:8443`** with:
- A Swing JFrame (1280x720) with animated bouncing balls
- A WSS WebSocket endpoint at `/ws`
- 30 FPS tile-based screen capture and broadcast

### 3. Verify

Visit `https://localhost:8443` in a browser and accept the self-signed certificate warning. You should see a blank page (no static content is served). The Swing window should be visible on your desktop.

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

1. **View** — The VNC canvas renders the Swing application in real time at ~30 FPS.
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
| `TILE_SIZE`            | 32     | Tile dimensions in pixels (32x32)              |
| `CAPTURE_INTERVAL_MS`  | 33     | Capture period (~30 FPS)                       |
| `JPEG_QUALITY`         | 0.6    | JPEG compression quality (0.0–1.0)             |
| `FULL_FRAME_THRESHOLD` | 0.4    | Fraction of changed tiles that triggers a full frame |
| `FORCE_FULL_INTERVAL`  | 300    | Force a full frame every N captures (~10s)     |

### WebSocket Limits (`WebSocketConfig`)

| Setting                     | Value    | Description                  |
|-----------------------------|----------|------------------------------|
| Max text message buffer     | 2 MB     | Accommodates full-frame JSON |
| Max binary message buffer   | 2 MB     | Safety margin                |
| Max session idle timeout    | 1 hour   | Keep-alive for viewers       |

---

## Troubleshooting

### "Canvas stays black"

- Ensure the backend is running and the Swing window is visible on the desktop.
- Check browser DevTools → Console for WebSocket connection errors.
- If using the production build (not dev server), you must accept the self-signed cert at `https://localhost:8443` first.

### "WebSocket connection refused"

- Verify the backend started successfully on port 8443.
- If running the frontend dev server, ensure `proxy.conf.json` is configured and the `angular.json` serve target includes `"proxyConfig": "proxy.conf.json"`.

### "Cannot find Java" during build

- Ensure `JAVA_HOME` points to a JDK 21 installation.
- On macOS with Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

### "Take Control button is always disabled"

- Another client already holds the lock. Only one controller is allowed at a time.
- If the previous controller disconnected uncleanly, the server auto-unlocks after detecting the closed session.
