import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { VncCanvasComponent } from '../vnc-canvas/vnc-canvas.component';
import { VncService } from '../../services/vnc.service';

@Component({
  selector: 'app-viewer',
  standalone: true,
  imports: [VncCanvasComponent, RouterLink],
  template: `
    <div class="viewer">
      <header>
        <div class="left">
          <a class="back-btn" routerLink="/">&#8592; Back</a>
          <h1>App #{{ appId }}</h1>
        </div>
        <div class="controls">
          <span class="status" [class.connected]="vnc.connected()">
            {{ vnc.connected() ? 'Connected' : 'Disconnected' }}
          </span>
          <button
            class="lock-btn"
            [class.active]="vnc.isController()"
            [disabled]="!vnc.connected() || (vnc.isLocked() && !vnc.isController())"
            (click)="toggleLock()"
          >
            {{ vnc.isController() ? 'Release Control' : 'Take Control' }}
          </button>
        </div>
      </header>
      <main>
        <app-vnc-canvas />
      </main>
    </div>
  `,
  styles: [
    `
      .viewer {
        min-height: 100vh;
        background: #0a0a0f;
        color: white;
      }

      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 12px 24px;
        background: #15151f;
        border-bottom: 1px solid #2a2a3a;
      }

      .left {
        display: flex;
        align-items: center;
        gap: 16px;
      }

      .back-btn {
        font-size: 13px;
        font-weight: 600;
        padding: 6px 14px;
        border-radius: 6px;
        background: #2a2a3a;
        color: #ccc;
        text-decoration: none;
        transition: all 0.2s ease;
      }

      .back-btn:hover {
        background: #3a3a4a;
        color: white;
      }

      h1 {
        font-size: 18px;
        font-weight: 600;
        margin: 0;
        color: #e0e0ff;
        letter-spacing: -0.3px;
      }

      .controls {
        display: flex;
        align-items: center;
        gap: 16px;
      }

      .status {
        font-size: 13px;
        font-weight: 500;
        padding: 4px 14px;
        border-radius: 20px;
        background: rgba(255, 60, 60, 0.12);
        color: #ff6666;
        transition: all 0.3s ease;
      }

      .status.connected {
        background: rgba(60, 255, 60, 0.12);
        color: #66ff88;
      }

      .lock-btn {
        padding: 8px 20px;
        border: none;
        border-radius: 6px;
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
        background: #3355aa;
        color: white;
        transition: all 0.2s ease;
      }

      .lock-btn:hover:not(:disabled) {
        background: #4466cc;
        transform: translateY(-1px);
      }

      .lock-btn.active {
        background: #aa3355;
      }

      .lock-btn.active:hover {
        background: #cc4466;
      }

      .lock-btn:disabled {
        opacity: 0.4;
        cursor: not-allowed;
        transform: none;
      }

      main {
        display: flex;
        justify-content: center;
        padding: 24px;
      }
    `,
  ],
})
export class ViewerComponent implements OnInit, OnDestroy {
  appId = '';

  constructor(
    public vnc: VncService,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.appId = this.route.snapshot.paramMap.get('id') ?? '';
    this.vnc.connect(this.appId);
  }

  ngOnDestroy(): void {
    this.vnc.disconnect();
  }

  toggleLock(): void {
    if (this.vnc.isController()) {
      this.vnc.releaseLock();
    } else {
      this.vnc.requestLock();
    }
  }
}
