import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ResultValueComponent } from './result-value.component';

export interface JobComparisonTable {
  jobs: any[];
  labels: string[];
}

/**
 * Port of `service.ts`'s ... no — of `src/objectives/WorkspaceResult.tsx`'s `comparisonRows()`
 * helper. Exported as a plain function (not a class method) so `ObjectiveWorkspaceComponent` can
 * import and reuse the exact same check, mirroring how the old React app imported it directly
 * from `WorkspaceResult.tsx`.
 */
export function comparisonRows(result: any): JobComparisonTable | null {
  const jobs = result?.job_comparison?.jobs;
  if (!Array.isArray(jobs) || jobs.length < 2) return null;
  const labelSet = new Set<string>();
  for (const j of jobs) {
    for (const c of j?.criteria ?? []) {
      if (typeof c?.criterion === 'string' && c.criterion.trim()) labelSet.add(c.criterion);
    }
  }
  const labels = [...labelSet];
  return labels.length ? { jobs, labels } : null;
}

/**
 * Port of `src/objectives/WorkspaceResult.tsx`'s `WorkspaceResult` component: renders a
 * completed/in-progress objective's `plan.result` — either as an aligned job-comparison table
 * (when the result contains 2+ `job_comparison.jobs`) or, for every other objective shape, a
 * generic recursive rendering of the result object via `<app-result-value>`.
 */
@Component({
  selector: 'app-workspace-result',
  standalone: true,
  imports: [CommonModule, ResultValueComponent],
  templateUrl: './workspace-result.component.html',
  styleUrl: './workspace-result.component.scss',
})
export class WorkspaceResultComponent {
  @Input() result: any;

  table(): JobComparisonTable | null {
    return comparisonRows(this.result);
  }

  criterionFor(job: any, label: string): any {
    return (job?.criteria ?? []).find((c: any) => c.criterion === label) ?? null;
  }

  hasStableConceptual(jobs: any[]): boolean {
    return jobs.some(j => (j?.criteria ?? []).some((c: any) => c.evidence_scope === 'STABLE_CONCEPTUAL'));
  }

  fallbackResult(): Record<string, any> {
    return Object.fromEntries(
      Object.entries(this.result || {}).filter(([k]) => !['summary', 'next_actions', 'unknowns'].includes(k))
    );
  }
}
