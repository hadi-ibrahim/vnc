import { Component, OnInit, OnDestroy } from '@angular/core';
import { VncCanvasComponent } from './components/vnc-canvas/vnc-canvas.component';
import { VncService } from './services/vnc.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [VncCanvasComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent implements OnInit, OnDestroy {
  constructor(public vnc: VncService) {}

  ngOnInit(): void {
    this.vnc.connect();
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
