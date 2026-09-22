import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WarehouseConnections } from './warehouse.types';

const SCOPE_NAME: Record<string, string> = { SA2: 'Local area', SA3: 'District', SA4: 'Wider region', STATE: 'State', NATIONAL: 'Australia' };
const GROUP_PURPOSE: Record<string, string> = {
  learning: 'Places to explore learning and training.',
  employment: 'Services that may help you explore work and next steps.',
  community: 'Places to try activities and discover what you enjoy.',
  support: 'Organisations connected with community support.',
  business: 'Business names in this directory; current hiring is not confirmed.',
};
const NUMERIC_TITLE = /^\d+$/;
const YEAR_MONTH = /^\d{4}-\d{2}(?:-\d{2})?$/;

/**
 * Angular port of yuzee-ai-token-lab/src/warehouse/LocalAreaGuide.tsx: a read-only regional guide
 * (community places, recorded job demand, outlook projections) rendered once a location has been
 * resolved to a region. Nested inside WarehouseConnectionsComponent when `localFirst` is set and
 * a region is available; renders nothing itself when `data.location.region` is null (an
 * unresolved/ambiguous area is not a guide to fabricate).
 *
 * Selector: app-local-area-guide
 * Inputs:  data: WarehouseConnections (required) — the `pack.connected` object, not the pack itself
 */
@Component({
  selector: 'app-local-area-guide',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './local-area-guide.component.html',
  styleUrl: './local-area-guide.component.scss',
})
export class LocalAreaGuideComponent {
  @Input({ required: true }) data!: WarehouseConnections;

  readonly groupPurpose = GROUP_PURPOSE;

  get region() {
    return this.data.location.region;
  }

  get overview() {
    return this.data.localOverview;
  }

  groups() {
    return this.overview?.community ?? [];
  }

  demandSignals() {
    return this.data.signals.filter(s => s.kind === 'RECORDED_DEMAND');
  }

  outlookSignals() {
    return this.data.signals.filter(s => s.kind === 'PROJECTION');
  }

  namedCareers() {
    return this.data.careers.filter(c => !NUMERIC_TITLE.test(c.title));
  }

  careerName(title: string): string {
    return NUMERIC_TITLE.test(title.trim()) ? 'Other occupation in the records' : title;
  }

  roleLabel(signal: WarehouseConnections['signals'][number]): string {
    const career = this.data.careers.find(c => c.id === signal.careerId);
    return this.careerName(career?.title || signal.title.replace(/^(Recorded demand|Recorded vacancies|Employment outlook) for /, ''));
  }

  scopeName(scope: string): string {
    return SCOPE_NAME[scope] ?? scope;
  }

  date(value: string | null | undefined): string {
    if (!value) return 'Date not supplied';
    if (!YEAR_MONTH.test(value)) return value;
    const iso = value.length === 7 ? value + '-01T00:00:00' : value + 'T00:00:00';
    return new Date(iso).toLocaleDateString('en-AU', { month: 'short', year: 'numeric' });
  }

  formatNumber(n: number | null | undefined): string {
    return n == null ? 'Not supplied' : n.toLocaleString('en-AU');
  }

  /** True when the signal carries enough metrics for `outlookHeadline`/`outlookDetail`; caller
   *  falls back to the signal's own free-text `text` otherwise. */
  hasOutlookMetrics(signal: WarehouseConnections['signals'][number]): boolean {
    return signal.metrics?.growthPercent != null && signal.metrics?.horizonYears != null;
  }

  /** Bold headline part, e.g. "12% growth". */
  outlookHeadline(signal: WarehouseConnections['signals'][number]): string {
    const growthPercent = signal.metrics!.growthPercent!;
    const direction = growthPercent < 0 ? 'decrease' : growthPercent === 0 ? 'change' : 'growth';
    return `${Math.abs(growthPercent)}% ${direction}`;
  }

  /** Trailing detail part, e.g. " projected over 5 years from 2023." */
  outlookDetail(signal: WarehouseConnections['signals'][number]): string {
    const horizonYears = signal.metrics!.horizonYears!;
    const from = signal.metrics?.baseYear ? ` from ${signal.metrics.baseYear}` : '';
    return `projected over ${horizonYears} years${from}.`;
  }

  signalRegionLabel(signal: WarehouseConnections['signals'][number]): string {
    return signal.region === 'AUS' || signal.region === 'ALL'
      ? 'Australia · National forecast'
      : `${signal.region} · ${this.scopeName(signal.scope)}`;
  }

  allDatedSignals() {
    return [...this.demandSignals(), ...this.outlookSignals()];
  }

  groupPreview(group: NonNullable<WarehouseConnections['localOverview']>['community'][number]): string {
    return group.examples.slice(0, 2).map(o => o.name).join(' · ');
  }

  sourceLabel(source: string): string {
    return source.replace(/_/g, ' ');
  }

  trackById(item: { id: string }): string { return item.id; }
  trackByKey(item: { key: string }): string { return item.key; }
  trackText(value: string): string { return value; }
}
