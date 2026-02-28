import { Injectable, signal } from '@angular/core';

export interface TileData {
  x: number;
  y: number;
  data: string;
}

export interface FrameMessage {
  type: 'frame';
  full: boolean;
  tiles: TileData[];
}

export interface LockStatusMessage {
  type: 'lockStatus';
  locked: boolean;
  you: boolean;
}

type ServerMessage = FrameMessage | LockStatusMessage;

@Injectable({ providedIn: 'root' })
export class VncService {
  private ws: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private frameCallback: ((msg: FrameMessage) => void) | null = null;

  readonly connected = signal(false);
  readonly isController = signal(false);
  readonly isLocked = signal(false);

  connect(): void {
    this.cleanup();

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${location.host}/ws`;

    const ws = new WebSocket(url);

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
      try {
        const msg: ServerMessage = JSON.parse(event.data);
        this.handleMessage(msg);
      } catch {
        // ignore malformed messages
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

  onFrame(callback: (msg: FrameMessage) => void): void {
    this.frameCallback = callback;
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

  private handleMessage(msg: ServerMessage): void {
    switch (msg.type) {
      case 'frame':
        this.frameCallback?.(msg as FrameMessage);
        break;
      case 'lockStatus': {
        const lock = msg as LockStatusMessage;
        this.isLocked.set(lock.locked);
        this.isController.set(lock.you);
        break;
      }
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
