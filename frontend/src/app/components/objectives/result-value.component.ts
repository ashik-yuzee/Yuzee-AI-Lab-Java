import { Component, Input, forwardRef } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Angular port of the old React app's `PrimitiveRenderer.tsx` `ResultValue`/`EvidenceNote`
 * components: a small recursive renderer for the model's arbitrary, untyped `result`/`content`
 * JSON (objects become `<dt>`/`<dd>` pairs, arrays become lists, a `{source_status,...}` object
 * renders as an evidence note, everything else is a scalar).
 *
 * Split out as its own standalone component (rather than a template fragment) because Angular
 * needs a real component to recurse into itself from a template; it self-imports via `forwardRef`
 * for that recursion. Reused by both `WorkspaceResultComponent` (the domain result) and
 * `PrimitiveRendererComponent` (each primitive's `content` evidence notes).
 */
@Component({
  selector: 'app-result-value',
  standalone: true,
  imports: [CommonModule, forwardRef(() => ResultValueComponent)],
  templateUrl: './result-value.component.html',
  styleUrl: './result-value.component.scss',
})
export class ResultValueComponent {
  @Input() value: any;

  private readonly sourceLabels: Record<string, string> = {
    USER_CONFIRMED: 'You shared',
    AI_INFERRED: 'AI interpretation',
    SOURCED_CURRENT_FACT: 'Source-backed',
    UNKNOWN: 'Still unknown',
    GENERAL_GUIDANCE: 'General guidance',
  };

  private static readonly SHOUTY_ENUM = /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$/;
  private static readonly HIDDEN_KEYS = new Set(['comparison_scope', 'evidence_scope', 'evidence_refs']);

  isEmpty(v: any): boolean {
    return v === null || v === undefined || v === '';
  }

  isEvidenceNote(v: any): boolean {
    return !!v && typeof v === 'object' && !Array.isArray(v) && !!v.source_status;
  }

  isArray(v: any): boolean {
    return Array.isArray(v);
  }

  isObject(v: any): boolean {
    return !!v && typeof v === 'object' && !Array.isArray(v) && !v.source_status;
  }

  scalarText(v: any): string {
    if (typeof v !== 'string') return String(v);
    return ResultValueComponent.SHOUTY_ENUM.test(v) ? v.toLowerCase().replace(/_/g, ' ') : v;
  }

  sourceLabel(status: string | undefined): string {
    return (status && this.sourceLabels[status]) || 'Detail';
  }

  fieldLabel(key: string): string {
    return key.replace(/_/g, ' ');
  }

  entries(v: object): { k: string; v: any }[] {
    return Object.entries(v)
      .filter(([k, val]) => this.showEntry(k, val))
      .map(([k, val]) => ({ k, v: val }));
  }

  private showEntry(key: string, val: any): boolean {
    if (ResultValueComponent.HIDDEN_KEYS.has(key)) return false;
    if (val === null || val === '') return false;
    if (Array.isArray(val) && val.length === 0) return false;
    return true;
  }
}
