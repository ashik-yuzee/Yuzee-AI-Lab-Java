import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';

export interface ClarificationOption {
  value: string;
  label: string;
  description?: string;
}

export interface ClarificationQuestion {
  id: string;
  dimension: string;
  priority: number;
  ui_type: 'single_select' | 'multi_select' | 'ranked_select' | 'free_text' | 'mixed';
  question: string;
  why_we_ask?: string;
  required?: boolean;
  min_select?: number;
  max_select?: number;
  options?: ClarificationOption[];
  allow_self_input?: boolean;
  self_input_label?: string;
}

export interface ClarificationAnswer {
  question_id: string;
  dimension: string;
  selected_values: string[];
  self_input: string;
}

/** Port of ClarificationQuestionsCard.tsx. */
@Component({
  selector: 'app-clarification-questions-card',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './clarification-questions-card.component.html',
  styleUrl: './clarification-questions-card.component.scss'
})
export class ClarificationQuestionsCardComponent implements OnChanges {
  @Input() questions: ClarificationQuestion[] = [];
  @Input() bridgeMessage?: string;
  @Input() disabled = false;
  /** React's `onSubmit`. */
  @Output() submitted = new EventEmitter<ClarificationAnswer[]>();

  qs: ClarificationQuestion[] = [];
  answers: Record<string, { selected: string[]; selfInput: string }> = {};
  rankedOrders: Record<string, string[]> = {};

  /** The questions are used as received (counsellor gate or /api/pre-check), like the original's useState initialisers. */
  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['questions']) return;
    this.qs = Array.isArray(this.questions) ? this.questions : [];
    this.answers = Object.fromEntries(this.qs.map(q => [q.id, { selected: [], selfInput: '' }]));
    this.rankedOrders = Object.fromEntries(this.qs.filter(q => q.ui_type === 'ranked_select').map(q => [q.id, (q.options || []).map(o => o.value)]));
  }

  uiLabel(q: ClarificationQuestion): string { return (q.ui_type ?? '').replace(/_/g, ' '); }
  isSelected(q: ClarificationQuestion, value: string): boolean { return this.answers[q.id].selected.includes(value); }
  option(q: ClarificationQuestion, value: string): ClarificationOption | undefined { return (q.options || []).find(o => o.value === value); }

  toggleSelect(q: ClarificationQuestion, value: string): void {
    if (this.disabled) return;
    const multi = q.ui_type === 'multi_select';
    const cur = this.answers[q.id].selected;
    const next = multi ? (cur.includes(value) ? cur.filter(v => v !== value) : [...cur, value]) : [value];
    this.answers = { ...this.answers, [q.id]: { ...this.answers[q.id], selected: next } };
  }

  setFreeText(q: ClarificationQuestion, value: string): void {
    this.answers = { ...this.answers, [q.id]: { ...this.answers[q.id], selfInput: value } };
  }

  setSelfInput(q: ClarificationQuestion, value: string): void {
    const prev = this.answers[q.id];
    this.answers = { ...this.answers, [q.id]: { ...prev, selfInput: value, selected: value ? [] : prev.selected } };
  }

  moveRanked(q: ClarificationQuestion, idx: number, dir: -1 | 1): void {
    const arr = [...(this.rankedOrders[q.id] ?? [])];
    const swapIdx = idx + dir;
    if (swapIdx < 0 || swapIdx >= arr.length) return;
    [arr[idx], arr[swapIdx]] = [arr[swapIdx], arr[idx]];
    this.rankedOrders = { ...this.rankedOrders, [q.id]: arr };
  }

  private hasAnswer(q: ClarificationQuestion): boolean {
    const a = this.answers[q.id];
    if (q.ui_type === 'free_text') return a.selfInput.trim().length > 0;
    if (q.ui_type === 'ranked_select') return true;
    return a.selected.length > 0 || a.selfInput.trim().length > 0;
  }

  get allRequired(): boolean { return this.qs.filter(q => q.required !== false).every(q => this.hasAnswer(q)); }

  handleSubmit(): void {
    this.submitted.emit(this.qs.map(q => {
      const a = this.answers[q.id];
      const selected = q.ui_type === 'ranked_select' ? this.rankedOrders[q.id] || [] : a.selected;
      return { question_id: q.id, dimension: q.dimension, selected_values: selected, self_input: a.selfInput };
    }));
  }
}
