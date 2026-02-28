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
  }

  ngOnDestroy(): void {
    this.vncService.onFrame(() => {});
  }

  private renderFrame(msg: FrameMessage): void {
    if (!this.ctx) return;

    for (const tile of msg.tiles) {
      const img = new Image();
      const dx = tile.x * this.TILE_SIZE;
      const dy = tile.y * this.TILE_SIZE;
      img.onload = () => this.ctx.drawImage(img, dx, dy);
      img.src = 'data:image/jpeg;base64,' + tile.data;
    }
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
