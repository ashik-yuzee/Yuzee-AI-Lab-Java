import { Component, Input } from '@angular/core';

const sourceLabels: Record<string, string> = { USER_CONFIRMED: 'You shared', AI_INFERRED: 'AI interpretation', SOURCED_CURRENT_FACT: 'Source-backed', UNKNOWN: 'Still unknown', GENERAL_GUIDANCE: 'General guidance' };

/** PrimitiveRenderer.tsx EvidenceNote. Host is `display:contents`, so the DOM matches the original. */
@Component({
  selector: 'app-evidence-note',
  standalone: true,
  styles: [':host{display:contents}'],
  template: `<div [class]="'objective-note ' + (note?.source_status?.toLowerCase() ?? 'undefined')">@if (note?.source_status !== 'USER_CONFIRMED') {<span>{{ label() }}</span>@if (note?.label) {<strong>{{ note.label }}</strong>}}<p>{{ note?.detail }}</p></div>`,
})
export class EvidenceNoteComponent {
  @Input({ required: true }) note: any;
  label(): string { return sourceLabels[this.note?.source_status] || 'Detail'; }
}

/** PrimitiveRenderer.tsx ResultValue (recursive). */
@Component({
  selector: 'app-result-value',
  standalone: true,
  imports: [EvidenceNoteComponent],
  styles: [':host{display:contents}'],
  templateUrl: './result-value.component.html',
})
export class ResultValueComponent {
  @Input() value: any;

  kind(): 'unknown' | 'scalar' | 'note' | 'list' | 'emptyList' | 'fields' {
    const v = this.value;
    if (v === null || v === undefined || v === '') return 'unknown';
    if (typeof v !== 'object') return 'scalar';
    if (v.source_status) return 'note';
    if (Array.isArray(v)) return v.length ? 'list' : 'emptyList';
    return 'fields';
  }

  scalar(): string {
    const v = this.value;
    return typeof v === 'string' && /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/.test(v) ? v.toLowerCase().replace(/_/g, ' ') : String(v);
  }

  fields(): [string, any][] {
    return Object.entries(this.value).filter(([k, v]) => !['comparison_scope', 'evidence_scope', 'evidence_refs'].includes(k) && v !== null && v !== '' && (!Array.isArray(v) || v.length > 0));
  }

  key(k: string): string { return k.replace(/_/g, ' '); }
}
