import { Injectable, signal } from '@angular/core';

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

@Injectable({ providedIn: 'root' })
export class VncService {
  private ws: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private frameCallback: ((frame: H264Frame) => void) | null = null;
  private configCallback: ((config: Uint8Array) => void) | null = null;

  readonly connected = signal(false);
  readonly isController = signal(false);
  readonly isLocked = signal(false);

  connect(): void {
    this.cleanup();

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${location.host}/ws`;

    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
      this.connected.set(true);
      this.clearReconnect();
    };

    ws.onclose = () => {
      this.connected.set(false);
      this.isController.set(false);
      this.isLocked.set(false);
      this.ws = null;
      this.scheduleReconnect();
    };

    ws.onerror = () => ws.close();

    ws.onmessage = (event: MessageEvent) => {
      if (event.data instanceof ArrayBuffer) {
        this.handleBinaryMessage(event.data);
      } else {
        this.handleTextMessage(event.data);
      }
    };

    this.ws = ws;
  }

  disconnect(): void {
    this.clearReconnect();
    this.cleanup();
    this.connected.set(false);
    this.isController.set(false);
    this.isLocked.set(false);
  }

  onFrame(callback: (frame: H264Frame) => void): void {
    this.frameCallback = callback;
  }

  onConfig(callback: (config: Uint8Array) => void): void {
    this.configCallback = callback;
  }

  sendClick(x: number, y: number): void {
    this.send({ type: 'click', x: Math.round(x), y: Math.round(y) });
  }

  sendKey(key: string): void {
    this.send({ type: 'key', key });
  }

  requestLock(): void {
    this.send({ type: 'lock' });
  }

  releaseLock(): void {
    this.send({ type: 'unlock' });
  }

  private send(msg: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  private handleBinaryMessage(buffer: ArrayBuffer): void {
    const view = new Uint8Array(buffer);
    if (view.length < 1) return;

    const firstByte = view[0];

    if (firstByte === 0xFF) {
      const config = view.slice(1);
      this.configCallback?.(config);
      return;
    }

    if (view.length < 5) return;

    const dataView = new DataView(buffer);
    const keyframe = (firstByte & 1) !== 0;
    const timestamp = dataView.getUint32(1, false);
    const data = view.slice(5);

    this.frameCallback?.({ keyframe, timestamp, data });
  }

  private handleTextMessage(text: string): void {
    try {
      const msg = JSON.parse(text);
      if (msg.type === 'lockStatus') {
        this.isLocked.set(msg.locked);
        this.isController.set(msg.you);
      }
    } catch {
      // ignore malformed messages
    }
  }

  private cleanup(): void {
    if (this.ws) {
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws.onmessage = null;
      this.ws.close();
      this.ws = null;
    }
  }

  private scheduleReconnect(): void {
    this.clearReconnect();
    this.reconnectTimer = setTimeout(() => this.connect(), 2000);
  }

  private clearReconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
