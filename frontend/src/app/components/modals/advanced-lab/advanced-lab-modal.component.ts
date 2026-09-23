import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, computed, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { TokenLabService } from '../../../services/token-lab.service';
import { Conversation, OptimizationStrategy, ResponseMode, ThinkingLevel } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { RouterModelSelectorComponent } from '../../shared/router-model-selector/router-model-selector.component';
import { RangeSliderComponent } from '../../shared/ui-kit/range-slider/range-slider.component';
import { ToggleSwitchComponent } from '../../shared/ui-kit/toggle-switch/toggle-switch.component';

/** Mirrors the original's StructuredMemoryCapsule. */
interface StructuredMemoryCapsule { facts: string; goals: string; constraints: string; decisions: string; openThreads: string; }

/** Per-conversation settings the original Conversation carries; not (yet) on models/types.ts Conversation. */
interface LabConversation extends Omit<Conversation, 'careerContext' | 'createdAt' | 'updatedAt' | 'messages'> {
  careerContext?: StructuredMemoryCapsule;
  useInteractionsApi?: boolean;
  useFlashLiteUtility?: boolean;
  usePathwayRag?: boolean;
}

// prompts/quizPrompt.ts QUIZ_PROMPT_VERSION in the original.
const QUIZ_PROMPT_VERSION = '1.11';
// data/models.ts: GEMINI_MODELS.filter(m => m.selectable).map(m => m.id) in the original.
const SELECTABLE_MODEL_IDS = [
  'gemini-3.7-flash', 'gemini-3.8-flash', 'gemini-3.6-flash', 'gemini-3.5-flash',
  'gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-2.5-flash', 'gemini-2.5-flash-lite'
];
const DEFAULT_MODEL_ID = 'gemini-3.7-flash';
const EMPTY_CAPSULE: StructuredMemoryCapsule = { facts: '', goals: '', constraints: '', decisions: '', openThreads: '' };
const BENCHMARK_STRATEGIES = ['BASELINE', 'SUMMARY_RECENT', 'ADAPTIVE_HYBRID', 'SEMANTIC_EVIDENCE'] as const;

/** 1:1 port of yuzee-ai-token-lab/src/components/AdvancedLabModal.tsx. */
@Component({
  selector: 'app-advanced-lab-modal',
  standalone: true,
  imports: [IconComponent, ConfirmDialogComponent, RouterModelSelectorComponent, RangeSliderComponent, ToggleSwitchComponent],
  templateUrl: './advanced-lab-modal.component.html',
  styleUrl: './advanced-lab-modal.component.scss'
})
export class AdvancedLabModalComponent implements OnChanges {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  readonly quizPromptVersion = QUIZ_PROMPT_VERSION;
  readonly modelIds = SELECTABLE_MODEL_IDS;
  readonly benchmarkStrategyIds = BENCHMARK_STRATEGIES;

  readonly navTabs = [
    { id: 'context', label: 'Context & Memory', icon: 'Layers' },
    { id: 'reasoning', label: 'Thinking & Reasoning', icon: 'Brain' },
    { id: 'prompt', label: 'Prompt & Response', icon: 'FileText' },
    { id: 'generation', label: 'Generation Parameters', icon: 'Wand2' },
    { id: 'optimization', label: 'Optimization & Economics', icon: 'Sliders' },
    { id: 'benchmark', label: 'Benchmark Matrix', icon: 'Play' },
    { id: 'analytics', label: 'Session Analytics', icon: 'BarChart3' }
  ];

  readonly strategies = [
    { id: 'ADAPTIVE_HYBRID', title: 'Adaptive Hybrid (Recommended)', desc: 'Prioritized budget + stable prefix caching + incremental compaction.' },
    { id: 'SEMANTIC_EVIDENCE', title: 'Semantic Evidence (Experimental)', desc: 'Episodic memory with typed temporal records. Retrieves relevant evidence from past episodes instead of compacting.' },
    { id: 'SUMMARY_RECENT', title: 'Summary + Recent Turns', desc: 'Compacts older turns into a semantic summary while retaining last N turns.' },
    { id: 'BASELINE', title: 'Baseline (Full History)', desc: 'Sends full historical transcript without compaction (unbounded token growth).' }
  ];

