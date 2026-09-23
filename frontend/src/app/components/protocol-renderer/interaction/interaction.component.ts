import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { IconComponent } from '../../shared/icon/icon.component';
import { validateInteractionFields } from '../protocol-validator';
import type { YuzeeInteraction, UserEvent, YuzeeOption } from '../../../models/types';

/** Mirrors React's `onInteract` prop: resolves to `false` when the reply was not accepted. */
export type InteractHandler = (event: UserEvent) => Promise<boolean | void> | boolean | void;

let nextScope = 0;

/**
 * Port of ProtocolInteraction.tsx — one explicit submission, with drafts preserved until the
 * reply is accepted.
 *
 * Submission goes to `interactHandler` when given (awaited exactly like React's `onInteract`).
 * Otherwise `(interact)` fires and the widget stays "Sending…" until the parent calls
 * `reportResult(accepted)`.
 */
@Component({
  selector: 'app-interaction',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './interaction.component.html',
  styleUrl: './interaction.component.scss'
})
export class InteractionComponent implements OnChanges {
  @Input({ required: true }) interaction!: YuzeeInteraction;
  @Input() initialFields: Record<string, string> = {};
  @Input() readOnly = false;
  @Input() interactHandler?: InteractHandler;
  /** React's `!onInteract`: set by a wrapper that forwards `(interact)` only when its own parent listens. */
  @Input() canInteract?: boolean;
  @Output() interact = new EventEmitter<UserEvent>();

  @ViewChild('answerRef') answerRef?: ElementRef<HTMLTextAreaElement>;

  readonly scope = 'pi' + (++nextScope);
  selected: string[] = [];
  ranked: string[] = [];
  answer = '';
  fields: Record<string, string> = {};
  fieldErrors: Record<string, string> = {};
  error = '';
  pending = false;
  submitted = false;
  ownOpen = false;

  private questionKey?: string;
  private resolveReport?: (accepted: boolean) => void;

  ngOnChanges(changes: SimpleChanges): void {
    // React keyed this component by question_id: a new question resets all local state.
    if (!changes['interaction'] || this.interaction?.question_id === this.questionKey && this.questionKey !== undefined) return;
    const q = this.interaction;
    this.questionKey = q?.question_id;
    this.selected = [];
    this.ranked = this.options.map(o => o.id);
    this.answer = '';
    const init = this.initialFields ?? {};
    this.fields = Object.fromEntries(this.fieldList.filter(f => init[f.id] !== undefined).map(f => [f.id, init[f.id]]));
    this.fieldErrors = {};
    this.error = '';
    this.pending = false;
    this.submitted = false;
    this.ownOpen = false;
  }

  get options(): YuzeeOption[] { return Array.isArray(this.interaction?.options) ? this.interaction.options : []; }
  get fieldList() { return Array.isArray(this.interaction?.fields) ? this.interaction.fields : []; }
  get active(): boolean { return !!this.interaction && this.interaction.kind !== 'none' && this.interaction.input_type !== 'none'; }
  get isChoice(): boolean { return ['single_select', 'multi_select'].includes(this.interaction.input_type); }

  get disabled(): boolean {
    return !!this.readOnly || this.pending || this.submitted || !(this.canInteract ?? (!!this.interactHandler || this.interact.observed));
  }

  get hasAnswer(): boolean {
    const t = this.interaction.input_type;
    return t === 'ranked_select' ? this.ranked.length > 0 : t === 'fields' ? true : t === 'text' ? !!this.answer.trim() : this.selected.length > 0 || (this.interaction.allow_other_input && !!this.answer.trim());
  }

  get statusText(): string {
    return this.submitted ? 'Answer sent.' : this.pending ? 'Sending your answer…' : 'Earlier question · You can add or change details in your message below.';
  }

  get hintText(): string {
    const t = this.interaction.input_type;
    return t === 'multi_select' ? 'Choose all that fit.' : t === 'single_select' ? 'Choose the next step that suits you.' : t === 'ranked_select' ? 'Move what matters most to the top.' : t === 'fields' ? 'Enter your details. Required fields are marked.' : 'A short answer is fine. You can also say “I’m not sure”.';
  }

