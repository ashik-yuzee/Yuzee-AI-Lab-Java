import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ResultValueComponent } from './result-value.component';
import {
  ObjectiveAnswer, ObjectiveNote, ObjectiveOption, ObjectivePlan, ObjectivePlanComponent,
  ObjectiveReadinessDimension, ObjectiveTableRow, componentType,
} from './objectives.types';

type CardSortValue = Record<string, string>;

/**
 * Angular port of the old React app's `src/objectives/PrimitiveRenderer.tsx`: dynamic dispatch
 * renderer for one entry of `plan.ui`. Renders either a required question control (matching the
 * primitive's `component`/`type` kind — see `componentType()`) or read-only supporting content
 * (evidence notes, tables, a hierarchy explorer, the readiness scorecard).
 *
 * A new component instance is created per question step: the parent (`ObjectiveWorkspaceComponent`)
 * tracks its `@for` loop by `sessionId:revision:componentId` (see its `trackPrimitive()`), so
 * Angular destroys and recreates this component whenever the session advances — exactly
 * replicating the old React app's `key={...}` reset trick for clearing per-question local state.
 *
 * Deliberate simplification vs. the old app: client-side answer validation (`validate()` below)
 * mirrors `ObjectiveService.java#validateObjectiveAnswer` only loosely (good-enough messages to
 * avoid an obviously-incomplete round trip); the backend remains the authoritative validator and
 * its error becomes `ObjectivesService.error()` if this check somehow passes something invalid.
 */
@Component({
  selector: 'app-primitive-renderer',
  standalone: true,
  imports: [CommonModule, FormsModule, ResultValueComponent],
  templateUrl: './primitive-renderer.component.html',
  styleUrl: './primitive-renderer.component.scss',
})
export class PrimitiveRendererComponent implements OnInit {
  @Input({ required: true }) component!: ObjectivePlanComponent;
  @Input({ required: true }) plan!: ObjectivePlan;
  @Input() disabled = false;
  @Input() hideKnown = false;

  @Output() answer = new EventEmitter<ObjectiveAnswer>();

  kind = '';
  required = false;

  choiceValue = '';
  multiValue: string[] = [];
  spectrumValue: number | '' = '';
  rankingValue: string[] = [];
  cardSortValue: CardSortValue = {};
  textValue = '';
  /** entity_picker's free-text fallback, kept separate from `choiceValue` (which holds a picked option id). */
  entityFreeText = '';

  errorMessage = '';

  ngOnInit(): void {
    this.kind = componentType(this.component);
    this.required = !!this.component.required && this.kind !== 'action_handoff';
    if (this.kind === 'ranking') this.rankingValue = (this.component.options ?? []).map(o => o.id);
  }

  // -- content / known details ------------------------------------------------

  private contentNotes(): ObjectiveNote[] {
    return Array.isArray(this.component.content) ? this.component.content : [];
  }

  knownNotes(): ObjectiveNote[] {
    return this.contentNotes().filter(n => n.source_status === 'USER_CONFIRMED');
  }

  otherNotes(): ObjectiveNote[] {
    return this.contentNotes().filter(n => n.source_status !== 'USER_CONFIRMED');
  }

  showKnownDetails(): boolean {
    return !this.hideKnown && this.knownNotes().length > 0;
  }

  /** timeline/pathway_builder primitives render their notes as a numbered sequence. */
  isOrdered(): boolean {
    return this.kind === 'timeline' || this.kind === 'pathway_builder';
  }

  hasTable(): boolean {
    return this.rows().length > 0;
  }

  /** `@for` sources — strictTemplates needs a definite (non-optional) array, so every optional
   *  array field on `component`/`plan.readiness` gets a `?? []` accessor here rather than being
   *  read directly in the template. */
  options(): ObjectiveOption[] {
    return this.component.options ?? [];
  }

  columns(): string[] {
    return this.component.columns ?? [];
  }

  rows(): ObjectiveTableRow[] {
    return this.component.rows ?? [];
  }

  buckets(): string[] {
    return this.component.settings?.buckets ?? [];
  }

  readinessDimensions(): ObjectiveReadinessDimension[] {
    return this.plan.readiness.dimensions ?? [];
  }

  /** Port of the old app's early-return: a non-required evidence_panel with only USER_CONFIRMED
   *  content and no table renders (at most) just the known-details block, nothing else. */
  evidenceOnlyCase(): boolean {
    return this.knownNotes().length > 0
      && !this.required
      && this.kind === 'evidence_panel'
      && this.otherNotes().length === 0
      && !this.hasTable();
  }