  readonly capsuleFields: { key: keyof StructuredMemoryCapsule; label: string; placeholder: string }[] = [
    { key: 'facts', label: 'Facts & Background', placeholder: 'e.g. 2 years IT support, Linux CLI, CompTIA Network+' },
    { key: 'goals', label: 'Target Roles & Goals', placeholder: 'e.g. Junior SOC Analyst within 6-9 months' },
    { key: 'constraints', label: 'Constraints (Budget & Time)', placeholder: 'e.g. Under $1,000 learning budget, 12 hrs/week' },
    { key: 'decisions', label: 'Agreed Decisions', placeholder: 'e.g. Prioritizing CompTIA Security+ over CySA+ first' },
    { key: 'openThreads', label: 'Open Threads & Questions', placeholder: 'e.g. Evaluating TryHackMe SOC Level 1 vs BTL1' }
  ];

  readonly thinkingLevels = [
    { id: 'minimal', label: 'Minimal', tokens: '0 tokens', desc: 'Direct response' },
    { id: 'low', label: 'Low', tokens: '128 tokens', desc: 'Light validation' },
    { id: 'medium', label: 'Medium', tokens: '512 tokens', desc: 'Structured trade-offs' },
    { id: 'high', label: 'High', tokens: '1,024 tokens', desc: 'Deep multi-step logic' },
    { id: 'adaptive', label: 'Adaptive (Auto)', tokens: 'Dynamic', desc: 'Zero-cost classifier' }
  ];

  readonly responseModes = [
    { id: 'standard', label: 'Standard', desc: 'Balanced steps & advice' },
    { id: 'quick', label: 'Quick / Concise', desc: 'High-density bullet points' },
    { id: 'explain', label: 'Explain Deeply', desc: 'Prerequisites & concepts' },
    { id: 'explore', label: 'Explore Paths', desc: 'Comparative pathways' },
    { id: 'decide', label: 'Decision Matrix', desc: 'Pros, cons & costs' },
    { id: 'vanilla', label: 'Vanilla', desc: 'No cap — 8192 tokens, AI Studio parity' }
  ];

  // Local state (same names/defaults as the original's useState hooks)
  benchmarkPrompt = signal('Help me transition into cybersecurity and build a 6-month study roadmap.');
  benchmarkResults = signal<any[] | null>(null);
  isBenchmarking = signal(false);
  isResetMemoryConfirmOpen = signal(false);
  isResetStatsConfirmOpen = signal(false);
  benchmarkModel = signal('');
  benchmarkStrategies = signal<string[]>([...BENCHMARK_STRATEGIES]);
  benchmarkIsLive = signal(false);
  defaultPromptContent = signal('');
  showPromptContent = signal(false);
  isReloadingPrompt = signal(false);
  reloadPromptStatus = signal<string | null>(null);

  /** `conv` in the original: currentConversation or this exact defaults object. */
  conv = computed<LabConversation>(() => (this.lab.currentConversation() as LabConversation | null) ?? {
    id: 'default',
    title: 'Career Exploration',
    model: DEFAULT_MODEL_ID,
    strategy: 'ADAPTIVE_HYBRID',
    contextBudget: 270000,
    recentTurnsToKeep: 100,
    thinkingLevel: 'adaptive',
    responseMode: 'standard',
    careerContext: { ...EMPTY_CAPSULE },
    systemPromptMode: 'default',
    useInteractionsApi: false,
    useFlashLiteUtility: true,
    temperature: undefined,
    topP: undefined,
    maxOutputTokens: undefined,
    useMultiTurn: true,
    useStructuredOutput: false,
    usePathwayRag: false,
  });
  currentCapsule = computed<StructuredMemoryCapsule>(() => this.conv().careerContext || EMPTY_CAPSULE);

  constructor(private api: ApiService, public lab: TokenLabService) {}

