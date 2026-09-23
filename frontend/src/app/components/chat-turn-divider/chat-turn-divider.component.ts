import { Component, Input, computed, signal } from '@angular/core';

/** Port of ChatTurnDivider.tsx. */
@Component({
  selector: 'app-chat-turn-divider',
  standalone: true,
  templateUrl: './chat-turn-divider.component.html',
  styleUrl: './chat-turn-divider.component.scss'
})
export class ChatTurnDividerComponent {
  private readonly createdAtValue = signal<number | undefined>(undefined);
  @Input() set createdAt(value: number | undefined) { this.createdAtValue.set(value); }

  readonly date = computed(() => {
    const createdAt = this.createdAtValue();
    const date = typeof createdAt === 'number' && Number.isFinite(createdAt) && createdAt > 0 ? new Date(createdAt) : null;
    return date && !Number.isNaN(date.getTime()) ? date : null;
  });

  readonly day = computed(() => {
    const date = this.date();
    if (!date) return '';
    const now = new Date(), yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    return date.toDateString() === now.toDateString() ? 'Today'
      : date.toDateString() === yesterday.toDateString() ? 'Yesterday'
      : date.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', ...(date.getFullYear() !== now.getFullYear() ? { year: 'numeric' as const } : {}) });
  });

  readonly time = computed(() => this.date()?.toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit', hour12: true }).toUpperCase() ?? '');
}
