import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { YuzeeInteraction, YuzeeOption, UserEvent } from '../../../models/types';

/**
 * Port of ProtocolInteraction.tsx — the interactive question widget attached to a turn.
 *
 * `@Output() interact` fires a `UserEvent`-shaped payload the instant the user submits.
 * Because Angular's EventEmitter has no return value (unlike the React `onInteract`
 * callback, which returned a Promise<boolean>), the pending/submitted/error lifecycle is
 * completed by the parent calling the public `reportResult(accepted)` method once its own
 * async submission (e.g. TokenLabService.sendMessage) resolves — grab a template ref
 * (`#ix`) on `<app-interaction>` and call `ix.reportResult(true | false)`.
 */
@Component({
  selector: 'app-interaction',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './interaction.component.html',
  styleUrl: './interaction.component.scss'
})
export class InteractionComponent implements OnChanges {
  @Input({ required: true }) interaction!: YuzeeInteraction;
  @Input() initialFields: Record<string, string> = {};
  @Input() readOnly = false;
  @Output() interact = new EventEmitter<UserEvent>();

  @ViewChild('ownAnswer') ownAnswerRef?: ElementRef<HTMLTextAreaElement>;

  selected: string[] = [];
  ranked: string[] = [];
  answer = '';
  fields: Record<string, string> = {};
  fieldErrors: Record<string, string> = {};
  error = '';
  pending = false;
  submitted = false;
  ownOpen = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['interaction']) return;
    const q = this.interaction;
    this.selected = [];
    this.ranked = (q?.options ?? []).map(o => o.id);
    this.answer = '';
    this.fieldErrors = {};
    this.error = '';
    this.pending = false;
    this.submitted = false;
    this.ownOpen = false;
    const init = this.initialFields ?? {};
    this.fields = Object.fromEntries((q?.fields ?? []).filter(f => init[f.id] !== undefined).map(f => [f.id, init[f.id]]));
  }

  get disabled(): boolean {
    return this.readOnly || this.pending || this.submitted;
  }

  get hasAnswer(): boolean {
    const q = this.interaction;
    if (!q) return false;
    if (q.input_type === 'ranked_select') return this.ranked.length > 0;
    if (q.input_type === 'fields') return true;
    if (q.input_type === 'text') return !!this.answer.trim();
    return this.selected.length > 0 || (q.allow_other_input && !!this.answer.trim());
  }

  get hintText(): string {
    switch (this.interaction?.input_type) {
      case 'multi_select': return 'Choose all that fit.';
      case 'single_select': return 'Choose the next step that suits you.';
      case 'ranked_select': return 'Move what matters most to the top.';
      case 'fields': return 'Enter your details. Required fields are marked.';
      default: return 'A short answer is fine. You can also say "I’m not sure".';
    }
  }

  get footerText(): string {
    if (this.selected.length) return this.interaction.input_type === 'multi_select' ? `${this.selected.length} selected` : 'One option selected';
    if (this.answer.trim()) return 'Your own answer';
    return 'You can change direction at any time.';
  }

  get statusText(): string {
    if (this.submitted) return 'Answer sent.';
    if (this.pending) return 'Sending your answer…';
    return 'Earlier question · You can add or change details in your message below.';
  }

  label(id: string): string {
    return this.interaction?.options?.find(o => o.id === id)?.label ?? id;
  }

  onOptionToggle(o: YuzeeOption): void {
    if (this.interaction.input_type === 'single_select') {
      this.selected = [o.id];
      this.answer = '';
      this.ownOpen = false;
    } else {
      this.selected = this.selected.includes(o.id) ? this.selected.filter(id => id !== o.id) : [...this.selected, o.id];
    }
  }

  onAnswerInput(value: string): void {
    this.answer = value;
    if (this.interaction.input_type === 'single_select') this.selected = [];
  }

  move(index: number, delta: number): void {
    const target = index + delta;
    if (target < 0 || target >= this.ranked.length) return;
    const next = [...this.ranked];
    [next[index], next[target]] = [next[target], next[index]];
    this.ranked = next;
  }

  toggleOwn(): void {
    this.ownOpen = !this.ownOpen;
    if (this.ownOpen) setTimeout(() => this.ownAnswerRef?.nativeElement.focus());
  }

  submit(e: Event): void {
    e.preventDefault();
    if (this.disabled) return;
    this.error = '';
    const q = this.interaction;
    const inter: { question_id: string; selected_option_ids?: string[]; ranked_option_ids?: string[]; fields?: Record<string, string>; self_input?: string } = { question_id: q.question_id };
    let value: string;
    let type: UserEvent['type'];

    if (q.input_type === 'fields') {
      const check = this.validateFields();
      this.fieldErrors = check.fieldErrors;
      if (!check.valid) { this.error = 'Please check the highlighted fields.'; return; }
      inter.fields = Object.fromEntries(Object.entries(this.fields).map(([k, v]) => [k, (v ?? '').trim()]));
      value = q.fields.map(f => `${f.label}: ${f.options.find(o => o.value === this.fields[f.id])?.label || (this.fields[f.id] ?? '').trim() || 'Not provided'}`).join('\n');
      type = 'fields_submission';
    } else if (q.input_type === 'text') {
      if (!this.answer.trim()) { this.error = 'Please type your answer.'; return; }
      inter.self_input = this.answer.trim();
      value = this.answer.trim();
      type = 'text_answer';
    } else if (q.input_type === 'ranked_select') {
      inter.ranked_option_ids = this.ranked;
      value = this.ranked.map(id => this.label(id)).join(' → ');
      type = 'ranked_submission';
    } else {
      if (!this.selected.length && !this.answer.trim()) { this.error = 'Choose an option or write your own answer.'; return; }
      inter.selected_option_ids = this.selected;
      if (q.allow_other_input && this.answer.trim()) inter.self_input = this.answer.trim();
      value = [...this.selected.map(id => this.label(id)), this.answer.trim()].filter(Boolean).join(', ');
      type = 'text_answer';
    }

    this.pending = true;
    this.interact.emit({ type, interaction_id: q.question_id, value, userEvent: { interaction: inter }, timestamp: Date.now() });
  }

  /** Called by the parent once its own async submission (POST to the backend) settles. */
  reportResult(accepted: boolean): void {
    this.pending = false;
    if (accepted) this.submitted = true;
    else this.error = 'Your reply could not be sent. Your answer is still here. Please try again.';
  }

  private validateFields(): { valid: boolean; fieldErrors: Record<string, string> } {
    const fieldErrors: Record<string, string> = {};
    for (const field of this.interaction.fields) {
      const text = (this.fields[field.id] ?? '').trim();
      if (field.required && !text) {
        fieldErrors[field.id] = `Enter ${field.id === 'location' ? 'a city, suburb or postcode' : field.label.toLowerCase()}.`;
      } else if (text.length > 500) {
        fieldErrors[field.id] = 'Keep this answer under 500 characters.';
      } else if (text && field.input_type === 'single_select') {
        const matches = field.options.some(o => (o.value || o.label) === text);
        if (!matches) fieldErrors[field.id] = `Choose one of the listed options for ${field.label.toLowerCase()}.`;
      }
    }
    return { valid: Object.keys(fieldErrors).length === 0, fieldErrors };
  }
}