  /** Original useEffect([isAdvancedLabOpen, defaultPromptContent]): fetch the prompt once per open until loaded. */
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open && !this.defaultPromptContent()) this.fetchSystemPrompt();
  }

  // ---- context-backed state/actions ----

  activeLabTab(): string { return this.lab.activeLabTab(); }

  effectiveTab(): string {
    const t = this.activeLabTab();
    return this.navTabs.some(n => n.id === t) ? t : 'context';
  }

  setActiveLabTab(id: string): void { this.lab.activeLabTab.set(id); }

  sharedSettings() { return this.lab.sharedSettings(); }

  updateSharedSettings(patch: Parameters<TokenLabService['updateSharedSettings']>[0]): Promise<void> {
    return this.lab.updateSharedSettings(patch);
  }

  updateCurrentConversationSettings(updates: Partial<LabConversation>): Promise<void> {
    return this.lab.updateCurrentConversationSettings(updates as Partial<Conversation>);
  }

  // ---- handlers ----

  close(): void {
    this.lab.isAdvancedLabOpen.set(false);
    this.closed.emit();
  }

  handleCareerFieldChange(field: keyof StructuredMemoryCapsule, value: string): void {
    this.updateCurrentConversationSettings({ careerContext: { ...(this.conv().careerContext || EMPTY_CAPSULE), [field]: value } });
  }

  inputValue(e: Event): string { return (e.target as HTMLInputElement).value; }

  private fetchSystemPrompt(): void {
    firstValueFrom(this.api.get<{ content: string }>('/system-prompt'))
      .then(r => this.defaultPromptContent.set(r.content)).catch(() => {});
  }

  async reloadPrompt(): Promise<void> {
    this.isReloadingPrompt.set(true);
    this.reloadPromptStatus.set(null);
    try {
      const r = await firstValueFrom(this.api.post<{ ok: boolean; hash: string; bytes: number }>('/system-prompt/reload', null))
        .catch((e: any) => { throw new Error(`Failed to reload prompt: ${e?.status}`); });
      const hadContent = !!this.defaultPromptContent();
      this.defaultPromptContent.set('');
      this.fetchSystemPrompt();
      // The original's [isAdvancedLabOpen, defaultPromptContent] effect re-fires when the content changes to '': a second GET.
      if (hadContent) this.fetchSystemPrompt();
      this.reloadPromptStatus.set(`Reloaded — ${(r.bytes / 1024).toFixed(1)} KB · ${r.hash.slice(0, 8)}`);
    } catch (e: any) {
      this.reloadPromptStatus.set(`Error: ${e.message}`);
    } finally {
      this.isReloadingPrompt.set(false);
    }
  }

  toggleBenchmarkStrategy(s: string, checked: boolean): void {
    this.benchmarkStrategies.update(prev => checked ? [...prev, s] : prev.filter(x => x !== s));
  }

  async runBenchmarkTest(): Promise<void> {
    try {
      this.isBenchmarking.set(true);
      const res = await firstValueFrom(this.api.post<{ results: any[] }>('/benchmark', {
        conversationId: this.conv().id,
        prompt: this.benchmarkPrompt(),
        model: this.benchmarkModel() || this.conv().model,
        strategies: this.benchmarkStrategies() as OptimizationStrategy[],
        isLive: this.benchmarkIsLive(),
      }));
      this.benchmarkResults.set(res.results);
    } catch (e) {
      console.error('Benchmark failed:', e);
    } finally {
      this.isBenchmarking.set(false);
    }
  }

  exportConversationJSON(): void {
    const conv = this.conv();
    const dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(conv, null, 2));
    const a = document.createElement('a');
    a.setAttribute('href', dataStr);
    a.setAttribute('download', `yuzee-token-lab-${conv.id}.json`);
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  stats(): any { return this.lab.sessionStats(); }

  /** `n?.toLocaleString() ?? "—"` */
  fmt(n: any): string { return n?.toLocaleString() ?? '—'; }

  /** `s.replace(/_/g, " ")` */
  strategyLabel(s: string): string { return s.replace(/_/g, ' '); }

  asStrategy(id: string): OptimizationStrategy { return id as OptimizationStrategy; }
  asThinking(id: string): ThinkingLevel { return id as ThinkingLevel; }
  asResponseMode(id: string): ResponseMode { return id as ResponseMode; }

  confirmResetMemory(): void {
    this.lab.resetMemory();
    this.isResetMemoryConfirmOpen.set(false);
  }

  confirmResetStats(): void {
    this.lab.resetSessionStats();
    this.isResetStatsConfirmOpen.set(false);
  }
}
