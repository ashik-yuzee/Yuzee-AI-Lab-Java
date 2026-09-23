import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { MessageTelemetry, SessionStats, TokenUsageMetrics } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

/**
 * 1:1 port of TokenInspector.tsx (right-hand slide-in panel). Reads TokenLabService.activeTurnTelemetry
 * like the original.
 */
@Component({
  selector: 'app-token-inspector',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './token-inspector.component.html',
  styleUrl: './token-inspector.component.scss'
})
export class TokenInspectorComponent {
  @Input() open = false;

  @Output() closed = new EventEmitter<void>();

  constructor(private lab: TokenLabService) {}

  close(): void {
    this.lab.isTokenInspectorOpen.set(false);
    this.closed.emit();
  }

  showContextInspector(): void {
    this.lab.isContextInspectorOpen.set(true);
  }

  showMemoryTimeline(): void {
    this.lab.isMemoryTimelineOpen.set(true);
  }

  get tele(): MessageTelemetry | null {
    return this.lab.activeTurnTelemetry();
  }

  get stats(): SessionStats | null {
    return this.lab.sessionStats();
  }

  get usage(): TokenUsageMetrics | undefined {
    return this.tele?.usage;
  }

  get hasCompaction(): boolean {
    return !!this.tele?.compactionMetrics;
  }

  get newInput(): number {
    const s = this.stats;
    return s?.totalUncachedInputTokens ?? Math.max(0, (s?.totalModelInputTokens ?? 0) - (s?.totalCachedTokens ?? 0));
  }

  get cachedPerTurn(): number {
    const s = this.stats;
    return Math.round((s?.totalCachedTokens || 0) / (s?.userFacingChatCalls || 1));
  }
}
