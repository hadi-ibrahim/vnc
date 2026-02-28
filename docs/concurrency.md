# Concurrency Model

## Thread Topology

```
┌─────────────────────────────────────────────────────┐
│                    JVM Threads                       │
│                                                     │
│  ┌──────────────────────┐                           │
│  │ EDT (AWT EventQueue) │ ← Swing Timer (16ms)      │
│  │                      │ ← invokeAndWait (capture)  │
│  │                      │ ← invokeLater (click/key)  │
│  └──────────────────────┘                           │
│                                                     │
│  ┌──────────────────────┐                           │
│  │ vnc-capture (daemon) │ ← ScheduledExecutorService │
│  │ single platform thd  │   scheduleAtFixedRate 50ms │
│  └──────────────────────┘                           │
│                                                     │
│  ┌──────────────────────┐                           │
│  │ Virtual threads      │ ← newVirtualThreadPerTask  │
│  │ (send executor)      │   one per in-flight send   │
│  │ max ~20 concurrent   │   bounded by inFlight CAS  │
│  └──────────────────────┘                           │
│                                                     │
│  ┌──────────────────────┐                           │
│  │ Tomcat NIO threads   │ ← WebSocket I/O            │
│  │ (platform threads)   │   handleTextMessage()      │
│  └──────────────────────┘                           │
└─────────────────────────────────────────────────────┘
```

## Thread Safety Analysis

### Swing / EDT

All Swing component access is confined to the EDT:

| Operation                   | Thread             | Mechanism              |
|-----------------------------|--------------------|------------------------|
| JFrame creation             | EDT                | `invokeAndWait()` in `SwingApp.start()` |
| Animation tick + repaint    | EDT                | `javax.swing.Timer` (fires on EDT)      |
| Frame capture (paint)       | EDT                | `invokeAndWait()` from capture thread   |
| Click dispatch              | EDT                | `invokeLater()` from Tomcat NIO thread  |
| Key dispatch                | EDT                | `invokeLater()` from Tomcat NIO thread  |
| JFrame disposal             | EDT                | `invokeLater()` in `SwingApp.stop()`    |

The `SwingApp.frame` field is `volatile`, ensuring visibility from the capture thread and Tomcat threads that read it.

### H264EncoderService

The encoder is **single-threaded by design** — only called from the `vnc-capture` thread within the `CaptureService` capture loop. All public methods are `synchronized` as a safety net, but contention never occurs in practice.

Native FFmpeg resources (`AVCodecContext`, `SwsContext`, `AVFrame`, `AVPacket`) are allocated once at `start()` and freed at `stop()`. They are never accessed concurrently.

The Annex B → AVCC conversion methods are stateless and thread-safe.

### CaptureService

The capture loop runs on a single daemon thread (`vnc-capture`). Concurrent re-entry is prevented by `AtomicBoolean capturing`:

```java
if (!capturing.compareAndSet(false, true)) return;
try {
    // ... capture logic ...
} finally {
    capturing.set(false);
}
```

The `captureBuffer` (`BufferedImage`) is only written to inside the `capturing` guard, which is single-threaded. The encoded `byte[]` is immutable once constructed and safely shared with virtual send threads.

### BroadcastService

**Client registry:** `ConcurrentHashMap<String, ClientSession>` provides safe concurrent iteration during broadcast while Tomcat threads add/remove clients.

**Frame serialization:** H.264 encoded bytes are packed into a binary frame (5-byte header + payload) once on the capture thread. The resulting `BinaryMessage` wraps an immutable `byte[]` and is safely shared across virtual send threads.

**Codec config + keyframe caching:** Both are stored as `volatile BinaryMessage` fields. The capture thread writes them; virtual send threads and Tomcat threads (in `addClient`) read them. Volatile ensures visibility.

**Per-client send:** Each `ClientSession` has an `AtomicBoolean inFlight`:

```
Capture thread:                     Virtual send thread:
  │                                   │
  ├─ inFlight.CAS(false→true) ✓      │
  ├─ submit virtual thread ──────────▶│
  │                                   ├─ synchronized(session) {
  │  [next broadcast cycle]           │      session.sendMessage()
  ├─ inFlight.CAS(false→true) ✗      │  }
  │  → frame DROPPED                 ├─ inFlight.set(false)
  │                                   │
```

The `synchronized(session)` block prevents overlapping writes to the same WebSocket session from `broadcastFrame()` and `sendTo()` (used for lock status text messages).

### ControlLockService

All operations use `AtomicReference.compareAndSet()`:

```java
// Lock:   CAS(null → sessionId)     — only succeeds if unlocked
// Unlock: CAS(sessionId → null)     — only succeeds if this session holds it
// Check:  sessionId.equals(ref.get()) — non-blocking read
```

This is lock-free and linearizable. No mutex contention under load.

## Memory Safety

### Bounded Queues

| Component            | Queue Type                        | Bound                          |
|----------------------|-----------------------------------|--------------------------------|
| Capture scheduler    | `ScheduledExecutorService`        | Single thread, fixed rate      |
| Send executor        | Virtual thread per task           | Bounded by `inFlight` CAS — max 1 task per client |
| WebSocket buffers    | Tomcat internal                   | 2 MB per session (configured)  |

### Object Lifetimes

| Object              | Scope                              | GC Eligible When                |
|---------------------|------------------------------------|---------------------------------|
| `BufferedImage` (captureBuffer) | CaptureService field    | Service shutdown               |
| `byte[]` H.264 packet          | Per-capture             | After all virtual sends finish |
| `BinaryMessage` (cachedKeyframe)| `volatile` field       | Replaced by next keyframe      |
| `BinaryMessage` (cachedCodecConfig)| `volatile` field    | Replaced if encoder restarts   |
| `ClientSession`                | ConcurrentHashMap entry | `removeClient()` call          |
| FFmpeg native resources        | H264EncoderService fields | `stop()` call               |

### No Unbounded Growth

- H.264 encoded bytes are allocated per-capture and become garbage after broadcast completes.
- The `cachedKeyframe` holds exactly one `BinaryMessage` at a time.
- The `cachedCodecConfig` holds exactly one `BinaryMessage` at a time.
- Virtual threads are short-lived (one send operation) and exit promptly.
- `ConcurrentHashMap` entries are bounded by the number of connected clients (~20 max).
- FFmpeg native memory (`AVFrame`, `AVPacket`) is pre-allocated and reused across frames — no per-frame native allocation.

### Native Resource Management

The `H264EncoderService` allocates native FFmpeg resources via JavaCV:

| Resource           | Allocated          | Freed                    |
|--------------------|--------------------|--------------------------|
| `AVCodecContext`   | `start()`          | `avcodec_free_context()` in `stop()` |
| `SwsContext`       | `start()`          | `sws_freeContext()` in `stop()`      |
| `AVFrame` (rgb)    | `start()`          | `av_frame_free()` in `stop()`        |
| `AVFrame` (yuv)    | `start()`          | `av_frame_free()` in `stop()`        |
| `AVPacket`         | `start()`          | `av_packet_free()` in `stop()`       |

All native resources are freed in `stop()`, which is called by `CaptureService.stop()` during Spring context shutdown. The `synchronized` keyword on `stop()` prevents concurrent access during shutdown.
