# Concurrency Model

## Thread Topology

```
┌─────────────────────────────────────────────────────────────┐
│                       JVM Threads                            │
│                                                             │
│  ┌──────────────────────────┐                               │
│  │ EDT (AWT EventQueue)     │ ← Swing Timer (16ms) per app  │
│  │                          │ ← invokeAndWait (capture)      │
│  │                          │ ← invokeLater (click/key)      │
│  └──────────────────────────┘                               │
│                                                             │
│  ┌──────────────────────────┐                               │
│  │ app-1-capture (daemon)   │ ← ScheduledExecutorService    │
│  │ app-2-capture (daemon)   │   scheduleAtFixedRate 50ms    │
│  │ app-3-capture (daemon)   │   one thread per app          │
│  └──────────────────────────┘                               │
│                                                             │
│  ┌──────────────────────────┐                               │
│  │ Virtual threads          │ ← newVirtualThreadPerTask     │
│  │ (send executors)         │   per-app broadcast service   │
│  │ max ~20 per app          │   bounded by inFlight CAS     │
│  └──────────────────────────┘                               │
│                                                             │
│  ┌──────────────────────────┐                               │
│  │ Tomcat NIO threads       │ ← WebSocket I/O               │
│  │ (platform threads)       │   handleTextMessage()          │
│  └──────────────────────────┘                               │
└─────────────────────────────────────────────────────────────┘
```

## Multi-App Thread Isolation

Each `AppInstance` owns its own:
- **Capture thread** — Named `app-{id}-capture`, runs as a single daemon thread via `ScheduledExecutorService`
- **Send executor** — `Executors.newVirtualThreadPerTaskExecutor()` for broadcasting frames to its clients
- **BroadcastService** — Independent client registry and in-flight tracking

The **EDT is shared** across all apps (Swing has a single event dispatch thread). All Swing operations (`invokeAndWait`, `invokeLater`) serialize on the EDT. Capture threads from different apps may contend briefly on the EDT, but `invokeAndWait` calls are short (a single `paint()` into a `BufferedImage`).

## Thread Safety Analysis

### Swing / EDT

All Swing component access is confined to the EDT:

| Operation                   | Thread             | Mechanism              |
|-----------------------------|--------------------|------------------------|
| JFrame creation (per app)   | EDT                | `invokeAndWait()` in `SwingApp.start()` |
| Animation tick + repaint    | EDT                | `javax.swing.Timer` (fires on EDT)      |
| Frame capture (paint)       | EDT                | `invokeAndWait()` from capture thread   |
| Click dispatch              | EDT                | `invokeLater()` from Tomcat NIO thread  |
| Key dispatch                | EDT                | `invokeLater()` from Tomcat NIO thread  |
| JFrame disposal             | EDT                | `invokeLater()` in `SwingApp.stop()`    |

Each `SwingApp.frame` field is `volatile`, ensuring visibility from capture threads and Tomcat threads.

### H264EncoderService

Each app has its own encoder, called only from that app's capture thread. All public methods are `synchronized` as a safety net, but contention never occurs in practice since each encoder is single-caller.

### AppInstance Capture Loop

Each app's capture loop runs on its own daemon thread. Concurrent re-entry is prevented by `AtomicBoolean capturing`:

```java
if (!capturing.compareAndSet(false, true)) return;
try {
    // ... capture + encode + broadcast ...
} finally {
    capturing.set(false);
}
```

### BroadcastService

Each app has its own `BroadcastService` with its own `ConcurrentHashMap<String, ClientSession>`.

**Frame serialization:** Built once on the capture thread. The `BinaryMessage` wraps an immutable `byte[]` shared across virtual send threads.

**Per-client send:** `AtomicBoolean inFlight` per client session:

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

### VncWebSocketHandler

The handler maintains a `ConcurrentMap<String, AppInstance>` mapping session IDs to their app. This map is:
- **Written to** on connection (Tomcat NIO thread)
- **Read from** on every message (Tomcat NIO thread)
- **Removed from** on disconnect (Tomcat NIO thread)

The `ConcurrentHashMap` provides safe concurrent access.

### ControlLockService

Per-app lock via `AtomicReference<String>`. Lock-free and linearizable.

## Memory Safety

### Bounded Queues

| Component                 | Queue Type                        | Bound                          |
|---------------------------|-----------------------------------|--------------------------------|
| Capture scheduler (×3)    | `ScheduledExecutorService`        | Single thread per app          |
| Send executor (×3)        | Virtual thread per task           | Bounded by `inFlight` CAS     |
| WebSocket buffers         | Tomcat internal                   | 2 MB per session               |

### Object Lifetimes

| Object              | Scope                              | GC Eligible When                |
|---------------------|------------------------------------|---------------------------------|
| `AppInstance`       | `AppRegistry` map entry            | `AppRegistry.stop()`            |
| `BufferedImage` (×3)| `AppInstance` field                | App shutdown                    |
| `byte[]` H.264 packet | Per-capture per app              | After all virtual sends finish  |
| `BinaryMessage` (cached) | Per-app `volatile` field       | Replaced by next keyframe       |
| `ClientSession`     | Per-app `ConcurrentHashMap` entry  | `removeClient()` call           |
| FFmpeg native resources | Per-app `H264EncoderService`   | `stop()` call                   |

### No Unbounded Growth

- Each app's encoded bytes are allocated per-capture and become garbage after broadcast.
- Each app caches exactly one keyframe and one codec config message.
- Virtual threads are short-lived (one send) and exit promptly.
- Client sessions per app are bounded by connected viewers.
- FFmpeg native memory is pre-allocated per app and reused across frames.

### Native Resource Management

Each `AppInstance` has its own `H264EncoderService` with its own native FFmpeg resources:

| Resource           | Allocated          | Freed                    |
|--------------------|--------------------|--------------------------|
| `AVCodecContext`   | `start()`          | `avcodec_free_context()` in `stop()` |
| `SwsContext`       | `start()`          | `sws_freeContext()` in `stop()`      |
| `AVFrame` (rgb)    | `start()`          | `av_frame_free()` in `stop()`        |
| `AVFrame` (yuv)    | `start()`          | `av_frame_free()` in `stop()`        |
| `AVPacket`         | `start()`          | `av_packet_free()` in `stop()`       |

All native resources are freed in `AppInstance.stop()`, called by `AppRegistry.stop()` during Spring context shutdown.
