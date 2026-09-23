import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { SessionStats } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

/** 1:1 port of AnalyticsDashboardModal.tsx — reads TokenLabService.sessionStats. */
@Component({
  selector: 'app-analytics-dashboard-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './analytics-dashboard-modal.component.html',
  styleUrl: './analytics-dashboard-modal.component.scss'
})
export class AnalyticsDashboardModalComponent {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  constructor(private lab: TokenLabService) {}

  get stats(): SessionStats | null {
    return this.lab.sessionStats();
  }

  get s() {
    const st = this.stats;
    return {
      totalCalls: st?.userFacingChatCalls || 0,
      trueTotal: st?.trueTotalConsumption || 0,
      promptTokens: st?.totalModelInputTokens || 0,
      outputTokens: st?.totalModelOutputTokens || 0,
      thinkingTokens: st?.totalThinkingTokens || 0,
      cachedTokens: st?.totalCachedTokens || 0,
      compactionCost: st?.compactionTotalTokens || 0,
      tokensSaved: st?.tokensSaved || 0,
    };
  }

  pct(part: number, total: number): number {
    return total > 0 ? (part / total) * 100 : 0;
  }

  close(): void {
    this.lab.isAnalyticsOpen.set(false);
    this.closed.emit();
  }
}
