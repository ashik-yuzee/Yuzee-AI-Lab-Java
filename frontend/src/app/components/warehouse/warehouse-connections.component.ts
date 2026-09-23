import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../shared/icon/icon.component';
import { LocalAreaGuideComponent } from './local-area-guide.component';
import { SkillsExplorerComponent } from './skills-explorer.component';
import { ExplorationChoice, WarehousePack } from './warehouse.types';

const scopeLabel: Record<string, string> = { SA2: 'Neighbourhood area', SA3: 'District', SA4: 'Wider region', STATE: 'State', NATIONAL: 'National' };
const readable = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^./, x => x.toUpperCase());
const funding: [string, string][] = [['Undergraduate supported places', 'cspUndergraduate'], ['Postgraduate supported places', 'cspPostgraduate'], ['HECS-HELP', 'hecsHelp'], ['FEE-HELP', 'feeHelp']];

/** Port of the original's warehouse/WarehouseConnections.tsx. */
@Component({
  selector: 'app-warehouse-connections',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, LocalAreaGuideComponent, SkillsExplorerComponent],
  styles: [':host{display:contents}'],
  templateUrl: './warehouse-connections.component.html',
})
export class WarehouseConnectionsComponent implements OnChanges {
  @Input() pack: WarehousePack | null | undefined;
  @Input() savedExploration: ExplorationChoice | null | undefined = null;
  @Input() disabled = false;
  @Input() localFirst = false;
  @Output() areaChange = new EventEmitter<string>();
  /** The original renders SkillsExplorer only when onSaveExploration and onCourse are supplied: mirrored via `.observed`. */
  @Output() saveExploration = new EventEmitter<ExplorationChoice>();
  @Output() course = new EventEmitter<string[]>();

  area = '';
  editing = false;
  scopeLabel = scopeLabel;
  readable = readable;
  private requested: string | undefined;

  // useEffect([connected?.location.requested])
  ngOnChanges(): void {
    const requested = this.pack?.connected?.location.requested;
    if (requested === this.requested) return;
    this.requested = requested;
    this.area = requested || ''; this.editing = false;
  }

  get connected() { return this.pack?.connected; }
  get show(): boolean {
    const c = this.connected;
    return this.pack?.status === 'READY' && !!c && !!(c.localOverview || c.exploration || (['careers', 'providers', 'industries', 'signals'] as const).some(k => c[k]?.length));
  }
  get explorer(): boolean { return !!this.connected?.exploration && this.saveExploration.observed && this.course.observed; }
  get namedIndustry(): string[] { return [...new Set((this.connected?.industries || []).map(x => x.name))]; }
  get region() { return this.connected?.location.region ?? null; }

  get areaTitle(): string {
    const region = this.region, location = this.connected!.location;
    return region ? `${region.name}${region.state && region.name !== region.state ? `, ${region.state}` : ''}` : location.requested ? 'Choose the area you mean' : 'Make this local to you';
  }
  get areaNote(): string {
    const region = this.region, location = this.connected!.location;
    return region ? `${scopeLabel[region.tier] || 'Area'} · Wider signals are labelled below.` : location.candidates.length ? 'More than one area matches. Add a state or postcode.' : location.requested ? 'That area has not been matched yet. Try a suburb and state, or postcode.' : 'Add your suburb and state or postcode to explore regional information.';
  }

  submitArea(event: Event): void {
    event.preventDefault();
    if (this.area.trim()) this.areaChange.emit(`Use ${this.area.trim()} as my area for these course, career and local options. This replaces my previous area for this conversation.`);
  }
  setArea(event: Event): void { this.area = (event.target as HTMLInputElement).value; }

  join(values: string[]): string { return values.join(' · '); }
  links(c: { courseLinks: { courseName: string }[] }): string { return c.courseLinks.map(l => l.courseName).join(' · '); }
  campus(c: { name: string; town: string; state: string; postcode: string }): string { return [c.name, c.town, c.state, c.postcode].filter(Boolean).join(', '); }
  fundingLabels(p: any): string[] { return funding.filter(([, key]) => p.higherEducationFunding[key] === true).map(([label]) => label); }
}
