import { Component, EventEmitter, Input, Output } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { WarehouseComparison } from './warehouse.types';

const basis: Record<string, string> = { COURSE_RECORD: 'Course record', PROVIDER_RECORD: 'Provider record', YUZEE_ANALYSIS: 'Yuzee analysis' };

/** Port of the original's warehouse/ProviderComparison.tsx. `selection` is split into selectionIds/selectionDisabled/toggleOption. */
@Component({
  selector: 'app-provider-comparison',
  standalone: true,
  imports: [IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './provider-comparison.component.html',
})
export class ProviderComparisonComponent {
  @Input({ required: true }) data!: WarehouseComparison;
  /** null = no selection column (the original's `selection` prop omitted). */
  @Input() selectionIds: string[] | null = null;
  @Input() selectionDisabled = false;
  @Output() toggleOption = new EventEmitter<string>();

  all = false;
  basis = basis;

  get shared() { return this.data.rows.filter(r => r.status === 'SHARED'); }
  get unknown() { return this.data.rows.filter(r => r.status === 'UNKNOWN'); }
  get rows() { return this.data.rows.filter(r => this.all || !['SHARED', 'UNKNOWN'].includes(r.status) && !['strengths', 'limits'].includes(r.key)); }
  get unmatched() { return this.data.providerMatches.filter(m => m.status !== 'MATCHED'); }
  get hasUnits(): boolean { return this.data.qualifications.some(q => q.units.length); }
  get unknownLabels(): string { return this.unknown.map(r => r.label).join(' · '); }

  checkAll(event: Event): void { this.all = (event.target as HTMLInputElement).checked; }
}
