import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/** Port of ChatTurnDivider.tsx — pure-presentational date/time divider between message groups. */
@Component({
  selector: 'app-chat-turn-divider',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="chat-turn-divider" data-chat-turn-divider>
      @if (valid) {
        <time [attr.datetime]="isoString" [attr.title]="titleString">{{ day }} {{ time }}</time>
      } @else {
        <span>New exchange</span>
      }
    </div>
  `,
  styles: [`
    .chat-turn-divider { margin-bottom: 24px; text-align: center; font-size: 13px; line-height: 24px; color: #718096; }
  `]
})
export class ChatTurnDividerComponent {
  @Input() createdAt?: number;

  private get date(): Date | null {
    return typeof this.createdAt === 'number' && Number.isFinite(this.createdAt) && this.createdAt > 0
      ? new Date(this.createdAt)
      : null;
  }

  get valid(): boolean {
    const d = this.date;
    return !!d && !Number.isNaN(d.getTime());
  }

  get day(): string {
    const d = this.date;
    if (!d || !this.valid) return '';
    const now = new Date();
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    if (d.toDateString() === now.toDateString()) return 'Today';
    if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
    return d.toLocaleDateString('en-AU', {
      day: 'numeric',
      month: 'short',
      ...(d.getFullYear() !== now.getFullYear() ? { year: 'numeric' as const } : {})
    });
  }

  get time(): string {
    const d = this.date;
    if (!d || !this.valid) return '';
    return d.toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit', hour12: true }).toUpperCase();
  }

  get isoString(): string {
    return this.date?.toISOString() ?? '';
  }

  get titleString(): string {
    return this.date?.toLocaleString() ?? '';
  }
}
