import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WarehouseComparison } from './warehouse.types';

const BASIS_LABEL: Record<string, string> = {
  COURSE_RECORD: 'Course record',
  PROVIDER_RECORD: 'Provider record',
  YUZEE_ANALYSIS: 'Yuzee analysis',
};

/**
 * Angular port of yuzee-ai-token-lab/src/warehouse/ProviderComparison.tsx: a course/provider
 * comparison table with a "show all details" toggle and shared/unknown-detail disclosures.
 * Nested inside WarehouseCoursesComponent (matching the old app's actual import graph — the
 * porting brief mentions it under WarehouseConnections, but only WarehouseCourses.tsx imports it
 * in the source; SkillsExplorer/LocalAreaGuide are the ones actually nested under connections).
 *
 * Selector: app-provider-comparison
 * Inputs:  data: WarehouseComparison, selectionIds: string[] | null = null, selectionDisabled = false
 * Outputs: toggleOption = EventEmitter<string>()  (emits the option/course id to toggle)
 */
@Component({
  selector: 'app-provider-comparison',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './provider-comparison.component.html',
  styleUrl: './provider-comparison.component.scss',
})
export class ProviderComparisonComponent {
  @Input({ required: true }) data!: WarehouseComparison;
  /** Present (non-null) only when the caller wants selectable option checkboxes — mirrors the
   *  old React component's optional `selection` prop, split into plain inputs/outputs. */
  @Input() selectionIds: string[] | null = null;
  @Input() selectionDisabled = false;
  @Output() toggleOption = new EventEmitter<string>();

  showAll = false;

  basisLabel(basis: string): string {
    return BASIS_LABEL[basis] ?? basis;
  }

  unitTypeLabel(type: string): string {
    return type.toLowerCase() === 'core' ? 'Core unit' : 'National elective option';
  }

  sharedRows() {
    return this.data.rows.filter(r => r.status === 'SHARED');
  }

  unknownRows() {
    return this.data.rows.filter(r => r.status === 'UNKNOWN');
  }

  unknownLabels(): string {
    return this.unknownRows().map(r => r.label).join(' · ');
  }

  visibleRows() {
    return this.data.rows.filter(r =>
      this.showAll || (!['SHARED', 'UNKNOWN'].includes(r.status) && !['strengths', 'limits'].includes(r.key))
    );
  }

  unmatchedProviderMatches() {
    return this.data.providerMatches.filter(m => m.status !== 'MATCHED');
  }

  hasQualificationUnits(): boolean {
    return this.data.qualifications.some(q => q.units.length > 0);
  }

  isSelected(id: string): boolean {
    return this.selectionIds?.includes(id) ?? false;
  }

  selectionCheckboxDisabled(id: string): boolean {
    if (!this.selectionIds) return false;
    return this.selectionDisabled || (this.selectionIds.length >= 3 && !this.selectionIds.includes(id));
  }

  toggle(id: string): void {
    this.toggleOption.emit(id);
  }

  trackByKey(item: { key: string }): string { return item.key; }
  trackByCode(item: { code: string }): string { return item.code; }
}
