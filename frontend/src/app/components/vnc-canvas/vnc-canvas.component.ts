import {
  Component,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { VncService, FrameMessage } from '../../services/vnc.service';

@Component({
  selector: 'app-vnc-canvas',
  standalone: true,
  template: `
    <canvas
      #canvas
      width="1280"
      height="720"
      (click)="onClick($event)"
      (keydown)="onKeyDown($event)"
      tabindex="0"
    ></canvas>
  `,
  styles: [
    `
      canvas {
        display: block;
        max-width: 100%;
        height: auto;
        cursor: crosshair;
        background: #111;
        border-radius: 6px;
        box-shadow: 0 4px 24px rgba(0, 0, 0, 0.5);
      }
    `,
  ],
})
export class VncCanvasComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;
  private ctx!: CanvasRenderingContext2D;
  private offscreen!: HTMLCanvasElement;
  private offCtx!: CanvasRenderingContext2D;
  private readonly TILE_SIZE = 32;

  constructor(private vncService: VncService) {}

  ngOnInit(): void {
    this.vncService.onFrame((msg) => this.renderFrame(msg));
  }

  ngAfterViewInit(): void {
    const ctx = this.canvasRef.nativeElement.getContext('2d');
    if (!ctx) throw new Error('Canvas 2D context unavailable');
    this.ctx = ctx;
    this.ctx.fillStyle = '#111';
    this.ctx.fillRect(0, 0, 1280, 720);

    this.offscreen = document.createElement('canvas');
    this.offscreen.width = 1280;
    this.offscreen.height = 720;
    this.offCtx = this.offscreen.getContext('2d')!;
  }

  ngOnDestroy(): void {
    this.vncService.onFrame(() => {});
  }

  private renderFrame(msg: FrameMessage): void {
    if (!this.ctx) return;

    if (msg.full) {
      this.renderFullFrame(msg);
    } else {
      this.renderDiffFrame(msg);
    }
  }

  private renderDiffFrame(msg: FrameMessage): void {
    for (const tile of msg.tiles) {
      const dx = tile.x * this.TILE_SIZE;
      const dy = tile.y * this.TILE_SIZE;
      this.decodeTile(tile.data).then(bitmap => {
        this.ctx.drawImage(bitmap, dx, dy);
        bitmap.close();
      });
    }
  }

  private renderFullFrame(msg: FrameMessage): void {
    this.offCtx.drawImage(this.canvasRef.nativeElement, 0, 0);

    const draws = msg.tiles.map(tile => {
      const dx = tile.x * this.TILE_SIZE;
      const dy = tile.y * this.TILE_SIZE;
      return this.decodeTile(tile.data).then(bitmap => {
        this.offCtx.drawImage(bitmap, dx, dy);
        bitmap.close();
      });
    });

    Promise.all(draws).then(() => {
      this.ctx.drawImage(this.offscreen, 0, 0);
    });
  }

  private decodeTile(base64: string): Promise<ImageBitmap> {
    const binStr = atob(base64);
    const bytes = new Uint8Array(binStr.length);
    for (let i = 0; i < binStr.length; i++) {
      bytes[i] = binStr.charCodeAt(i);
    }
    return createImageBitmap(new Blob([bytes], { type: 'image/jpeg' }));
  }

  onClick(event: MouseEvent): void {
    if (!this.vncService.isController()) return;
    const canvas = this.canvasRef.nativeElement;
    const rect = canvas.getBoundingClientRect();
    const scaleX = 1280 / rect.width;
    const scaleY = 720 / rect.height;
    const x = (event.clientX - rect.left) * scaleX;
    const y = (event.clientY - rect.top) * scaleY;
    this.vncService.sendClick(x, y);
  }

  onKeyDown(event: KeyboardEvent): void {
    if (!this.vncService.isController()) return;
    if (event.key.length === 1) {
      event.preventDefault();
      this.vncService.sendKey(event.key);
    }
  }
}
