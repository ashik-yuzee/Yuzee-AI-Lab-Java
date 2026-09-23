import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { ExplorationChoice, WarehouseExploration } from './warehouse.types';

const empty: ExplorationChoice = { roleIds: [], skills: [] };

/** Port of the original's warehouse/SkillsExplorer.tsx. */
@Component({
  selector: 'app-skills-explorer',
  standalone: true,
  imports: [IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './skills-explorer.component.html',
})
export class SkillsExplorerComponent implements OnChanges {
  @Input({ required: true }) data!: WarehouseExploration;
  @Input() saved: ExplorationChoice | null | undefined = null;
  @Input() disabled = false;
  @Output() save = new EventEmitter<ExplorationChoice>();
  @Output() course = new EventEmitter<string[]>();

  choice: ExplorationChoice = empty;

  // useEffect([saved, data])
  ngOnChanges(changes: SimpleChanges): void { if (changes['saved'] || changes['data']) this.choice = this.saved || empty; }

  get roles(): string[] { return this.choice.roleIds.filter(id => this.data.roles.some(r => r.id === id)); }
  get visibleSkills() { const roles = this.roles; return roles.length ? this.data.skills.filter(s => s.roleIds.some(id => roles.includes(id))) : this.data.skills; }
  get dirty(): boolean { return JSON.stringify(this.choice) !== JSON.stringify(this.saved || empty); }

  toggleRole(id: string, event: Event): void {
    const roles = this.roles;
    this.choice = { ...this.choice, roleIds: (event.target as HTMLInputElement).checked ? [...roles, id] : roles.filter(x => x !== id) };
  }

  skillState(id: string): string { return this.choice.skills.find(x => x.id === id)?.state || ''; }

  setSkill(s: { id: string; name: string }, event: Event): void {
    const value = (event.target as HTMLSelectElement).value as 'HAVE' | 'LEARN' | 'UNSURE' | '';
    this.choice = { ...this.choice, skills: [...this.choice.skills.filter(x => x.id !== s.id), ...(value ? [{ id: s.id, name: s.name, state: value }] : [])] };
  }

  submit(): void { this.save.emit({ roleIds: this.roles, skills: this.choice.skills.filter(s => this.data.skills.some(k => k.id === s.id)) }); }

  join(values: string[]): string { return values.join(' · '); }
  names(values: { name: string }[]): string { return values.map(s => s.name).join(' · '); }
  place(j: { company: string; area: string }): string { return [j.company, j.area].filter(Boolean).join(' · '); }
}
