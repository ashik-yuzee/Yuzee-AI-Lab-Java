import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { ProviderComparisonComponent } from './provider-comparison.component';
import { WarehouseCourse, WarehousePack, WarehouseStatus } from './warehouse.types';

/**
 * Angular port of yuzee-ai-token-lab/src/warehouse/WarehouseCourses.tsx: a course
 * comparison/selection table (or the nested ProviderComparison snippet when the pack already
 * carries one) plus per-course expandable detail ("Learning, outcomes and quality"), letting the
 * caller "focus" up to three selected courses for the next conversation turn.
 *
 * Renders only ever from real `pack` data — when `pack` is null/undefined (no
 * `context.warehouse_data` on the session yet) this calls GET /api/warehouse/status itself and
 * shows an honest message instead of fabricating placeholder courses.
 *
 * Selector: app-warehouse-courses
 * Inputs:  pack: WarehousePack | null | undefined
 *          selectedIds: string[] = []
 *          disabled = false
 * Outputs: focus = EventEmitter<string[]>()   (ids of the courses the learner chose to explore)
 */
@Component({
  selector: 'app-warehouse-courses',
  standalone: true,
  imports: [CommonModule, ProviderComparisonComponent],
  templateUrl: './warehouse-courses.component.html',
  styleUrl: './warehouse-courses.component.scss',
})
export class WarehouseCoursesComponent implements OnInit, OnChanges {
  @Input() pack: WarehousePack | null | undefined;
  @Input() selectedIds: string[] = [];
  @Input() disabled = false;
  @Output() focus = new EventEmitter<string[]>();

  /** Selection state — mirrors the old component's local `useState(selectedIds)`, resynced
   *  whenever the pack's course ids or the caller's `selectedIds` change. */
  selected = signal<string[]>([]);
  /** Populated only when no `pack` was supplied at all; see class doc comment. */
  status = signal<WarehouseStatus | null>(null);

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    if (!this.pack) this.loadStatus();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['pack'] || changes['selectedIds']) {
      const validIds = this.pack?.courses.map(c => c.id) ?? [];
      this.selected.set(this.selectedIds.filter(id => validIds.includes(id)));
    }
    if (changes['pack'] && !this.pack) this.loadStatus();
  }

  private loadStatus(): void {
    this.api.get<WarehouseStatus>('/warehouse/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set(null),
    });
  }

  statusMessage(): string {
    const st = this.status();
    if (!st) return 'Connected course data is not available yet.';
    return st.status === 'READY'
      ? 'Course data is connected, but nothing has been retrieved for this conversation yet.'
      : 'Connected course data is not available yet.';
  }

  toggle(id: string): void {
    this.selected.update(prev =>
      prev.includes(id) ? prev.filter(x => x !== id) : prev.length < 3 ? [...prev, id] : prev
    );
  }

  isSelected(id: string): boolean {
    return this.selected().includes(id);
  }

  checkboxDisabled(id: string): boolean {
    return this.disabled || (!this.isSelected(id) && this.selected().length >= 3);
  }

  get saved(): boolean {
    const sel = this.selected();
    return sel.length > 0 && sel.length === this.selectedIds.length && sel.every(x => this.selectedIds.includes(x));
  }

  submit(): void {
    this.focus.emit(this.selected());
  }

  display(value: string | null): string {
    return value || 'Not supplied';
  }

  locationsPreview(course: WarehouseCourse): string {
    const shown = course.locations.slice(0, 3).join(' · ');
    return course.locations.length > 3 ? `${shown} · more locations in catalogue` : shown;
  }

  trackCourse(course: WarehouseCourse): string { return course.id; }
  trackText(value: string): string { return value; }
}
