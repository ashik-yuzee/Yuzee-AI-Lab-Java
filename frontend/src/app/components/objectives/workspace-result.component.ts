import { Component, Input } from '@angular/core';
import { EvidenceNoteComponent, ResultValueComponent } from './result-value.component';
import { comparisonRows } from './objectives.types';

export { comparisonRows } from './objectives.types';

/** Port of the original's objectives/WorkspaceResult.tsx. */
@Component({
  selector: 'app-workspace-result',
  standalone: true,
  imports: [EvidenceNoteComponent, ResultValueComponent],
  styles: [':host{display:contents}'],
  templateUrl: './workspace-result.component.html',
})
export class WorkspaceResultComponent {
  @Input() result: any;

  table() { return comparisonRows(this.result); }

  cell(job: any, label: string): any { return job.criteria?.find((c: any) => c.criterion === label); }

  conceptual(jobs: any[]): boolean { return jobs.some(j => j.criteria?.some((c: any) => c.evidence_scope === 'STABLE_CONCEPTUAL')); }

  private restFor: any; private restValue: any;
  /** Cached so the `[value]` binding stays referentially stable between change-detection passes. */
  rest(): any {
    if (this.restFor !== this.result || !this.restValue) {
      this.restFor = this.result;
      this.restValue = Object.fromEntries(Object.entries(this.result || {}).filter(([k]) => !['summary', 'next_actions', 'unknowns'].includes(k)));
    }
    return this.restValue;
  }
}
