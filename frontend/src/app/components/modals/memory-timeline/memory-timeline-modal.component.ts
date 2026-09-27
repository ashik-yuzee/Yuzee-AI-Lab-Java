import { Component, EventEmitter, Input, Output, ChangeDetectionStrategy } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { CompactionMetrics, Conversation } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

/** 1:1 port of MemoryTimelineModal.tsx — reads the current conversation from TokenLabService. */
@Component({
    selector: 'app-memory-timeline-modal',
    imports: [IconComponent],
    templateUrl: './memory-timeline-modal.component.html',
    changeDetection: ChangeDetectionStrategy.Eager,
    styleUrl: './memory-timeline-modal.component.scss'
})
export class MemoryTimelineModalComponent {
  @Input() open = false;

  @Output() closed = new EventEmitter<void>();

  constructor(public lab: TokenLabService) {}

  private get conv(): Conversation | null {
    return this.lab.currentConversation();
  }

  get history(): CompactionMetrics[] {
    const h = this.conv?.compactionHistory;
    return Array.isArray(h) ? h : [];
  }

  get summary(): string | undefined {
    return this.conv?.summary;
  }

  get summaryVersion(): number {
    return this.conv?.summaryVersion || 0;
  }

  timeOf(ts: number | undefined): string {
    return new Date(ts as number).toLocaleTimeString();
  }

  close(): void {
    this.lab.isMemoryTimelineOpen.set(false);
    this.closed.emit();
  }
}
