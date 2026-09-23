import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../shared/icon/icon.component';
import { ProviderComparisonComponent } from './provider-comparison.component';
import { WarehousePack } from './warehouse.types';

/** Port of the original's warehouse/WarehouseCourses.tsx (CourseDetails is an ng-template). */
@Component({
  selector: 'app-warehouse-courses',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, ProviderComparisonComponent],
  styles: [':host{display:contents}'],
  templateUrl: './warehouse-courses.component.html',
})
export class WarehouseCoursesComponent implements OnChanges {
  @Input() pack: WarehousePack | null | undefined;
  @Input() selectedIds: string[] = [];
  @Input() disabled = false;
  @Output() focusCourses = new EventEmitter<string[]>();

  selected: string[] = [];
  private key = '';

  // useEffect([course ids, selectedIds]) — re-sync the local selection with the saved one.
  ngOnChanges(): void {
    const key = `${this.pack?.courses?.map(c => c.id).join(',') || ''}|${(this.selectedIds || []).join(',')}`;
    if (key === this.key) return;
    this.key = key;
    this.selected = (this.selectedIds || []).filter(id => this.pack?.courses.some(c => c.id === id));
  }

  display(value: string | null): string { return value || 'Not supplied'; }

  toggle(id: string): void {
    const previous = this.selected;
    this.selected = previous.includes(id) ? previous.filter(x => x !== id) : previous.length < 3 ? [...previous, id] : previous;
  }

  get saved(): boolean {
    const ids = this.selectedIds || [];
    return this.selected.length > 0 && this.selected.length === ids.length && this.selected.every(x => ids.includes(x));
  }
}