  get footerText(): string {
    return this.selected.length ? (this.interaction.input_type === 'multi_select' ? `${this.selected.length} selected` : 'One option selected') : this.answer.trim() ? 'Your own answer' : 'You can change direction at any time.';
  }

  label(id: string): string { return this.options.find(o => o.id === id)?.label || id; }

  toggle(o: YuzeeOption): void {
    const single = this.interaction.input_type === 'single_select';
    this.selected = single ? [o.id] : this.selected.includes(o.id) ? this.selected.filter(id => id !== o.id) : [...this.selected, o.id];
    if (single) { this.answer = ''; this.ownOpen = false; }
  }

  toggleOwn(): void {
    this.ownOpen = !this.ownOpen;
    if (this.ownOpen) setTimeout(() => this.answerRef?.nativeElement.focus());
  }

  onAnswer(value: string): void {
    this.answer = value;
    if (this.interaction.input_type === 'single_select') this.selected = [];
  }

  setField(id: string, value: string): void { this.fields = { ...this.fields, [id]: value }; }

  move(index: number, delta: number): void {
    const next = [...this.ranked];
    const target = index + delta;
    [next[index], next[target]] = [next[target], next[index]];
    this.ranked = next;
  }

  optionValue(o: YuzeeOption): string { return o.value || o.label; }

  /** Parent-driven completion for the `(interact)` output path. */
  reportResult(accepted: boolean): void {
    this.resolveReport?.(accepted);
    this.resolveReport = undefined;
  }

  async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.disabled) return;
    const q = this.interaction;
    this.error = '';
    const inter: any = { question_id: q.question_id };
    let value = '';
    if (q.input_type === 'fields') {
      const check = validateInteractionFields(q, this.fields);
      this.fieldErrors = check.fieldErrors;
      if (!check.valid) { this.error = 'Please check the highlighted fields.'; return; }
      inter.fields = Object.fromEntries(Object.entries(this.fields).map(([k, v]) => [k, v.trim()]));
      value = this.fieldList.map(f => `${f.label}: ${f.options.find(o => o.value === this.fields[f.id])?.label || this.fields[f.id]?.trim() || 'Not provided'}`).join('\n');
    } else if (q.input_type === 'text') {
      if (!this.answer.trim()) { this.error = 'Please type your answer.'; return; }
      inter.self_input = this.answer.trim(); value = this.answer.trim();
    } else if (q.input_type === 'ranked_select') {
      inter.ranked_option_ids = this.ranked; value = this.ranked.map(id => this.label(id)).join(' → ');
    } else {
      if (!this.selected.length && !this.answer.trim()) { this.error = 'Choose an option or write your own answer.'; return; }
      inter.selected_option_ids = this.selected;
      if (q.allow_other_input && this.answer.trim()) inter.self_input = this.answer.trim();
      value = [...this.selected.map(id => this.label(id)), this.answer.trim()].filter(Boolean).join(', ');
    }
    const event = {
      type: q.input_type === 'ranked_select' ? 'ranked_submission' : q.input_type === 'fields' ? 'fields_submission' : 'text_answer',
      interaction_id: q.question_id, value, userEvent: { interaction: inter }, timestamp: Date.now()
    } as UserEvent;
    this.pending = true;
    try {
      const accepted = await this.dispatch(event);
      if (accepted === false) this.error = 'Your reply could not be completed. Your answer is still here. Please try again.';
      else this.submitted = true;
    } catch { this.error = 'Your reply could not be sent. Your answer is still here. Please try again.'; }
    finally { this.pending = false; }
  }

  private dispatch(event: UserEvent): Promise<boolean | void> {
    if (this.interactHandler) return Promise.resolve(this.interactHandler(event));
    const result = new Promise<boolean>(resolve => { this.resolveReport = resolve; });
    this.interact.emit(event);
    return result;
  }
}
