import { Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges, ViewChild, signal } from '@angular/core';
import { ChatMessage } from '../../models/types';
import { IconComponent } from '../shared/icon/icon.component';
import { MiniPathwayRun, pathwayContext, pathwayDate } from './history';

/** Port of PathwayHistorySelector.tsx. Styled by the global mini-pathway.css. */
@Component({
  selector: 'app-pathway-history-selector',
  standalone: true,
  imports: [IconComponent],
  template: `
    @if (runs.length) {
      <div class="mini-pathway-history" #root>
        <span class="mini-pathway-history-label"><app-icon [size]="11" name="Clock" />Saved pathways <b>{{ runs.length }}</b></span>
        <button
          type="button"
          class="mini-pathway-history-trigger"
          [class.mini-pathway-history-trigger--open]="open()"
          [disabled]="loading || runs.length === 1"
          aria-haspopup="listbox"
          [attr.aria-expanded]="open()"
          (click)="runs.length > 1 && open.set(!open())"
        >
          <span class="mini-pathway-history-trigger-text">
            <strong>{{ label() }}</strong>
            @if (sublabel()) { <small>{{ sublabel() }}</small> }
          </span>
          @if (runs.length > 1) { <app-icon [size]="15" class="mini-pathway-history-chevron" name="ChevronDown" /> }
        </button>
        @if (open()) {
          <ul class="mini-pathway-history-list" role="listbox">
            @for (run of ordered(); track run.id) {
              <li role="option" [attr.aria-selected]="run.id === selectedId">
                <button type="button" [class.mini-pathway-history-option--selected]="run.id === selectedId" (click)="onSelect.emit(run.id); open.set(false)">
                  <span class="mini-pathway-history-option-title">Pathway {{ runs.indexOf(run) + 1 }} · {{ date(run.createdAt) }}</span>
                  <span class="mini-pathway-history-option-ctx">{{ context(run) }}</span>
                </button>
              </li>
            }
          </ul>
        }
      </div>
    }
  `,
  styles: [':host{display:contents}'],
})
export class PathwayHistorySelectorComponent implements OnChanges {
  @Input() runs: MiniPathwayRun[] = [];
  @Input() selectedId?: string;
  @Input() messages: ChatMessage[] = [];
  @Input() loading = false;
  // eslint-disable-next-line @angular-eslint/no-output-on-prefix -- mirrors the original onSelect prop
  @Output() onSelect = new EventEmitter<string>();

  @ViewChild('root') root?: ElementRef<HTMLElement>;
  open = signal(false);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['loading']) this.open.set(false);
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(e: MouseEvent): void {
    if (this.open() && this.root && !this.root.nativeElement.contains(e.target as Node)) this.open.set(false);
  }

  private selected(): MiniPathwayRun | undefined { return this.runs.find(r => r.id === this.selectedId); }
  ordered(): MiniPathwayRun[] { return [...this.runs].reverse(); }
  date(createdAt: string): string { return pathwayDate(createdAt); }
  context(run: MiniPathwayRun): string { return pathwayContext(run, this.messages); }

  label(): string {
    const s = this.selected();
    return this.loading ? 'Building a new pathway…' : s ? `Pathway ${this.runs.indexOf(s) + 1} · ${pathwayDate(s.createdAt)}` : 'Select a pathway';
  }
  sublabel(): string {
    const s = this.selected();
    return this.loading ? 'Your saved pathways will remain available here.' : s ? pathwayContext(s, this.messages) : '';
  }
}
