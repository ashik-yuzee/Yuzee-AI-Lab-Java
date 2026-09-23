import { Component, Input } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { WarehouseConnections } from './warehouse.types';

type Signal = WarehouseConnections['signals'][number];
const names: Record<string, string> = { SA2: 'Local area', SA3: 'District', SA4: 'Wider region', STATE: 'State', NATIONAL: 'Australia' };
const groupPurpose: Record<string, string> = { learning: 'Places to explore learning and training.', employment: 'Services that may help you explore work and next steps.', community: 'Places to try activities and discover what you enjoy.', support: 'Organisations connected with community support.', business: 'Business names in this directory; current hiring is not confirmed.' };
const careerName = (title: string) => /^\d+$/.test(title.trim()) ? 'Other occupation in the records' : title;

/** Port of the original's warehouse/LocalAreaGuide.tsx. */
@Component({
  selector: 'app-local-area-guide',
  standalone: true,
  imports: [IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './local-area-guide.component.html',
})
export class LocalAreaGuideComponent {
  @Input({ required: true }) data!: WarehouseConnections;
  names = names;
  groupPurpose = groupPurpose;

  get area() { return this.data.location.region; }
  get overview() { return this.data.localOverview; }
  get demand(): Signal[] { return this.data.signals.filter(s => s.kind === 'RECORDED_DEMAND'); }
  get outlook(): Signal[] { return this.data.signals.filter(s => s.kind === 'PROJECTION'); }
  get groups() { return this.overview?.community || []; }
  get namedCareers() { return this.data.careers.filter(c => !/^\d+$/.test(c.title)); }

  date(value: string | null): string {
    return !value ? 'Date not supplied' : /^\d{4}-\d{2}(?:-\d{2})?$/.test(value) ? new Date(value.length === 7 ? value + '-01T00:00:00' : value + 'T00:00:00').toLocaleDateString('en-AU', { month: 'short', year: 'numeric' }) : value;
  }
  number(n: number | null | undefined): string { return n == null ? 'Not supplied' : n.toLocaleString('en-AU'); }
  role(s: Signal): string { return careerName(this.data.careers.find(c => c.id === s.careerId)?.title || s.title.replace(/^(Recorded demand|Recorded vacancies|Employment outlook) for /, '')); }

  profileText(): string {
    const p = this.overview!.profile!, area = this.area!;
    return `${p.scope !== area.tier || p.area !== area.name ? `For the wider ${p.area} area` : `For ${area.name}`}${p.population != null ? `, the recorded population was ${this.number(p.population)} in ${p.period}` : ''}. ${p.setting ? `The regional profile describes it as ${p.setting.toLowerCase()}.` : ''}`;
  }
  examples(g: { examples: { name: string }[] }): string { return g.examples.slice(0, 2).map(o => o.name).join(' · '); }
  source(s: string): string { return s.replace(/_/g, ' '); }
  growth(s: Signal): string { const g = s.metrics!.growthPercent!; return `${Math.abs(g)}% ${g < 0 ? 'decrease' : g === 0 ? 'change' : 'growth'}`; }
  outlookPlace(s: Signal): string { return s.region === 'AUS' || s.region === 'ALL' ? 'Australia · National forecast' : `${s.region} · ${names[s.scope] || s.scope}`; }
}
