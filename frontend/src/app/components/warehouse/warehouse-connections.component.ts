import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { LocalAreaGuideComponent } from './local-area-guide.component';
import { SkillsExplorerComponent } from './skills-explorer.component';
import { ExplorationChoice, WarehouseConnections, WarehousePack, WarehouseStatus } from './warehouse.types';

const SCOPE_LABEL: Record<string, string> = { SA2: 'Neighbourhood area', SA3: 'District', SA4: 'Wider region', STATE: 'State', NATIONAL: 'National' };
const FUNDING_FLAGS: Array<[label: string, key: string]> = [
  ['Undergraduate supported places', 'cspUndergraduate'],
  ['Postgraduate supported places', 'cspPostgraduate'],
  ['HECS-HELP', 'hecsHelp'],
  ['FEE-HELP', 'feeHelp'],
];

/**
 * Angular port of yuzee-ai-token-lab/src/warehouse/WarehouseConnections.tsx: the "how the options
 * connect" panel — area/location picker, the nested skills/role explorer, and (when not
 * `localFirst`) read-only sections for connected careers, industries, regional signals and
 * providers. Nests SkillsExplorerComponent and LocalAreaGuideComponent, matching the old app's
 * actual imports (ProviderComparison is nested under WarehouseCoursesComponent instead — see that
 * component's doc comment).
 *
 * Renders nothing when `pack` has data but there's simply nothing connected yet (matches the old
 * component returning null); when `pack` is missing altogether it calls GET /api/warehouse/status
 * itself and shows an honest message rather than fabricating provider/career data.
 *
 * Selector: app-warehouse-connections
 * Inputs:  pack: WarehousePack | null | undefined
 *          savedExploration: ExplorationChoice | null = null
 *          disabled = false
 *          localFirst = false
 * Outputs: areaChange = EventEmitter<string>()          (a full correction message for the chat)
 *          saveExploration = EventEmitter<ExplorationChoice>()
 *          course = EventEmitter<string[]>()
 */
@Component({
  selector: 'app-warehouse-connections',
  standalone: true,
  imports: [CommonModule, FormsModule, LocalAreaGuideComponent, SkillsExplorerComponent],
  templateUrl: './warehouse-connections.component.html',
  styleUrl: './warehouse-connections.component.scss',
})
export class WarehouseConnectionsComponent implements OnInit, OnChanges {
  @Input() pack: WarehousePack | null | undefined;
  @Input() savedExploration: ExplorationChoice | null = null;
  @Input() disabled = false;
  @Input() localFirst = false;
  @Output() areaChange = new EventEmitter<string>();
  @Output() saveExploration = new EventEmitter<ExplorationChoice>();
  @Output() course = new EventEmitter<string[]>();

  area = signal('');
  editing = signal(false);
  status = signal<WarehouseStatus | null>(null);

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    if (!this.pack) this.loadStatus();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['pack']) {
      this.area.set(this.pack?.connected?.location.requested ?? '');
      this.editing.set(false);
      if (!this.pack) this.loadStatus();
    }
  }

  private loadStatus(): void {
    this.api.get<WarehouseStatus>('/warehouse/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set(null),
    });
  }

  statusMessage(): string {
    const st = this.status();
    if (!st) return 'Connected career and local information is not available yet.';
    return st.status === 'READY'
      ? 'Connected data is available, but nothing has been retrieved for this conversation yet.'
      : 'Connected career and local information is not available yet.';
  }

  /** Mirrors the old component's early-return guard: something must actually be connected. */
  hasContent(connected: WarehouseConnections): boolean {
    return !!connected.localOverview || !!connected.exploration
      || connected.careers.length > 0 || connected.providers.length > 0
      || connected.industries.length > 0 || connected.signals.length > 0;
  }

  regionHeading(connected: WarehouseConnections): string {
    const region = connected.location.region;
    if (region) return region.state && region.name !== region.state ? `${region.name}, ${region.state}` : region.name;
    return connected.location.requested ? 'Choose the area you mean' : 'Make this local to you';
  }

  areaSubtext(connected: WarehouseConnections): string {
    const region = connected.location.region;
    if (region) return `${SCOPE_LABEL[region.tier] ?? 'Area'} · Wider signals are labelled below.`;
    if (connected.location.candidates.length) return 'More than one area matches. Add a state or postcode.';
    return connected.location.requested ? 'That area has not been matched yet. Try a suburb and state, or postcode.' : 'Add your suburb and state or postcode to explore regional information.';
  }

  toggleEditing(): void {
    this.editing.update(v => !v);
  }

  submitArea(): void {
    const trimmed = this.area().trim();
    if (!trimmed) return;
    this.areaChange.emit(`Use ${trimmed} as my area for these course, career and local options. This replaces my previous area for this conversation.`);
  }

  namedIndustries(connected: WarehouseConnections): string[] {
    return [...new Set(connected.industries.map(x => x.name))];
  }

  scopeLabel(scope: string): string {
    return SCOPE_LABEL[scope] ?? this.readable(scope);
  }

  readable(s: string): string {
    const spaced = s.replace(/_/g, ' ').toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  showCareersSection(connected: WarehouseConnections): boolean {
    return !this.localFirst && connected.careers.length > 0 && !(connected.exploration?.roles.length ?? 0);
  }

  fundingLabels(higherEducationFunding: any): string[] {
    return FUNDING_FLAGS.filter(([, key]) => higherEducationFunding?.[key] === true).map(([label]) => label);
  }

  fundingPeriodNote(higherEducationFunding: any): string {
    let note = 'Provider arrangements do not determine your personal eligibility.';
    if (higherEducationFunding?.from) note += ` Record effective from ${higherEducationFunding.from}.`;
    if (higherEducationFunding?.to) note += ` Effective to ${higherEducationFunding.to}.`;
    return note;
  }

  fundingRowSummary(f: any): string {
    const status = this.readable(f.funding_status || 'Funding details not supplied');
    const tuition = f.student_tuition_out_of_pocket != null ? ` · Recorded tuition: ${f.currency || ''} ${f.student_tuition_out_of_pocket}` : '';
    return `${status} · ${f.delivery_state} · ${f.effective_period}${tuition}`;
  }

  campusLine(campus: { name: string; town: string; state: string; postcode: string }): string {
    return [campus.name, campus.town, campus.state, campus.postcode].filter(Boolean).join(', ');
  }

  courseLinkNames(career: WarehouseConnections['careers'][number]): string {
    return career.courseLinks.map(l => l.courseName).join(' · ');
  }

  trackById(item: { id: string }): string { return item.id; }
  trackText(value: string): string { return value; }
  trackCampus(campus: { name: string; town: string; state: string; postcode: string }): string { return this.campusLine(campus); }
}
