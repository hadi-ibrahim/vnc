import { Component, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

interface AppInfo {
  id: string;
  name: string;
}

@Component({
  selector: 'app-screen-manager',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="manager">
      <header>
        <h1>Screen Manager</h1>
        <span class="subtitle">Select a screen to view</span>
      </header>
      <main>
        @if (loading()) {
          <p class="loading">Loading screens...</p>
        } @else {
          <div class="grid">
            @for (app of apps(); track app.id) {
              <a class="card" [routerLink]="['/app', app.id]">
                <div class="card-icon">{{ app.id }}</div>
                <div class="card-body">
                  <h2>{{ app.name }}</h2>
                  <span class="card-id">App #{{ app.id }}</span>
                </div>
                <span class="arrow">&#8594;</span>
              </a>
            }
          </div>
        }
      </main>
    </div>
  `,
  styles: [
    `
      .manager {
        min-height: 100vh;
        background: #0a0a0f;
        color: white;
      }

      header {
        padding: 32px 32px 0;
        text-align: center;
      }

      h1 {
        font-size: 28px;
        font-weight: 700;
        margin: 0;
        color: #e0e0ff;
      }

      .subtitle {
        display: block;
        margin-top: 8px;
        font-size: 14px;
        color: #888;
      }

      main {
        padding: 40px 32px;
        display: flex;
        justify-content: center;
      }

      .loading {
        color: #888;
        font-size: 15px;
      }

      .grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
        gap: 20px;
        max-width: 1000px;
        width: 100%;
      }

      .card {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 20px 24px;
        background: #15151f;
        border: 1px solid #2a2a3a;
        border-radius: 12px;
        text-decoration: none;
        color: white;
        cursor: pointer;
        transition: all 0.2s ease;
      }

      .card:hover {
        background: #1c1c2e;
        border-color: #3355aa;
        transform: translateY(-2px);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
      }

      .card-icon {
        width: 48px;
        height: 48px;
        border-radius: 10px;
        background: linear-gradient(135deg, #3355aa, #5577cc);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 20px;
        font-weight: 700;
        flex-shrink: 0;
      }

      .card-body {
        flex: 1;
        min-width: 0;
      }

      h2 {
        font-size: 16px;
        font-weight: 600;
        margin: 0;
        color: #e0e0ff;
      }

      .card-id {
        font-size: 13px;
        color: #666;
        margin-top: 2px;
        display: block;
      }

      .arrow {
        font-size: 20px;
        color: #555;
        transition: color 0.2s;
      }

      .card:hover .arrow {
        color: #3355aa;
      }
    `,
  ],
})
export class ScreenManagerComponent implements OnInit {
  readonly apps = signal<AppInfo[]>([]);
  readonly loading = signal(true);

  ngOnInit(): void {
    fetch('/api/apps')
      .then((res) => res.json())
      .then((data: AppInfo[]) => {
        this.apps.set(data);
        this.loading.set(false);
      })
      .catch(() => this.loading.set(false));
  }
}
