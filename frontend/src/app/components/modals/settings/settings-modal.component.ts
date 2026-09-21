import { Component, Output, EventEmitter, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TokenLabService } from '../../../services/token-lab.service';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService } from '../../../services/auth.service';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-settings-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="modal-backdrop" (click)="close.emit()">
      <div class="modal-card card p-4 shadow-lg" (click)="$event.stopPropagation()">
        <div class="d-flex align-items-center justify-content-between mb-4">
          <h5 class="mb-0 fw-semibold">Settings</h5>
          <button class="btn btn-sm btn-outline-secondary" (click)="close.emit()">✕</button>
        </div>

        <!-- Model -->
        <div class="mb-3">
          <label class="form-label">Model</label>
          <select class="form-select" [(ngModel)]="selectedModel">
            @for (m of models(); track m.id) {
              <option [value]="m.id">{{ m.label }}</option>
            }
          </select>
        </div>

        <!-- Optimization mode -->
        <div class="mb-3">
          <label class="form-label">Optimization Mode</label>
          <select class="form-select" [(ngModel)]="selectedMode">
            <option value="AUTO">Auto</option>
            <option value="SAVE_TOKENS">Save Tokens</option>
            <option value="FULL_CONTEXT">Full Context</option>
            <option value="VANILLA">Vanilla</option>
          </select>
        </div>

        <!-- Response mode -->
        <div class="mb-3">
          <label class="form-label">Response Mode</label>
          <select class="form-select" [(ngModel)]="selectedResponseMode">
            <option value="quick">Quick</option>
            <option value="standard">Standard</option>
            <option value="explain">Explain</option>
            <option value="explore">Explore</option>
            <option value="detail">Detail</option>
          </select>
        </div>

        <div class="d-flex justify-content-end gap-2 mt-4">
          <button class="btn btn-outline-secondary" (click)="close.emit()">Cancel</button>
          <button class="btn btn-purple" (click)="apply()">Apply</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 1050;
      display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal-card { border-radius: 16px; width: 100%; max-width: 440px; border: 1px solid #e4e9f2; }
    .btn-purple { background: #7957c6; color: #fff; border: none; }
    .btn-purple:hover { background: #6744b5; color: #fff; }
  `]
})
export class SettingsModalComponent implements OnInit {
  @Output() close = new EventEmitter<void>();

  models = signal<{ id: string; label: string }[]>([]);
  selectedModel = '';
  selectedMode = 'AUTO';
  selectedResponseMode = 'standard';

  constructor(public lab: TokenLabService, private http: HttpClient, private auth: AuthService) {}

  ngOnInit(): void {
    this.selectedModel = this.lab.activeModelId();
    this.selectedMode = this.lab.optimizationMode();
    this.selectedResponseMode = this.lab.responseMode();
    this.loadModels();
  }

  async loadModels(): Promise<void> {
    try {
      const caps = await firstValueFrom(
        this.http.get<any>('/api/config/capabilities', {
          headers: new HttpHeaders({ Authorization: `Bearer ${this.auth.token}` })
        })
      );
      this.models.set(caps.models ?? []);
    } catch {
      this.models.set([{ id: 'gemini-2.0-flash', label: 'Gemini 2.0 Flash' }]);
    }
  }

  apply(): void {
    this.lab.activeModelId.set(this.selectedModel);
    this.lab.optimizationMode.set(this.selectedMode);
    this.lab.responseMode.set(this.selectedResponseMode);
    this.close.emit();
  }
}
