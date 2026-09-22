import { Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MiniPathwayRun } from './mini-pathway.types';

/**
 * Port of the old React app's components/PathwayHistorySelector.tsx — a dropdown listing saved
 * mini-pathway runs for a conversation. Outside-click closes it; disabled while a generation is
 * in progress or when there is only one saved run (nothing to switch to).
 */
@Component({
  selector: 'app-pathway-history-selector',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (runs.length) {
      <div class="pathway-history">
        <span class="pathway-history-label">Saved pathways <b>{{ runs.length }}</b></span>
        <button
          type="button"
          class="pathway-history-trigger"
          [disabled]="loading || runs.length === 1"
          aria-haspopup="listbox"
          [attr.aria-expanded]="open()"
          (click)="toggle()"
        >
          <span class="pathway-history-trigger-text">
            <strong>{{ triggerLabel() }}</strong>
          </span>
          @if (runs.length > 1) {
            <span class="pathway-history-chevron" aria-hidden="true">&#9662;</span>
          }
        </button>
        @if (open()) {
          <ul class="pathway-history-list" role="listbox">
            @for (run of orderedRuns(); track run.id) {
              <li role="option" [attr.aria-selected]="run.id === selectedId">
                <button type="button" [class.is-selected]="run.id === selectedId" (click)="choose(run.id)">
                  <span class="option-title">{{ run.goal || 'Mini pathway' }}</span>
                  <span class="option-date">{{ formatDate(run.createdAt) }}</span>
                </button>
              </li>
            }
          </ul>
        }
      </div>
    }
  `,
  styles: [`
    .pathway-history { position: relative; margin-bottom: 12px; font-size: 12px; }
    .pathway-history-label { display: flex; align-items: center; gap: 6px; color: #8a94a6; font-size: 11px; font-weight: 600; margin-bottom: 4px; }
    .pathway-history-label b { color: #27364a; }
    .pathway-history-trigger {
      width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 8px;
      border: 1px solid #e4e9f2; background: #f8f9fc; border-radius: 8px; padding: 8px 10px; text-align: left;
    }
    .pathway-history-trigger:disabled { opacity: 0.7; }
    .pathway-history-trigger-text strong { display: block; color: #27364a; font-size: 12px; }
    .pathway-history-chevron { color: #8a94a6; }
    .pathway-history-list {
      position: absolute; z-index: 5; top: 100%; left: 0; right: 0; margin-top: 4px; padding: 4px;
      list-style: none; background: #fff; border: 1px solid #e4e9f2; border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0,0,0,0.08); max-height: 220px; overflow-y: auto;
    }
    .pathway-history-list button {
      width: 100%; display: flex; flex-direction: column; gap: 2px; border: none; background: transparent;
      padding: 6px 8px; border-radius: 6px; text-align: left;
    }
    .pathway-history-list button:hover, .pathway-history-list button.is-selected { background: var(--accent-8, #f2edff); }
    .option-title { font-size: 12px; color: #27364a; font-weight: 600; }
    .option-date { font-size: 10px; color: #8a94a6; }
  `]
})
export class PathwayHistorySelectorComponent implements OnChanges {
  @Input() runs: MiniPathwayRun[] = [];
  @Input() selectedId: string | null = null;
  @Input() loading = false;

  @Output() select = new EventEmitter<string>();

  open = signal(false);

  constructor(private host: ElementRef<HTMLElement>) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['loading']?.currentValue) this.open.set(false);
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) this.open.set(false);
  }

  toggle(): void {
    if (this.runs.length > 1) this.open.update(v => !v);
  }

  orderedRuns(): MiniPathwayRun[] {
    return [...this.runs].reverse();
  }

  triggerLabel(): string {
    const selected = this.runs.find(r => r.id === this.selectedId);
    if (!selected) return this.loading ? 'Building a new pathway...' : 'Select a pathway';
    return `${selected.goal || 'Mini pathway'} - ${this.formatDate(selected.createdAt)}`;
  }

  formatDate(createdAt: number): string {
    const date = new Date(createdAt);
    return Number.isNaN(date.getTime())
      ? 'Date unavailable'
      : date.toLocaleString('en-AU', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  choose(id: string): void {
    this.select.emit(id);
    this.open.set(false);
  }
}
