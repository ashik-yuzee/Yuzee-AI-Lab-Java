import { Component, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-location-prompt-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="modal-backdrop" (click)="dismiss.emit()">
      <div class="modal-card card p-4 shadow-lg" (click)="$event.stopPropagation()">
        <div class="d-flex align-items-center gap-2 mb-3">
          <span style="font-size:24px">📍</span>
          <h5 class="mb-0 fw-semibold">Where are you based?</h5>
        </div>
        <p class="text-muted small mb-3">
          Sharing your location helps Yuzee give you relevant course and career advice for your area.
        </p>
        <div class="mb-3">
          <label class="form-label">City or state</label>
          <input class="form-control" [(ngModel)]="location" placeholder="e.g. Melbourne, Victoria"
                 (keyup.enter)="submit()">
        </div>
        <div class="d-flex gap-2">
          <button class="btn btn-purple flex-1" [disabled]="!location.trim()" (click)="submit()">
            Confirm location
          </button>
          <button class="btn btn-outline-secondary" (click)="dismiss.emit()">Skip</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); z-index: 1050;
      display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal-card { border-radius: 16px; width: 100%; max-width: 400px; border: 1px solid #e4e9f2; }
    .btn-purple { background: #7957c6; color: #fff; border: none; }
    .btn-purple:hover { background: #6744b5; color: #fff; }
    .btn-purple:disabled { background: #b5a3e0; }
  `]
})
export class LocationPromptModalComponent {
  @Output() locationSet = new EventEmitter<string>();
  @Output() dismiss = new EventEmitter<void>();
  location = '';

  submit(): void {
    if (this.location.trim()) this.locationSet.emit(this.location.trim());
  }
}
