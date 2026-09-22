import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { TokenLabService } from '../../../services/token-lab.service';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { RouterModelSelectorComponent } from '../../shared/router-model-selector/router-model-selector.component';
import { OptimizationStrategy, ResponseMode } from '../../../models/types';

type LabTab = 'memory' | 'prompt' | 'routing' | 'benchmark' | 'analytics';

interface StrategyOption { id: OptimizationStrategy; title: string; desc: string; }
interface ResponseModeOption { id: ResponseMode; label: string; desc: string; }
interface SystemPromptInfo { version: string; hash: string; byteSize: number; label: string; }

/**
 * Port of AdvancedLabModal.tsx (yuzee-ai-token-lab/src/components/AdvancedLabModal.tsx, 923
 * lines), rebuilt against what the Java backend actually persists/exposes rather than the old
 * app's full field set. See this component's ngOnInit-adjacent methods and the file-level report
 * for exactly what was kept, dropped, or turned into a read-only note, and why:
 *
 * - Kept as real, working controls: `strategy` and `responseMode` (Conversation fields, PUT
 *   /api/conversations/{id}), reset-memory (POST .../reset-memory), system-prompt metadata +
 *   reload (GET /api/system-prompt, POST /api/system-prompt/reload), session stats + reset
 *   (TokenLabService.sessionStats / resetSessionStats()), and the router model status (via
 *   RouterModelSelectorComponent).
 * - Dropped entirely (no backend field, and a fake control would be worse than no control):
 *   contextBudget / recentTurnsToKeep sliders, thinkingLevel picker (server picks this per-turn —
 *   see the read-only note in the memory tab instead), structured memory capsule editor (this
 *   app already has a dedicated Career Context modal for that), useInteractionsApi /
 *   useFlashLiteUtility toggles, and the old inline benchmark tab (this app already has a
 *   dedicated Benchmark modal — see `openBenchmark`).
 * - Simplified: the old "View Prompt" content viewer is gone because
 *   SystemPromptService.getInfo() only returns version/hash/byteSize/label, never the prompt
 *   text — there is no endpoint to fetch it from.
 */
@Component({
  selector: 'app-advanced-lab-modal',
  standalone: true,
  imports: [CommonModule, ConfirmDialogComponent, RouterModelSelectorComponent],
  templateUrl: './advanced-lab-modal.component.html',
  styleUrl: './advanced-lab-modal.component.scss'
})
export class AdvancedLabModalComponent implements OnChanges {
  @Input() open = false;
  @Input() conversationId: string | null = null;
  @Input() modelId = 'gemini-3.6-flash';

  @Output() closed = new EventEmitter<void>();
  /** Integration point: this modal has no benchmark logic of its own — the parent should open
   *  the existing BenchmarkModalComponent when this fires. */
  @Output() openBenchmark = new EventEmitter<void>();

  readonly tabs: { id: LabTab; label: string }[] = [
    { id: 'memory', label: 'Strategy & Memory' },
    { id: 'prompt', label: 'Prompt & Response' },
    { id: 'routing', label: 'Routing' },
    { id: 'benchmark', label: 'Benchmark' },
    { id: 'analytics', label: 'Session Analytics' }
  ];

  activeTab = signal<LabTab>('memory');

  readonly strategies: StrategyOption[] = [
    { id: 'ADAPTIVE_HYBRID', title: 'Adaptive Hybrid (Recommended)', desc: 'Prioritized budget + stable prefix caching + incremental compaction.' },
    { id: 'SEMANTIC_EVIDENCE', title: 'Semantic Evidence (Experimental)', desc: 'Episodic memory with typed temporal records; retrieves relevant evidence instead of compacting.' },
    { id: 'SUMMARY_RECENT', title: 'Summary + Recent Turns', desc: 'Compacts older turns into a semantic summary while retaining the most recent turns.' },
    { id: 'SLIDING_WINDOW', title: 'Sliding Window', desc: 'Keeps only the most recent turns within a fixed window; older turns are dropped, not summarized.' },
    { id: 'BASELINE', title: 'Baseline (Full History)', desc: 'Sends the full historical transcript without compaction (unbounded token growth).' }
  ];

  readonly responseModes: ResponseModeOption[] = [
    { id: 'standard', label: 'Standard', desc: 'Balanced steps & advice' },
    { id: 'quick', label: 'Quick / Concise', desc: 'High-density bullet points' },
    { id: 'explain', label: 'Explain Deeply', desc: 'Prerequisites & concepts' },
    { id: 'explore', label: 'Explore Paths', desc: 'Comparative pathways' },
    { id: 'detail', label: 'Detail', desc: 'Longer, more thorough responses' },
    { id: 'decide', label: 'Decision Matrix', desc: 'Pros, cons & costs' },
    { id: 'vanilla', label: 'Vanilla', desc: 'No cap — AI Studio parity' }
  ];

