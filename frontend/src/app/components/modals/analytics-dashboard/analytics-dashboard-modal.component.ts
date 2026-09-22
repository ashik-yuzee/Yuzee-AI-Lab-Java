import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { SessionStats } from '../../../models/types';

interface DailyCost {
  estimatedCostUsd: number;
  date: string;
}

interface UtilityStats {
  whiteboardCalls: number;
  utilityModelCalls: number;
}

/**
 * Read-only dashboard over session stats. Port of the old React app's
 * AnalyticsDashboardModal.tsx, trimmed to what TokenService.getSessionStats() (and the sibling
 * daily-cost / utility-stats endpoints) actually track: prompt/output/total tokens, turn count,
 * and estimated cost. The old app additionally showed cached-token totals, compaction overhead,
 * and a "tokens saved vs. baseline" figure — none of that is tracked server-side here (there's no
 * cumulative cache/compaction/baseline counter), so those cards are simply left out.
 */
@Component({
  selector: 'app-analytics-dashboard-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './analytics-dashboard-modal.component.html',
  styleUrl: './analytics-dashboard-modal.component.scss'
})
export class AnalyticsDashboardModalComponent implements OnChanges {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  sessionStats = signal<SessionStats | null>(null);
  dailyCost = signal<DailyCost | null>(null);
  utilityStats = signal<UtilityStats | null>(null);
  loading = signal(false);

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.load();
    }
  }

  async load(): Promise<void> {
    this.loading.set(true);
    const [stats, daily, util] = await Promise.allSettled([
      firstValueFrom(this.api.get<SessionStats>('/tokens/session-stats')),
      firstValueFrom(this.api.get<DailyCost>('/tokens/daily-cost')),
      firstValueFrom(this.api.get<UtilityStats>('/tokens/utility-stats'))
    ]);
    this.sessionStats.set(stats.status === 'fulfilled' ? stats.value : null);
    this.dailyCost.set(daily.status === 'fulfilled' ? daily.value : null);
    this.utilityStats.set(util.status === 'fulfilled' ? util.value : null);
    this.loading.set(false);
  }

  pct(part: number, total: number): number {
    return total > 0 ? Math.round((part / total) * 100) : 0;
  }

  avgTokensPerTurn(): number {
    const stats = this.sessionStats();
    if (!stats || stats.turns <= 0) return 0;
    return Math.round(stats.totalTokens / stats.turns);
  }

  async resetSession(): Promise<void> {
    try {
      await firstValueFrom(this.api.post('/tokens/session-reset'));
      await this.load();
    } catch {
      // ponytail: best-effort refresh — a failed reset just leaves the previous totals showing.
    }
  }

  close(): void {
    this.closed.emit();
  }
}
