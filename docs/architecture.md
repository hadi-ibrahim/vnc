# Architecture Overview

## System Diagram

```
┌─────────────────────────────────────────────────────┐
│                   Spring Boot JVM                    │
│                                                     │
│  ┌──────────┐     ┌────────────────┐                │
│  │ SwingApp │────▶│ AnimatedPanel  │  (EDT, 60 FPS) │
│  │ (JFrame) │     └────────────────┘                │
│  └────┬─────┘                                       │
│       │ paint(g)                                    │
│       ▼                                             │
│  ┌──────────────┐    ┌────────────────────┐         │
│  │CaptureService│───▶│ H264EncoderService │         │
│  │ (20 FPS loop)│    │ (JavaCV/FFmpeg)    │         │
│  └──────────────┘    └────────┬───────────┘         │
│                               │                     │
│                               │ encoded H.264 bytes │
│                               ▼                     │
│                       ┌──────────────────┐          │
│                       │ BroadcastService │          │
│                       │ (virtual threads)│          │
│                       └───────┬──────────┘          │
│                               │                     │
│                  binary WSS frames (+ text for lock)│
│                               │                     │
│  ┌────────────────┐   ┌──────────────────┐          │
│  │RemoteControl   │   │VncWebSocketHandler│◀── WSS  │
│  │Service         │   │  (routes msgs)   │          │
│  └────────────────┘   └──────────────────┘          │
│       ▲                       │                     │
│       │ click/key             │ lock/unlock          │
│       │                       ▼                     │
│       │               ┌──────────────────┐          │
│       └───────────────│ControlLockService│          │
│                       │ (AtomicReference)│          │
│                       └──────────────────┘          │
└─────────────────────────────────────────────────────┘
         │ WSS (binary H.264 frames + text JSON)
         ▼
┌─────────────────────────────────────────────────────┐
│              Browser (Angular 19)                    │
│                                                     │
│  ┌──────────┐    ┌───────────────────┐              │
│  │VncService│───▶│VncCanvasComponent │              │
│  │(WebSocket│    │(Canvas 1280×720)  │              │
│  │ binary + │    │ WebCodecs         │              │
│  │ signals) │    │ VideoDecoder      │              │
│  └──────────┘    └───────────────────┘              │
└─────────────────────────────────────────────────────┘
```

## Component Responsibilities

### Backend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `VncApplication`       | Entry point. Disables AWT headless mode for Swing.     |
| `SwingApp`             | SmartLifecycle (phase 0). Creates JFrame on EDT.       |
| `AnimatedPanel`        | 60 FPS Swing Timer animation with bouncing balls.      |
| `H264EncoderService`   | Wraps FFmpeg's H.264 encoder (libx264 or libopenh264) via JavaCV. Handles RGB→YUV420P conversion, Annex B→AVCC format conversion. |
| `CaptureService`       | SmartLifecycle (phase 1). 20 FPS scheduled capture, feeds frames to H264Encoder. |
| `BroadcastService`     | Client session registry. Sends binary H.264 frames + codec config, JSON text for lock status, via virtual threads. |
| `ControlLockService`   | Single-controller lock via CAS on AtomicReference.     |
| `RemoteControlService` | `getSnapshot()`, `click(x,y)`, `press(key)`. EDT-safe. |
| `VncWebSocketHandler`  | WSS endpoint. Routes incoming text messages, manages connect/disconnect lifecycle. |
| `WebSocketConfig`      | Registers `/ws` handler. Sets buffer and timeout limits.|

### Frontend

| Component              | Responsibility                                         |
|------------------------|--------------------------------------------------------|
| `AppComponent`         | Top-level layout. Connection status, lock button.      |
| `VncCanvasComponent`   | Canvas renderer. Uses WebCodecs `VideoDecoder` for hardware-accelerated H.264 decoding. Click/key forwarding. |
| `VncService`           | WebSocket lifecycle, binary H.264 frame parsing, codec config handling, auto-reconnect, Angular signals for reactive state. |

## Data Flow

