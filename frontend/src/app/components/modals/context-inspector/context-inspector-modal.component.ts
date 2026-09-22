import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CompactionInfo } from '../../../models/token-lab-inspector.types';

type ContextTab = 'included' | 'excluded';

/**
 * Read-only tabbed viewer for the last turn's assembled context. Port of the old React app's
 * ContextInspectorModal.tsx.
 *
 * The old app showed per-section included/excluded prompt fragments with previews and token
 * counts (from a live "excluded content" telemetry feed). That feed doesn't exist in this
 * backend — RequestAssemblerService/ConversationMemoryService only return aggregate counts
 * (turnsKept/turnsDropped/tokensUsed/tokenBudget) via CompactionMetrics, not which turns or
 * prompt sections were dropped or their content. This component shows exactly that aggregate
 * data and labels the gap explicitly instead of fabricating a fake excluded-content list.
 */
@Component({
  selector: 'app-context-inspector-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './context-inspector-modal.component.html',
  styleUrl: './context-inspector-modal.component.scss'
})
export class ContextInspectorModalComponent {
  @Input() open = false;
  @Input() compaction: CompactionInfo | null = null;

  @Output() closed = new EventEmitter<void>();

  activeTab = signal<ContextTab>('included');

  setTab(tab: ContextTab): void {
    this.activeTab.set(tab);
  }

  close(): void {
    this.closed.emit();
  }
}
