import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';

/** Loosely typed — the backend endpoint is currently a stub (see below) so the real result
 *  shape isn't settled yet. Known fields are read defensively; anything else just isn't shown. */
interface BenchmarkResult {
  strategy?: string;
  label?: string;
  mode?: string;
  totalTokens?: number;
  inputTokens?: number;
  outputTokens?: number;
  latencyMs?: number;
  notes?: string;
  responsePreview?: string;
}

interface BenchmarkResponse {
  results: BenchmarkResult[];
  message?: string;
}

/**
 * Runs POST /api/benchmark in "modelled" or "live" mode. Port of the old React app's
 * BenchmarkModal.tsx.
 *
 * As of this port, SystemController.benchmark() (backend/src/main/java/com/yuzee/tokenlab/
 * controller/SystemController.java) is still a stub: it always returns
 * `{ results: [], message: "Benchmark not available in Java port" }` regardless of the request
 * body — there is no BenchmarkService implementing real modelled/live runs yet. This component
 * still sends the full request shape (conversationId, prompt, model, strategies, isLive) so it
 * needs no changes once that service is built, and it surfaces the stub's `message` directly to
 * the user instead of pretending results exist. The strategy list is limited to the three
 * strategies MemoryStrategy.java actually implements (BASELINE, SEMANTIC_EVIDENCE,
 * BUDGET_EVICTION) rather than the old app's five, since the other two don't exist server-side.
 */
@Component({
  selector: 'app-benchmark-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './benchmark-modal.component.html',
  styleUrl: './benchmark-modal.component.scss'
})
export class BenchmarkModalComponent {
  @Input() open = false;
  @Input() conversationId: string | null = null;
  @Input() modelId = 'gemini-3.6-flash';

  @Output() closed = new EventEmitter<void>();

  readonly strategies: string[] = ['BASELINE', 'SEMANTIC_EVIDENCE', 'BUDGET_EVICTION'];

  prompt = signal('Help me transition into cybersecurity and build a 6-month study roadmap.');
  isLive = signal(false);
  running = signal(false);
  results = signal<BenchmarkResult[] | null>(null);
  message = signal<string | null>(null);
  error = signal<string | null>(null);

  constructor(private api: ApiService) {}

  setPrompt(value: string): void {
    this.prompt.set(value);
  }

  setLive(live: boolean): void {
    this.isLive.set(live);
  }

  async run(): Promise<void> {
    if (!this.prompt().trim() || this.running()) return;
    this.running.set(true);
    this.error.set(null);
    this.message.set(null);
    try {
      const res = await firstValueFrom(
        this.api.post<BenchmarkResponse>('/benchmark', {
          conversationId: this.conversationId,
          prompt: this.prompt(),
          model: this.modelId,
          strategies: this.strategies,
          isLive: this.isLive()
        })
      );
      this.results.set(res.results ?? []);
      this.message.set(res.message ?? null);
    } catch {
      this.error.set('Benchmark request failed.');
    } finally {
      this.running.set(false);
    }
  }

  close(): void {
    this.closed.emit();
  }
}
