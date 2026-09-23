import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../shared/icon/icon.component';
import { EvidenceNoteComponent } from './result-value.component';
import { ObjectiveAnswer, componentType, validateObjectiveAnswer } from './objectives.types';

/** Port of the original's objectives/PrimitiveRenderer.tsx (PrimitiveRenderer). */
@Component({
  selector: 'app-primitive-renderer',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, EvidenceNoteComponent],
  styles: [':host{display:contents}'],
  templateUrl: './primitive-renderer.component.html',
})
export class PrimitiveRendererComponent implements OnInit {
  @Input({ required: true }) component: any;
  @Input({ required: true }) plan: any;
  @Input() disabled = false;
  @Input() hideKnown = false;
  @Output() answer = new EventEmitter<ObjectiveAnswer>();

  c: any = {};
  kind = '';
  value: any = '';
  error = '';

  ngOnInit(): void {
    const c = this.component || {};
    // The Java plan may omit empty arrays; the original always had them.
    this.c = { ...c, options: c.options || [], content: c.content || [], rows: c.rows || [], columns: c.columns || [], settings: c.settings || {} };
    this.kind = componentType(c);
    this.value = this.kind === 'ranking' ? this.c.options.map((o: any) => o.id) : this.kind === 'card_sort' ? {} : this.kind === 'multi_select' ? [] : '';
  }

  get required(): boolean { return !!this.c.required && this.kind !== 'action_handoff'; }
  get ordered(): boolean { return ['timeline', 'pathway_builder'].includes(this.kind); }
  get known(): any[] { return this.c.content.filter((n: any) => n.source_status === 'USER_CONFIRMED'); }
  get content(): any[] { return this.c.content.filter((n: any) => n.source_status !== 'USER_CONFIRMED'); }
  get showKnownDetails(): boolean { return !this.hideKnown && this.known.length > 0; }
  get onlyKnown(): boolean { return this.known.length > 0 && !this.required && this.kind === 'evidence_panel' && !this.content.length && !this.c.rows.length; }
  get choiceControl(): boolean { return ['single_choice', 'yes_no_unsure', 'multi_select'].includes(this.kind) || (this.kind === 'entity_picker' && this.c.options.length > 0); }
  get textLabel(): string {
    return this.kind === 'entity_picker' ? 'Name or link (we will still need to verify it)' : this.kind === 'profile_editor' ? 'Confirm or correct these details for this activity' : 'Your answer';
  }

  checked(id: string): boolean { return Array.isArray(this.value) ? this.value.includes(id) : this.value === id; }
  choose(id: string): void {
    this.value = this.kind === 'multi_select' ? (this.value.includes(id) ? this.value.filter((x: string) => x !== id) : [...this.value, id]) : id;
  }
  optionLabel(id: string): string { return this.c.options.find((o: any) => o.id === id)?.label; }
  entityText(): string { return this.c.options.some((o: any) => o.id === this.value) ? '' : this.value; }
  text(event: Event): void { this.value = (event.target as HTMLInputElement).value; }
  range(event: Event): void { this.value = Number((event.target as HTMLInputElement).value); }
  number(event: Event): void { const v = (event.target as HTMLInputElement).value; this.value = v === '' ? '' : Number(v); }
  sort(id: string, event: Event): void { this.value = { ...this.value, [id]: (event.target as HTMLSelectElement).value }; }

  reorder(index: number, delta: number): void {
    const a = [...this.value];
    [a[index], a[index + delta]] = [a[index + delta], a[index]];
    this.value = a;
  }

  submit(event: Event | null, unsure = false): void {
    event?.preventDefault();
    const a = { component_id: this.c.id, value: this.value, unsure };
    try { this.answer.emit(validateObjectiveAnswer(this.plan, a)); this.error = ''; } catch (e) { this.error = (e as Error).message; }
  }
}
