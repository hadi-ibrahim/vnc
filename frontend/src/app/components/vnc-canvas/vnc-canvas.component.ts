import {
  Component,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { VncService, H264Frame } from '../../services/vnc.service';

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
  private decoder: VideoDecoder | null = null;
  private codecDescription: Uint8Array | null = null;
  private pendingFrames: H264Frame[] = [];

  constructor(private vncService: VncService) {}

  ngOnInit(): void {
    this.vncService.onConfig((config) => this.onCodecConfig(config));
    this.vncService.onFrame((frame) => this.onH264Frame(frame));
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
    this.vncService.onConfig(() => {});
    if (this.decoder && this.decoder.state !== 'closed') {
      this.decoder.close();
    }
  }

  private onCodecConfig(config: Uint8Array): void {
    this.codecDescription = config;
    this.initDecoder();
  }

  private initDecoder(): void {
    if (this.decoder && this.decoder.state !== 'closed') {
      this.decoder.close();
    }

    this.decoder = new VideoDecoder({
      output: (frame: VideoFrame) => {
        this.ctx.drawImage(frame, 0, 0);
        frame.close();
      },
      error: (e: DOMException) => {
        console.error('VideoDecoder error:', e);
      },
    });

    this.decoder.configure({
      codec: 'avc1.42001e',
      codedWidth: 1280,
      codedHeight: 720,
      description: this.codecDescription!,
    });

    for (const frame of this.pendingFrames) {
      this.decodeFrame(frame);
    }
    this.pendingFrames = [];
  }

  private onH264Frame(frame: H264Frame): void {
    if (!this.decoder || this.decoder.state !== 'configured') {
      if (frame.keyframe) {
        this.pendingFrames.push(frame);
      }
      return;
    }

    this.decodeFrame(frame);
  }

  private decodeFrame(frame: H264Frame): void {
    if (!this.decoder || this.decoder.state !== 'configured') return;

    const chunk = new EncodedVideoChunk({
      type: frame.keyframe ? 'key' : 'delta',
      timestamp: frame.timestamp * 1000,
      data: frame.data,
    });

    try {
      this.decoder.decode(chunk);
    } catch (e) {
      console.error('Decode failed:', e);
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
