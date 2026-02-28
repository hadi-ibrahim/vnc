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
│  │ single platform thd  │   scheduleAtFixedRate 33ms │
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

Pre-allocated `int[]` arrays (`currentPixels`, `previousPixels`) are only accessed from the capture thread, requiring no synchronization.

`BufferedImage` double-buffering uses reference swaps (not concurrent writes):
```java
BufferedImage temp = previousFrame;
previousFrame = currentFrame;
currentFrame = temp;
```
Both images are only written inside the `capturing` guard, which is single-threaded.

### BroadcastService

**Client registry:** `ConcurrentHashMap<String, ClientSession>` provides safe concurrent iteration during broadcast while Tomcat threads add/remove clients.

**Frame serialization:** JSON serialization happens once per frame on the capture thread. The resulting `TextMessage` is immutable and safely shared across virtual send threads.

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

The `synchronized(session)` block prevents overlapping writes to the same WebSocket session from `broadcast()` and `sendTo()` (used for lock status messages).

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
| `BufferedImage` (current/previous) | CaptureService field  | Service shutdown               |
| Tile `BufferedImage` (32x32)       | Per-capture, method local | After JPEG encode completes  |
| `TextMessage` (frame JSON)         | Per-broadcast           | After all virtual sends finish |
| `lastFullFrame` cache              | `volatile` field        | Replaced by next full frame    |
| `ClientSession`                    | ConcurrentHashMap entry | `removeClient()` call          |

### No Unbounded Growth

- Tile JPEG bytes are allocated per-capture and become garbage after Base64 encoding.
- The `lastFullFrame` cache holds exactly one `TextMessage` at a time.
- Virtual threads are short-lived (one send operation) and exit promptly.
- `ConcurrentHashMap` entries are bounded by the number of connected clients (~20 max).
