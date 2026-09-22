import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DetailFact, DetailResult, DetailSource, Evidence, NextQuestionKind } from './research-details.types';

const STATUS_LABEL: Record<DetailResult['status'], string> = {
  answered: 'Sources found',
  partial: 'Some details to confirm',
  needs_clarification: 'More context needed',
  no_evidence: 'Not confirmed yet',
};

const FACT_KIND_LABEL: Record<DetailFact['kind'], string> = {
  source_backed: 'From a source',
  inference: 'Our interpretation',
  benchmark: 'General career expectation',
};

/**
 * Port of yuzee-ai-token-lab/src/components/ResearchAnswerCard.tsx. Pure display of one saved
 * DetailResult: status, summary, gaps, cited facts/evidence, clarification follow-ups and
 * suggested next questions.
 *
 * Simplification vs the old app: `result.searchSuggestionsHtml` (a Google-supplied search-terms
 * widget, rendered there in a sandboxed iframe) is not rendered here — re-creating an equally
 * safe sandboxed srcdoc iframe wasn't worth the risk for a low-value attribution widget. A plain
 * fallback line is shown instead when it's present.
 */
@Component({
  selector: 'app-research-answer-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './research-answer-card.component.html',
  styleUrl: './research-answer-card.component.scss',
})
export class ResearchAnswerCardComponent {
  @Input({ required: true }) result!: DetailResult;
  @Input() disabled = false;

  @Output() askQuestion = new EventEmitter<{ text: string; kind: NextQuestionKind }>();
  @Output() use = new EventEmitter<void>();

  readonly statusLabels = STATUS_LABEL;
  readonly factKindLabels = FACT_KIND_LABEL;

  get suggested() {
    return this.result.nextQuestions.filter(q => q.kind === 'suggested_question');
  }

  get clarifications() {
    return this.result.nextQuestions.filter(q => q.kind === 'ask_user');
  }

  get statusLabel(): string {
    return this.statusLabels[this.result.status];
  }

  get titleText(): string {
    if (this.result.status === 'no_evidence') return 'Let’s narrow this down';
    if (this.result.status === 'needs_clarification') return 'A little more context will help';
    return 'Your answer';
  }

  get scopeNote(): string {
    return [
      this.result.request.studyYear ? `Study year ${this.result.request.studyYear}` : '',
      this.result.request.location,
    ].filter(Boolean).join(' · ');
  }

  factEvidence(fact: DetailFact): Evidence[] {
    return this.result.evidence.filter(e => fact.evidenceIds.includes(e.id));
  }

  factSources(fact: DetailFact): DetailSource[] {
    const evidence = this.factEvidence(fact);
    const sourceIds = new Set(evidence.flatMap(e => e.sourceIds));
    return this.result.sources.filter(s => sourceIds.has(s.id));
  }

  sourceNumber(sourceId: string): number {
    return this.result.sources.findIndex(s => s.id === sourceId) + 1;
  }

  onAskQuestion(text: string, kind: NextQuestionKind): void {
    if (this.disabled) return;
    this.askQuestion.emit({ text, kind });
  }

  onUse(): void {
    if (this.disabled) return;
    this.use.emit();
  }
}
