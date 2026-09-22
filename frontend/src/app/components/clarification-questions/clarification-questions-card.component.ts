import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export type ClarificationUiType = 'single_select' | 'multi_select' | 'ranked_select' | 'free_text';

export interface ClarificationOption {
  value: string;
  label?: string;
  description?: string;
}

/** Matches POST /api/pre-check's `questions` shape: { questionId, question, options: string[] }.
 *  The optional fields below let this widget be reused for a richer question shape later
 *  (multi/ranked select, forced free text) without the backend having to change first. */
export interface ClarificationQuestion {
  questionId: string;
  question: string;
  options: (string | ClarificationOption)[];
  uiType?: ClarificationUiType;
  allowSelfInput?: boolean;
  selfInputLabel?: string;
  required?: boolean;
}

export interface ClarificationAnswer {
  questionId: string;
  answerText: string;
}

interface AnswerState {
  selected: string[];
  selfInput: string;
}

@Component({
  selector: 'app-clarification-questions-card',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './clarification-questions-card.component.html',
  styleUrl: './clarification-questions-card.component.scss'
})
export class ClarificationQuestionsCardComponent implements OnChanges {
  @Input() questions: ClarificationQuestion[] = [];
  @Input() bridgeMessage?: string;
  @Input() disabled = false;
  @Output() answered = new EventEmitter<ClarificationAnswer[]>();

  answers: Record<string, AnswerState> = {};
  rankedOrders: Record<string, string[]> = {};

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['questions']) {
      this.answers = {};
      this.rankedOrders = {};
      for (const q of this.questions) {
        this.answers[q.questionId] = { selected: [], selfInput: '' };
        if (this.uiTypeOf(q) === 'ranked_select') {
          this.rankedOrders[q.questionId] = this.normalizedOptions(q).map(o => o.value);
        }
      }
    }
  }

  uiTypeOf(q: ClarificationQuestion): ClarificationUiType {
    return q.uiType ?? 'single_select';
  }

  allowSelfInputFor(q: ClarificationQuestion): boolean {
    return q.allowSelfInput ?? true;
  }

  normalizedOptions(q: ClarificationQuestion): ClarificationOption[] {
    return (q.options || []).map(o => typeof o === 'string' ? { value: o, label: o } : o);
  }

  isSelected(q: ClarificationQuestion, value: string): boolean {
    return this.answers[q.questionId]?.selected.includes(value) ?? false;
  }

  toggleSelect(q: ClarificationQuestion, value: string): void {
    if (this.disabled) return;
    const multi = this.uiTypeOf(q) === 'multi_select';
    const state = this.answers[q.questionId];
    const cur = state.selected;
    state.selected = multi
      ? (cur.includes(value) ? cur.filter(v => v !== value) : [...cur, value])
      : [value];
    state.selfInput = '';
  }

  onSelfInputChange(q: ClarificationQuestion, value: string): void {
    const state = this.answers[q.questionId];
    state.selfInput = value;
    if (value) state.selected = [];
  }

  moveRanked(q: ClarificationQuestion, idx: number, dir: -1 | 1): void {
    if (this.disabled) return;
    const arr = [...(this.rankedOrders[q.questionId] ?? [])];
    const swapIdx = idx + dir;
    if (swapIdx < 0 || swapIdx >= arr.length) return;
    [arr[idx], arr[swapIdx]] = [arr[swapIdx], arr[idx]];
    this.rankedOrders[q.questionId] = arr;
  }

  optionLabel(q: ClarificationQuestion, value: string): string {
    return this.normalizedOptions(q).find(o => o.value === value)?.label ?? value;
  }

  hasAnswer(q: ClarificationQuestion): boolean {
    const state = this.answers[q.questionId];
    if (!state) return false;
    const ui = this.uiTypeOf(q);
    if (ui === 'free_text') return state.selfInput.trim().length > 0;
    if (ui === 'ranked_select') return true;
    return state.selected.length > 0 || state.selfInput.trim().length > 0;
  }

  get allRequiredAnswered(): boolean {
    return this.questions.filter(q => q.required !== false).every(q => this.hasAnswer(q));
  }

  submit(): void {
    const result: ClarificationAnswer[] = this.questions.map(q => ({
      questionId: q.questionId,
      answerText: this.answerTextFor(q)
    }));
    this.answered.emit(result);
  }

  private answerTextFor(q: ClarificationQuestion): string {
    const state = this.answers[q.questionId];
    if (!state) return '';
    const ui = this.uiTypeOf(q);
    if (ui === 'free_text') return state.selfInput.trim();
    if (ui === 'ranked_select') {
      const order = this.rankedOrders[q.questionId] ?? [];
      return order.map((v, i) => `${i + 1}. ${this.optionLabel(q, v)}`).join(', ');
    }
    if (state.selfInput.trim()) return state.selfInput.trim();
    return state.selected.map(v => this.optionLabel(q, v)).join(', ');
  }
}
