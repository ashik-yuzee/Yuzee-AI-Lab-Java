import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { SessionStats } from '../../../models/types';
import { CompactionInfo, TurnTokenUsage } from '../../../models/token-lab-inspector.types';

/**
 * Slide-in right panel — turn-level token/usage breakdown for the current message, plus
 * cumulative session totals. Port of the old React app's TokenInspector.tsx.
 *
 * Simplifications vs. the old app (no backend data for these today):
 *  - No per-stage latency lifecycle timeline (pre-provider/TTFT/generation/validation ms) —
 *    ChatController does not currently measure or emit per-stage timings, only a total.
 *  - Context composition is a static legend, not per-section token counts — the backend's
 *    AssembledRequest/RequestAssemblerService doesn't expose a token count per prompt section.
 *  - Session totals only cover what TokenService.getSessionStats() tracks (prompt/output/total
 *    tokens, turns, cost) — there's no cumulative cached-token count, compaction overhead, or
 *    "tokens saved vs. baseline" figure available server-side, so those cards are omitted
 *    rather than faked.
 */
@Component({
  selector: 'app-token-inspector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './token-inspector.component.html',
  styleUrl: './token-inspector.component.scss'
})
export class TokenInspectorComponent implements OnChanges {
  @Input() open = false;
  @Input() tokenUsage: TurnTokenUsage | null = null;
  @Input() compaction: CompactionInfo | null = null;

  @Output() closed = new EventEmitter<void>();
  @Output() openContextInspector = new EventEmitter<void>();
  @Output() openMemoryTimeline = new EventEmitter<void>();

  sessionStats = signal<SessionStats | null>(null);
  statsLoading = signal(false);
  statsError = signal(false);

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.loadSessionStats();
    }
  }

  async loadSessionStats(): Promise<void> {
    this.statsLoading.set(true);
    this.statsError.set(false);
    try {
      const stats = await firstValueFrom(this.api.get<SessionStats>('/tokens/session-stats'));
      this.sessionStats.set(stats);
    } catch {
      this.statsError.set(true);
    } finally {
      this.statsLoading.set(false);
    }
  }

  cacheHitPercentage(): number | null {
    const u = this.tokenUsage;
    if (!u || !u.cachedTokens || !u.promptTokens) return null;
    return Math.round((u.cachedTokens / u.promptTokens) * 100);
  }

  uncachedInput(): number {
    const u = this.tokenUsage;
    if (!u) return 0;
    return Math.max(0, u.promptTokens - (u.cachedTokens ?? 0));
  }

  close(): void {
    this.closed.emit();
  }
}
