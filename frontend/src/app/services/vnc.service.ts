import { Injectable, signal } from '@angular/core';

export interface TileData {
  x: number;
  y: number;
  jpeg: Uint8Array;
}

export interface FrameMessage {
  full: boolean;
  tiles: TileData[];
}

interface LockStatusMessage {
  type: 'lockStatus';
  locked: boolean;
  you: boolean;
}

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
        this.handleBinaryFrame(event.data);
      } else {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'lockStatus') {
            this.isLocked.set(msg.locked);
            this.isController.set(msg.you);
          }
        } catch { /* ignore malformed text */ }
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

  /**
   * Binary frame layout:
   *   [0]      uint8   flags (bit 0 = full)
   *   [1-2]    uint16  tile count (BE)
   *   Per tile:
   *     [0]    uint8   col
   *     [1]    uint8   row
   *     [2-5]  uint32  jpeg length (BE)
   *     [6..]  raw jpeg bytes
   */
  private handleBinaryFrame(buffer: ArrayBuffer): void {
    const view = new DataView(buffer);
    const full = (view.getUint8(0) & 1) !== 0;
    const tileCount = view.getUint16(1, false);

    const tiles: TileData[] = [];
    let offset = 3;

    for (let i = 0; i < tileCount; i++) {
      const x = view.getUint8(offset);
      const y = view.getUint8(offset + 1);
      const jpegLen = view.getUint32(offset + 2, false);
      const jpeg = new Uint8Array(buffer, offset + 6, jpegLen);
      tiles.push({ x, y, jpeg });
      offset += 6 + jpegLen;
    }

    this.frameCallback?.({ full, tiles });
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
