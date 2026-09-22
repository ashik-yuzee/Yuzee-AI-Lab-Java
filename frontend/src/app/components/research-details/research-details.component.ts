import { Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { ResearchAnswerCardComponent } from './research-answer-card.component';
import {
  DetailRequest,
  DetailResult,
  DetailStreamEvent,
  NextQuestionKind,
  ResearchOffer,
} from './research-details.types';

const STAGE_LABEL: Record<string, string> = {
  searching: 'Looking for relevant sources…',
  analysing: 'Preparing your answer…',
  reviewing: 'Checking the answer…',
  saving: 'Saving your answer…',
  cached: 'Opening your saved answer…',
};

/**
 * Port of yuzee-ai-token-lab/src/components/MoreDetails.tsx: the expandable "check course
 * details" research panel that renders under an assistant message. Renders nothing unless
 * `offer` is supplied — see class doc on `offer` below.
 *
 * The integration engineer wires this into chat-area.component.html once
 * TurnNeedsService.assessTurnNeeds()/researchOffer() are attached to the chat turn response;
 * until then `offer` simply stays null/undefined wherever nobody binds it, and the panel renders
 * nothing (matches the old app's `{structured && offer && ... && <MoreDetails ... />}` guard).
 */
@Component({
  selector: 'app-research-details',
  standalone: true,
  imports: [CommonModule, FormsModule, ResearchAnswerCardComponent],
  templateUrl: './research-details.component.html',
  styleUrl: './research-details.component.scss',
})
export class ResearchDetailsComponent implements OnChanges, OnDestroy {
  @Input() offer: ResearchOffer | null = null;
  @Input({ required: true }) conversationId!: string;
  @Input({ required: true }) parentMessageId!: string;
  @Input() disabled = false;

  @Output() useInPlan = new EventEmitter<string>();

  @ViewChild('questionInput') questionInputRef?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('answerAnchor') answerAnchorRef?: ElementRef<HTMLElement>;

  open = false;
  target = '';
  question = '';
  studyYear = '';
  location = '';
  editingScope = false;
  replyTo = '';
  refresh = false;
  stage = '';
  error = '';
  loadingSaved = false;
  results: DetailResult[] = [];
  selectedId = '';

  readonly stageLabels = STAGE_LABEL;

  private initializedFromOffer = false;
  private loadedSaved = false;
  private activeStream: { close: () => void } | null = null;

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['offer'] && this.offer && !this.initializedFromOffer) {
      this.initializedFromOffer = true;
      this.target = this.offer.scope?.target ?? '';
      this.question = this.offer.question ?? '';
      this.studyYear = this.offer.scope?.studyYear ?? '';
      this.location = this.offer.scope?.location ?? '';
      this.editingScope = !this.target;
    }
  }

  ngOnDestroy(): void {
    this.activeStream?.close();
  }

  get active(): boolean {
    return Boolean(this.stage);
  }

  get selected(): DetailResult | undefined {
    return this.results.find(r => r.id === this.selectedId) ?? this.results[this.results.length - 1];
  }

  get savedAnswersReversed(): DetailResult[] {
    return [...this.results].reverse();
  }

  get questionLimit(): number {
    return this.replyTo ? Math.max(1, 1500 - this.replyTo.length - 25) : 1500;
  }

  get stageLabel(): string {
    return this.stageLabels[this.stage] ?? '';
  }

  get scopeSummaryText(): string {
    return [this.studyYear, this.location].filter(Boolean).join(' · ') || 'Year and location not added';
  }

  isSelected(result: DetailResult): boolean {
    return this.selected?.id === result.id;
  }

  historyLabel(index: number, result: DetailResult): string {
    return index === 0 ? 'Latest' : new Date(result.retrievedAt).toLocaleDateString();
  }

  toggle(): void {
    this.open = !this.open;
    if (this.open && !this.loadedSaved) {
      this.loadSaved();
    }
  }

  private async loadSaved(): Promise<void> {
    this.loadingSaved = true;
    try {
      const saved = await firstValueFrom(
        this.api.get<DetailResult[]>(`/conversations/${encodeURIComponent(this.conversationId)}/details`)
      );
      const own = (saved ?? []).filter(r => r.request.parentMessageId === this.parentMessageId);
      this.results = own;
      this.loadedSaved = true;
      const last = own[own.length - 1];
      if (last) {
        this.selectedId = last.id;
        this.useScope(last);
      }
    } catch {
      this.error = 'Saved answers could not be loaded. Close and reopen this section to retry.';
    } finally {
      this.loadingSaved = false;
    }
  }

  private useScope(result: DetailResult): void {
    this.target = result.request.target;
    this.studyYear = result.request.studyYear;
    this.location = result.request.location;
    this.editingScope = false;
  }

  selectResult(result: DetailResult): void {
    if (this.active || this.disabled) return;
    this.selectedId = result.id;
    if (!this.question.trim()) {
      this.useScope(result);
      this.replyTo = '';
    }
    this.error = '';
  }

  setEditingScope(value: boolean): void {
    this.editingScope = value;
  }

  cancelReply(): void {
    this.replyTo = '';
    this.question = '';
  }

  onAskQuestion(event: { text: string; kind: NextQuestionKind }): void {
    const sel = this.selected;
    if (sel) this.useScope(sel);
    this.replyTo = event.kind === 'ask_user' ? event.text : '';
    this.question = event.kind === 'ask_user' ? '' : event.text;
    this.error = '';
    setTimeout(() => this.questionInputRef?.nativeElement.focus({ preventScroll: true }));
  }

  onUse(): void {
    const sel = this.selected;
    if (!sel) return;
    this.error = '';
    this.useInPlan.emit(
      `Use the research about ${sel.request.target} to help me decide what to check next. Treat study hours as ` +
      `estimates, keep unresolved points clear, and do not assume the course fits my schedule or that I qualify ` +
      `for payments. Research reference: ${sel.id}`
    );
  }

  submit(event: Event): void {
    event.preventDefault();
    if (this.activeStream || this.disabled || this.loadingSaved) return;
    if (!this.question.trim() || !this.target.trim()) return;

    this.stage = 'searching';
    this.error = '';
    const submittedQuestion = this.replyTo
      ? `Replying to: ${this.replyTo}\nMy answer: ${this.question}`
      : this.question;
    const body: DetailRequest = {
      parentMessageId: this.parentMessageId,
      target: this.target,
      question: submittedQuestion,
      studyYear: this.studyYear,
      location: this.location,
      refresh: this.refresh,
    };

    this.activeStream = this.api.openStream(
      `/conversations/${encodeURIComponent(this.conversationId)}/details`,
      body,
      (data: DetailStreamEvent) => this.handleStreamEvent(data),
      () => this.handleStreamFailure('This search could not start. Please try again.')
    );
  }

  private handleStreamEvent(data: DetailStreamEvent): void {
    if (data.error) {
      this.handleStreamFailure(data.error);
      return;
    }
    if (data.done && data.details) {
      const result = data.details;
      this.results = [...this.results.filter(r => r.id !== result.id), result];
      this.selectedId = result.id;
      this.question = '';
      this.replyTo = '';
      this.editingScope = false;
      this.stage = '';
      this.activeStream = null;
      setTimeout(() => this.answerAnchorRef?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      return;
    }
    if (data.phase) {
      this.stage = data.phase;
    }
  }

  private handleStreamFailure(message: string): void {
    this.error = message;
    this.stage = '';
    this.activeStream = null;
  }

  stopSearch(): void {
    this.activeStream?.close();
    this.activeStream = null;
    this.stage = '';
    this.error = 'Search stopped. Your question has been kept.';
  }
}