### Frame Pipeline (Server → Clients)

```
EDT repaint (60 FPS)
    │
    ▼
CaptureService.captureAndBroadcast() — scheduled every 50ms (20 FPS)
    │
    ├── SwingUtilities.invokeAndWait()  → paint content pane into BufferedImage
    │
    ├── H264EncoderService.encode(bufferedImage)
    │   ├── Extract pixel data (DataBufferInt → BGRA ByteBuffer)
    │   ├── sws_scale: BGRA → YUV420P
    │   ├── avcodec_send_frame + avcodec_receive_packet
    │   ├── annexBToAvccPacket: convert NAL units to length-prefixed format
    │   └── Returns encoded byte[] + isKeyframe flag
    │
    └── BroadcastService.broadcastFrame(encoded, isKeyframe, timestamp)
        │
        ├── Build binary frame: [flags:1][timestamp:4][H.264 data:N]
        ├── Wrap in BinaryMessage once (shared across all clients)
        ├── Cache if keyframe (for new client init)
        │
        └── For each client:
            ├── AtomicBoolean.compareAndSet(false, true) → if fails, DROP frame
            └── Virtual thread: synchronized(session) { sendMessage() }
                └── finally: inFlight.set(false)
```

### Client Frame Receive

```
WebSocket binary message (ArrayBuffer)
    │
    ├── First byte == 0xFF → Codec config (SPS+PPS in AVCC format)
    │   └── VncService stores config, VncCanvasComponent configures VideoDecoder
    │
    └── First byte != 0xFF → H.264 frame
        ├── VncService parses: flags (keyframe), timestamp, H.264 data
        │
        ▼
    VncCanvasComponent.onH264Frame()
        │
        ├── Create EncodedVideoChunk(type: key|delta, timestamp, data)
        ├── VideoDecoder.decode(chunk)
        │
        └── VideoDecoder output callback:
            ├── ctx.drawImage(videoFrame, 0, 0)
            └── videoFrame.close()
```

### Control Pipeline (Client → Server)

```
Browser canvas click/keydown
    │
    ├── VncService.sendClick(x, y) / sendKey(key)
    │   └── WebSocket.send(JSON text)
    │
    ▼
VncWebSocketHandler.handleTextMessage()
    │
    ├── Check: controlLockService.isController(sessionId)?
    │   └── If not controller → silently ignored
    │
    └── RemoteControlService.click(x, y) / press(key)
        └── SwingUtilities.invokeLater()  → dispatch MouseEvent / KeyEvent on EDT
```

### Lock Protocol

```
Client sends:   { "type": "lock" }  (text frame)
    │
    ▼
Server: controlLockService.tryLock(sessionId)  — CAS(null → sessionId)
    │
    ├── Success → broadcast lockStatus (text JSON) to ALL clients
    │             (each gets personalized { you: true/false })
    │
    └── Failure → no response (another client holds the lock)

Client disconnect:
    │
    ▼
Server: controlLockService.unlock(sessionId)  — CAS(sessionId → null)
    └── broadcast lockStatus (unlocked) to remaining clients
```

## Lifecycle Ordering

Spring `SmartLifecycle` phases control startup order:

| Phase | Component       | What Happens                                 |
|-------|-----------------|----------------------------------------------|
| 0     | `SwingApp`      | `invokeAndWait()` → JFrame created and visible |
| 1     | `CaptureService`| H264Encoder started, `scheduleAtFixedRate()` → capture loop starts |

The 200ms initial delay on the capture scheduler provides additional safety margin after the JFrame becomes visible.

## Bandwidth Comparison

```
Old (JPEG tiles):   30 KB/frame × 20 FPS = 600 KB/s/client  (20 clients = 12 MB/s)
New (H.264):        ~2 KB/frame × 20 FPS =  40 KB/s/client  (20 clients = 800 KB/s)
```

H.264 achieves this through **inter-frame prediction**: delta frames encode only motion vectors and residuals relative to the previous frame, rather than re-encoding each changed region from scratch.
