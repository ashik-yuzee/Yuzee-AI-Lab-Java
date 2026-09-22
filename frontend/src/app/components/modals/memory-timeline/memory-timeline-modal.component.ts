import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { CompactionInfo } from '../../../models/token-lab-inspector.types';

/**
 * Read-only compaction-history view + a reset-memory action. Port of the old React app's
 * MemoryTimelineModal.tsx.
 *
 * Simplification: the old app rendered a list of past compaction events
 * (`currentConversation.compactionHistory`). This backend's Conversation model
 * (backend/src/main/java/com/yuzee/tokenlab/model/Conversation.java) stores only a single
 * `summaryText` string — there is no persisted event log to list. This component instead shows
 * the conversation's current summary state plus the most recent turn's compaction result (passed
 * in as `compaction`), and says plainly that a multi-event history isn't tracked server-side.
 */
@Component({
  selector: 'app-memory-timeline-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './memory-timeline-modal.component.html',
  styleUrl: './memory-timeline-modal.component.scss'
})
export class MemoryTimelineModalComponent implements OnChanges {
  @Input() open = false;
  @Input() conversationId: string | null = null;
  @Input() compaction: CompactionInfo | null = null;

  @Output() closed = new EventEmitter<void>();
  @Output() memoryReset = new EventEmitter<void>();

  summaryText = signal<string | null>(null);
  summaryLoading = signal(false);

  resetting = signal(false);
  resetError = signal<string | null>(null);
  resetDone = signal(false);

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.resetDone.set(false);
      this.resetError.set(null);
      this.loadConversation();
    }
  }

  async loadConversation(): Promise<void> {
    if (!this.conversationId) {
      this.summaryText.set(null);
      return;
    }
    this.summaryLoading.set(true);
    try {
      const conv = await firstValueFrom(
        this.api.get<{ summaryText?: string }>(`/conversations/${this.conversationId}`)
      );
      this.summaryText.set(conv?.summaryText ?? null);
    } catch {
      this.summaryText.set(null);
    } finally {
      this.summaryLoading.set(false);
    }
  }

  async resetMemory(): Promise<void> {
    if (!this.conversationId || this.resetting()) return;
    this.resetting.set(true);
    this.resetError.set(null);
    try {
      await firstValueFrom(this.api.post(`/conversations/${this.conversationId}/reset-memory`));
      this.summaryText.set(null);
      this.resetDone.set(true);
      this.memoryReset.emit();
    } catch {
      this.resetError.set('Failed to reset conversation memory. Please try again.');
    } finally {
      this.resetting.set(false);
    }
  }

  close(): void {
    this.closed.emit();
  }
}
