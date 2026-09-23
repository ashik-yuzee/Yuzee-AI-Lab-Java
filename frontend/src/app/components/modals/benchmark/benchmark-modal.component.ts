import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { TokenLabService } from '../../../services/token-lab.service';
import { IconComponent } from '../../shared/icon/icon.component';

/** The original's BenchmarkResult (types.ts), as rendered. */
interface BenchmarkResultView {
  strategy: string;
  label: string;
  mode?: 'live' | 'estimated';
  totalTokens: number;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
  ttftMs?: number | null;
  generationMs?: number | null;
  thinkingTokens: number | null;
  cachedTokens: number | null;
  notes: string;
  responsePreview: string;
}

const SCENARIO_STEPS = [
  '1. User wants to become a cybersecurity analyst.',
  '2. User describes existing IT skills.',
  '3. User asks for required skills.',
  '4. User asks for courses.',
  '5. User changes timeline.',
  '6. User adds a budget constraint.',
  '7. User compares two routes.',
  '8. User asks which previous recommendation still applies.',
  '9. User changes one constraint.',
  '10. User asks for the final pathway.',
];

/** 1:1 port of BenchmarkModal.tsx: POST /api/benchmark with the original's request fields and result rows. */
@Component({
  selector: 'app-benchmark-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './benchmark-modal.component.html',
  styleUrl: './benchmark-modal.component.scss'
})
export class BenchmarkModalComponent {
  @Input() open = false;

  @Output() closed = new EventEmitter<void>();

  readonly scenarioSteps = SCENARIO_STEPS;

  prompt = signal('Help me transition into cybersecurity and build a 6-month study roadmap.');
  isLive = signal(false);
  isRunning = signal(false);
  results = signal<BenchmarkResultView[] | null>(null);

  constructor(private api: ApiService, private lab: TokenLabService) {}

  liveResults(): boolean {
    return this.results()?.[0]?.mode === 'live';
  }

  onPrompt(e: Event): void {
    this.prompt.set((e.target as HTMLInputElement).value);
  }

  close(): void {
    this.lab.isBenchmarkOpen.set(false);
    this.closed.emit();
  }

  async run(): Promise<void> {
    this.isRunning.set(true);
    const conv = this.lab.currentConversation();
    try {
      const res = await firstValueFrom(this.api.post<{ results: BenchmarkResultView[] }>('/benchmark', {
        conversationId: conv?.id,
        prompt: this.prompt(),
        model: conv?.model || 'gemini-3.5-flash-lite',
        strategies: ['BASELINE', 'SLIDING_WINDOW', 'SUMMARY_RECENT', 'ADAPTIVE_HYBRID', 'SEMANTIC_EVIDENCE'],
        isLive: this.isLive(),
      }));
      this.results.set(res.results);
    } catch (e) {
      console.error('Benchmark failed:', e);
    } finally {
      this.isRunning.set(false);
    }
  }
}
