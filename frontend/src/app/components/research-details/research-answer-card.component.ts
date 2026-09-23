import { Component, EventEmitter, Input, OnChanges, Output, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { IconComponent } from '../shared/icon/icon.component';
import { DetailFact, DetailResult, NextQuestionKind } from './research-details.types';

let nextId = 0;
const statusLabels: Record<DetailResult['status'], string> = { answered: 'Sources found', partial: 'Some details to confirm', needs_clarification: 'More context needed', no_evidence: 'Not confirmed yet' };
const kindLabels: Record<DetailFact['kind'], string> = { source_backed: 'From a source', inference: 'Our interpretation', benchmark: 'General career expectation' };

/** Port of the original's components/ResearchAnswerCard.tsx. */
@Component({
  selector: 'app-research-answer-card',
  standalone: true,
  imports: [IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './research-answer-card.component.html',
})
export class ResearchAnswerCardComponent implements OnChanges {
  @Input({ required: true }) result!: DetailResult;
  @Input() disabled = false;
  /** The original's async `onUse`; `using` stays true until it settles. */
  @Input({ required: true }) onUse!: () => Promise<void>;
  @Output() askQuestion = new EventEmitter<{ text: string; kind: NextQuestionKind }>();

  readonly id = `research-card-${nextId++}`;
  kindLabels = kindLabels;
  using = false;
  suggestionsDoc: SafeHtml | null = null;
  private sanitizer = inject(DomSanitizer);

  ngOnChanges(): void {
    // Rendered in a sandboxed iframe without scripts, exactly as the original (CSP meta + srcdoc).
    this.suggestionsDoc = this.result.searchSuggestionsHtml
      ? this.sanitizer.bypassSecurityTrustHtml(`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data:">${this.result.searchSuggestionsHtml}`)
      : null;
  }

  get suggested() { return this.result.nextQuestions.filter(q => q.kind === 'suggested_question'); }
  get clarifications() { return this.result.nextQuestions.filter(q => q.kind === 'ask_user'); }
  get status(): string { return statusLabels[this.result.status]; }
  get checked(): string { return new Date(this.result.retrievedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }); }
  get scopeNote(): string { return [this.result.request.studyYear && `Study year ${this.result.request.studyYear}`, this.result.request.location].filter(Boolean).join(' · '); }
  get title(): string { return this.result.status === 'no_evidence' ? 'Let’s narrow this down' : this.result.status === 'needs_clarification' ? 'A little more context will help' : 'Your answer'; }

  evidence(fact: DetailFact) { return this.result.evidence.filter(e => fact.evidenceIds.includes(e.id)); }
  sources(fact: DetailFact) { const ids = new Set(this.evidence(fact).flatMap(e => e.sourceIds)); return this.result.sources.filter(s => ids.has(s.id)); }
  sourceNumber(sourceId: string): number { return this.result.sources.findIndex(s => s.id === sourceId) + 1; }

  async use(): Promise<void> { this.using = true; try { await this.onUse(); } finally { this.using = false; } }
}