  conversation = computed(() => this.lab.conversations().find(c => c.id === this.conversationId) ?? null);
  sessionStats: TokenLabService['sessionStats'];

  savingStrategy = signal(false);
  savingResponseMode = signal(false);

  promptInfo = signal<SystemPromptInfo | null>(null);
  loadingPromptInfo = signal(false);
  reloadingPrompt = signal(false);
  reloadStatus = signal<string | null>(null);
  reloadStatusIsError = signal(false);

  resetMemoryConfirmOpen = signal(false);
  resettingMemory = signal(false);
  resetMemoryStatus = signal<string | null>(null);

  resetStatsConfirmOpen = signal(false);

  constructor(private api: ApiService, public lab: TokenLabService) {
    this.sessionStats = this.lab.sessionStats;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open) {
      this.loadPromptInfo();
      this.lab.loadSessionStats();
    }
  }

  setActiveTab(tab: LabTab): void {
    this.activeTab.set(tab);
  }

  close(): void {
    this.closed.emit();
  }

  triggerBenchmark(): void {
    this.openBenchmark.emit();
    this.closed.emit();
  }

  async selectStrategy(id: OptimizationStrategy): Promise<void> {
    const conv = this.conversation();
    if (!conv || conv.strategy === id || this.savingStrategy()) return;
    this.savingStrategy.set(true);
    try {
      await firstValueFrom(this.api.put(`/conversations/${conv.id}`, { strategy: id }));
      this.lab.conversations.update(cs => cs.map(c => c.id === conv.id ? { ...c, strategy: id } : c));
    } finally {
      this.savingStrategy.set(false);
    }
  }

  async selectResponseMode(id: ResponseMode): Promise<void> {
    const conv = this.conversation();
    if (!conv || conv.responseMode === id || this.savingResponseMode()) return;
    this.savingResponseMode.set(true);
    try {
      await firstValueFrom(this.api.put(`/conversations/${conv.id}`, { responseMode: id }));
      this.lab.conversations.update(cs => cs.map(c => c.id === conv.id ? { ...c, responseMode: id } : c));
    } finally {
      this.savingResponseMode.set(false);
    }
  }

  async loadPromptInfo(): Promise<void> {
    this.loadingPromptInfo.set(true);
    try {
      const info = await firstValueFrom(this.api.get<SystemPromptInfo>('/system-prompt'));
      this.promptInfo.set(info);
    } catch {
      this.promptInfo.set(null);
    } finally {
      this.loadingPromptInfo.set(false);
    }
  }

  async reloadPrompt(): Promise<void> {
    if (this.reloadingPrompt()) return;
    this.reloadingPrompt.set(true);
    this.reloadStatus.set(null);
    try {
      await firstValueFrom(this.api.post('/system-prompt/reload'));
      await this.loadPromptInfo();
      this.reloadStatusIsError.set(false);
      const info = this.promptInfo();
      this.reloadStatus.set(info ? `Reloaded — v${info.version} · ${info.hash}` : 'Reloaded.');
    } catch {
      this.reloadStatusIsError.set(true);
      this.reloadStatus.set('Reload failed.');
    } finally {
      this.reloadingPrompt.set(false);
    }
  }

  openResetMemoryConfirm(): void {
    if (!this.conversation()) return;
    this.resetMemoryConfirmOpen.set(true);
  }

  async confirmResetMemory(): Promise<void> {
    const conv = this.conversation();
    this.resetMemoryConfirmOpen.set(false);
    if (!conv) return;
    this.resettingMemory.set(true);
    this.resetMemoryStatus.set(null);
    try {
      await firstValueFrom(this.api.post(`/conversations/${conv.id}/reset-memory`));
      this.lab.conversations.update(cs => cs.map(c => c.id === conv.id ? { ...c, summaryText: undefined } : c));
      this.resetMemoryStatus.set('Memory reset.');
    } catch {
      this.resetMemoryStatus.set('Reset failed.');
    } finally {
      this.resettingMemory.set(false);
    }
  }

  openResetStatsConfirm(): void {
    this.resetStatsConfirmOpen.set(true);
  }

  async confirmResetStats(): Promise<void> {
    this.resetStatsConfirmOpen.set(false);
    await this.lab.resetSessionStats();
  }

  exportTelemetryJson(): void {
    const conv = this.conversation();
    if (!conv) return;
    const blob = new Blob([JSON.stringify(conv, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `yuzee-token-lab-${conv.id}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }
}
