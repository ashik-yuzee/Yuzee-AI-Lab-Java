import { Component, ElementRef, Input, OnChanges, OnDestroy, SimpleChanges, ViewChild, inject } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { AuthService } from '../../services/auth.service';
import { ResearchAnswerCardComponent } from './research-answer-card.component';
import { DetailRequest, DetailResult, NextQuestionKind, ResearchOffer } from './research-details.types';

let nextId = 0;
const stageLabels: Record<string, string> = { searching: 'Looking for relevant sources…', analysing: 'Preparing your answer…', reviewing: 'Checking the answer…', saving: 'Saving your answer…', cached: 'Opening your saved answer…' };

/** Port of the original's components/MoreDetails.tsx ("Explore more" research panel under an answer). */
@Component({
  selector: 'app-research-details',
  standalone: true,
  imports: [IconComponent, ResearchAnswerCardComponent],
  styles: [':host{display:contents}'],
  templateUrl: './research-details.component.html',
})
export class ResearchDetailsComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) offer!: ResearchOffer;
  @Input({ required: true }) conversationId!: string;
  @Input({ required: true }) parentMessageId!: string;
  @Input() disabled = false;
  /** The original's `onUse` (sendMessage): resolves false when the reply could not be added. */
  @Input({ required: true }) onUse!: (message: string) => Promise<boolean>;
  @ViewChild('questionRef') questionRef?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('formRef') formRef?: ElementRef<HTMLFormElement>;
  @ViewChild('answerRef') answerRef?: ElementRef<HTMLDivElement>;

  readonly id = `research-${nextId++}`;
  stageLabels = stageLabels;
  open = false;
  target = ''; question = ''; studyYear = ''; location = '';
  results: DetailResult[] = [];
  selectedId = '';
  editingScope = false;
  replyTo = '';
  stage = '';
  error = '';
  refresh = false;
  loadingSaved = false;
  private controller: AbortController | null = null;
  private loaded = false;
  private loadAbort: AbortController | null = null;
  private auth = inject(AuthService);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['offer']?.firstChange) {
      const o = this.offer;
      this.target = o.scope.target; this.question = o.question; this.studyYear = o.scope.studyYear; this.location = o.scope.location;
      this.editingScope = !o.scope.target;
    }
  }

  ngOnDestroy(): void { this.controller?.abort(); this.loadAbort?.abort(); }

  get active(): boolean { return Boolean(this.stage); }
  get selected(): DetailResult | undefined { return this.results.find(r => r.id === this.selectedId) || this.results.at(-1); }
  get reversed(): DetailResult[] { return [...this.results].reverse(); }
  get questionLimit(): number { return this.replyTo ? Math.max(1, 1500 - this.replyTo.length - 25) : 1500; }
  get scopeText(): string { return [this.studyYear, this.location].filter(Boolean).join(' · ') || 'Year and location not added'; }
  historyDate(r: DetailResult): string { return new Date(r.retrievedAt).toLocaleDateString(); }

  private get headers(): Record<string, string> {
    const token = this.auth.token;
    return token ? { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } : { 'Content-Type': 'application/json' };
  }

  toggle(): void {
    this.open = !this.open;
    if (!this.open) { this.loadAbort?.abort(); return; }
    if (this.loaded) return;
    const abort = this.loadAbort = new AbortController();
    this.loadingSaved = true;
    fetch(`/api/conversations/${encodeURIComponent(this.conversationId)}/details`, { signal: abort.signal, headers: this.headers })
      .then(async r => { if (!r.ok) throw new Error('Saved answers could not be loaded. Close and reopen this section to retry.'); return r.json(); })
      .then((saved: DetailResult[]) => {
        const own = saved.filter(r => r.request.parentMessageId === this.parentMessageId);
        this.results = own; this.loaded = true;
        const last = own.at(-1);
        if (last) { this.selectedId = last.id; this.useScope(last); }
      })
      .catch(e => { if (!abort.signal.aborted) this.error = e.message; })
      .finally(() => { if (!abort.signal.aborted) this.loadingSaved = false; });
  }

  private useScope(result: DetailResult): void {
    this.target = result.request.target; this.studyYear = result.request.studyYear; this.location = result.request.location; this.editingScope = false;
  }

  pick(result: DetailResult): void {
    this.selectedId = result.id;
    if (!this.question.trim()) { this.useScope(result); this.replyTo = ''; }
    this.error = '';
  }

  prepareQuestion(event: { text: string; kind: NextQuestionKind }): void {
    if (this.selected) this.useScope(this.selected);
    this.replyTo = event.kind === 'ask_user' ? event.text : '';
    this.question = event.kind === 'ask_user' ? '' : event.text;
    this.error = '';
    requestAnimationFrame(() => { this.formRef?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'center' }); this.questionRef?.nativeElement.focus({ preventScroll: true }); });
  }

  cancelReply(): void { this.replyTo = ''; this.question = ''; }
  stop(): void { this.controller?.abort(); this.error = 'Search stopped. Your question has been kept.'; }
  value(event: Event): string { return (event.target as HTMLInputElement).value; }

  use = async (): Promise<void> => {
    const selected = this.selected;
    if (!selected) return;
    this.error = '';
    const message = `Use the research about ${selected.request.target} to help me decide what to check next. Treat study hours as estimates, keep unresolved points clear, and do not assume the course fits my schedule or that I qualify for payments. Research reference: ${selected.id}`;
    try {
      const ok = await this.onUse(message);
      if (!ok) this.error = 'Your research is saved. Please try adding it to your plan again.';
    } catch { this.error = 'Your research is saved. Please try adding it to your plan again.'; }
  };

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.controller || this.disabled || this.loadingSaved) return;
    const abort = new AbortController(); this.controller = abort;
    this.stage = 'searching'; this.error = '';
    try {
      const question = this.replyTo ? `Replying to: ${this.replyTo}\nMy answer: ${this.question}` : this.question;
      const result = await this.research({ parentMessageId: this.parentMessageId, target: this.target, question, studyYear: this.studyYear, location: this.location, refresh: this.refresh }, abort.signal);
      if (!abort.signal.aborted) {
        this.results = [...this.results.filter(r => r.id !== result.id), result]; this.selectedId = result.id;
        this.question = ''; this.replyTo = ''; this.editingScope = false;
        requestAnimationFrame(() => this.answerRef?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      }
    } catch (e: any) { if (!abort.signal.aborted) this.error = e.message; }
    finally { if (this.controller === abort) { this.controller = null; this.stage = ''; } }
  }

  /** research/client.ts researchDetails(). */
  private async research(request: DetailRequest, signal: AbortSignal): Promise<DetailResult> {
    const response = await fetch(`/api/conversations/${encodeURIComponent(this.conversationId)}/details`, { method: 'POST', headers: this.headers, body: JSON.stringify(request), signal });
    if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.error || 'This search could not start. Please try again.'); }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('This search could not start.');
    const decoder = new TextDecoder();
    let buffer = '', result: DetailResult | undefined;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        let end: number;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const line = buffer.slice(0, end); buffer = buffer.slice(end + 2);
          if (!line.startsWith('data: ')) continue;
          const event = JSON.parse(line.slice(6));
          if (event.type === 'progress') this.stage = event.stage;
          if (event.type === 'error') throw new Error(event.error);
          if (event.type === 'result') result = event.result;
        }
      }
    } finally { reader.releaseLock(); }
    if (!result) throw new Error('The connection ended before the answer arrived. Your question has been kept.');
    return result;
  }
}