  // -- required controls --------------------------------------------------------

  isChoiceKind(): boolean {
    return ['single_choice', 'yes_no_unsure'].includes(this.kind)
      || (this.kind === 'entity_picker' && (this.component.options?.length ?? 0) > 0);
  }

  isMultiSelect(): boolean {
    return this.kind === 'multi_select';
  }

  isChecked(id: string): boolean {
    return this.isMultiSelect() ? this.multiValue.includes(id) : this.choiceValue === id;
  }

  selectChoice(id: string): void {
    if (this.isMultiSelect()) {
      this.multiValue = this.multiValue.includes(id) ? this.multiValue.filter(x => x !== id) : [...this.multiValue, id];
    } else {
      this.choiceValue = id;
    }
  }

  onSpectrumRangeInput(event: Event): void {
    const n = (event.target as HTMLInputElement).valueAsNumber;
    this.spectrumValue = Number.isFinite(n) ? n : '';
  }

  /** `[value]` on a native `<input>` binds to the DOM `.value` string property — stringify here
   *  rather than binding the raw `number | ''` model value directly. */
  spectrumRangeValue(): string {
    const v = this.spectrumValue === '' ? this.component.settings?.min : this.spectrumValue;
    return v === undefined ? '' : String(v);
  }

  reorder(index: number, delta: number): void {
    const next = [...this.rankingValue];
    const j = index + delta;
    if (j < 0 || j >= next.length) return;
    [next[index], next[j]] = [next[j], next[index]];
    this.rankingValue = next;
  }

  optionLabel(id: string): string {
    return this.component.options?.find(o => o.id === id)?.label ?? id;
  }

  setCardSort(optionId: string, bucket: string): void {
    this.cardSortValue = { ...this.cardSortValue, [optionId]: bucket };
  }

  cardSortOf(optionId: string): string {
    return this.cardSortValue[optionId] ?? '';
  }

  entityShowsFreeText(): boolean {
    return this.kind === 'entity_picker' && (this.component.options?.length ?? 0) > 0;
  }

  textLabel(): string {
    if (this.kind === 'entity_picker') return 'Name or link (we will still need to verify it)';
    if (this.kind === 'profile_editor') return 'Confirm or correct these details for this activity';
    return 'Your answer';
  }

  // -- submit ----------------------------------------------------------------------

  submit(unsure = false): void {
    this.errorMessage = '';
    let value: unknown = null;
    if (!unsure) {
      value = this.currentValue();
      const err = this.validate(value);
      if (err) {
        this.errorMessage = err;
        return;
      }
    }
    this.answer.emit({ component_id: this.component.id, value, unsure });
  }

  private currentValue(): unknown {
    switch (this.kind) {
      case 'single_choice':
      case 'yes_no_unsure':
        return this.choiceValue;
      case 'multi_select':
        return this.multiValue;
      case 'spectrum':
        return this.spectrumValue;
      case 'ranking':
        return this.rankingValue;
      case 'card_sort':
        return this.cardSortValue;
      case 'entity_picker':
        return this.entityFreeText.trim() ? this.entityFreeText.trim() : this.choiceValue;
      default:
        return this.textValue;
    }
  }

  private validate(value: unknown): string {
    const ids = (this.component.options ?? []).map(o => o.id);
    switch (this.kind) {
      case 'single_choice':
      case 'yes_no_unsure':
        return typeof value === 'string' && value ? '' : 'Choose one of the available options.';
      case 'multi_select':
        return Array.isArray(value) && value.length ? '' : 'Choose at least one option.';
      case 'ranking':
        return Array.isArray(value) && value.length === ids.length ? '' : 'Rank every option once.';
      case 'spectrum': {
        const min = this.component.settings?.min;
        const max = this.component.settings?.max;
        const ok = typeof value === 'number' && Number.isFinite(value)
          && (min === undefined || value >= min) && (max === undefined || value <= max);
        return ok ? '' : 'Choose a value within the range.';
      }
      case 'card_sort': {
        const v = (value ?? {}) as CardSortValue;
        return ids.length > 0 && ids.every(id => !!v[id]) ? '' : 'Place each card into a group.';
      }
      default:
        return typeof value === 'string' && value.trim() ? '' : 'Add an answer of up to 4,000 characters.';
    }
  }
}
