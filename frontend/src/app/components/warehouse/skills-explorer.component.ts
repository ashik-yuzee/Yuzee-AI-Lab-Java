import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ExplorationChoice, WarehouseExploration } from './warehouse.types';

const EMPTY_CHOICE: ExplorationChoice = { roleIds: [], skills: [] };

/**
 * Angular port of yuzee-ai-token-lab/src/warehouse/SkillsExplorer.tsx: lets a learner pick up to
 * three roles to focus on and mark skills as HAVE/LEARN/UNSURE, plus read-only sections for
 * linked learning, job-advert examples and observed-skill counts. Nested inside
 * WarehouseConnectionsComponent.
 *
 * Selector: app-skills-explorer
 * Inputs:  data: WarehouseExploration (required)
 *          saved: ExplorationChoice | null = null
 *          disabled = false
 * Outputs: save = EventEmitter<ExplorationChoice>()
 *          course = EventEmitter<string[]>()   (course ids to focus, from a "learning" link)
 */
@Component({
  selector: 'app-skills-explorer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './skills-explorer.component.html',
  styleUrl: './skills-explorer.component.scss',
})
export class SkillsExplorerComponent implements OnChanges {
  @Input({ required: true }) data!: WarehouseExploration;
  @Input() saved: ExplorationChoice | null = null;
  @Input() disabled = false;
  @Output() save = new EventEmitter<ExplorationChoice>();
  @Output() course = new EventEmitter<string[]>();

  choice = signal<ExplorationChoice>(EMPTY_CHOICE);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['saved'] || changes['data']) this.choice.set(this.saved ?? EMPTY_CHOICE);
  }

  selectedRoleIds(): string[] {
    return this.choice().roleIds.filter(id => this.data.roles.some(r => r.id === id));
  }

  visibleSkills() {
    const roles = this.selectedRoleIds();
    return roles.length ? this.data.skills.filter(s => s.roleIds.some(id => roles.includes(id))) : this.data.skills;
  }

  isDirty(): boolean {
    return JSON.stringify(this.choice()) !== JSON.stringify(this.saved ?? EMPTY_CHOICE);
  }

  isRoleSelected(id: string): boolean {
    return this.selectedRoleIds().includes(id);
  }

  roleCheckboxDisabled(id: string): boolean {
    const roles = this.selectedRoleIds();
    return this.disabled || (roles.length >= 3 && !roles.includes(id));
  }

  toggleRole(id: string, checked: boolean): void {
    const roles = this.selectedRoleIds();
    const nextRoleIds = checked ? [...roles, id] : roles.filter(x => x !== id);
    this.choice.update(c => ({ ...c, roleIds: nextRoleIds }));
  }

  skillStateFor(skillId: string): '' | 'HAVE' | 'LEARN' | 'UNSURE' {
    return this.choice().skills.find(s => s.id === skillId)?.state ?? '';
  }

  onSkillStateChange(skillId: string, skillName: string, value: string): void {
    const remaining = this.choice().skills.filter(s => s.id !== skillId);
    const next = value ? [...remaining, { id: skillId, name: skillName, state: value as 'HAVE' | 'LEARN' | 'UNSURE' }] : remaining;
    this.choice.update(c => ({ ...c, skills: next }));
  }

  commitSave(): void {
    const roles = this.selectedRoleIds();
    const skills = this.choice().skills.filter(s => this.data.skills.some(k => k.id === s.id));
    this.save.emit({ roleIds: roles, skills });
  }

  savedStatusText(): string {
    if (this.isDirty()) return 'Your changes are ready to save.';
    return this.saved ? 'Saved for this conversation. Oala will use these choices in your next answer.' : 'Your choices will guide the next answer.';
  }

  openCourse(id: string): void {
    this.course.emit([id]);
  }

  hasChoiceSection(): boolean {
    return this.data.roles.length > 0 || this.data.skills.length > 0;
  }

  jobRecordedDate(job: WarehouseExploration['jobs'][number]): string {
    const raw = job.postedAt?.slice(0, 10) || job.updatedAt?.slice(0, 10);
    return raw || 'date not supplied';
  }

  jobCompanyAndArea(job: WarehouseExploration['jobs'][number]): string {
    return [job.company, job.area].filter(Boolean).join(' · ');
  }

  roleSkillNames(role: WarehouseExploration['roles'][number]): string {
    return role.skills.map(s => s.name).join(' · ');
  }

  trackById(item: { id: string }): string { return item.id; }
  trackText(value: string): string { return value; }
}
