import { Component, ElementRef, HostListener, ViewChild, computed, effect, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../shared/icon/icon.component';
import { WarehouseConnectionsComponent } from '../warehouse/warehouse-connections.component';
import { WarehouseCoursesComponent } from '../warehouse/warehouse-courses.component';
import { ObjectivesService } from '../../services/objectives.service';
import { AnswerHistoryComponent } from './objective-history.component';
import { ContextDetailsComponent } from './context-details.component';
import { PrimitiveRendererComponent } from './primitive-renderer.component';
import { WorkspaceResultComponent } from './workspace-result.component';
import { activityTitle, comparisonRows, componentType, resultReady, stateLabel } from './objectives.types';

const NO_IDS: string[] = [];
const WIDTH_KEY = 'yuzee-objective-workspace-width', DOCK_BREAKPOINT = 1000;

/** Port of the original's objectives/ObjectiveWorkspace.tsx (with miniPathway/useDrawerResize.ts inlined). */
@Component({
  selector: 'app-objective-workspace',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, WarehouseConnectionsComponent, WarehouseCoursesComponent, AnswerHistoryComponent, ContextDetailsComponent, PrimitiveRendererComponent, WorkspaceResultComponent],
  styles: [':host{display:contents}'],
  templateUrl: './objective-workspace.component.html',
})
export class ObjectiveWorkspaceComponent {
  @ViewChild('heading') heading?: ElementRef<HTMLHeadingElement>;

  expanded = signal(false);
  search = signal('');
  goal = signal('');
  activityTitle = activityTitle;
  stateLabel = stateLabel;
  comparisonRows = comparisonRows;
  resultReady = resultReady;

  // ---- useDrawerResize(o.open, expanded, WIDTH_KEY, 1000) ----
  private viewport = signal(window.innerWidth);
  private preferred = signal<number | null>(this.readWidth());
  private drag: { id: number; x: number; width: number } | null = null;
  maximum = computed(() => Math.max(1, this.viewport() >= DOCK_BREAKPOINT ? this.viewport() - 360 : this.viewport()));
  minimum = computed(() => Math.min(320, this.maximum()));
  width = computed(() => this.clamp(this.preferred() ?? (this.viewport() >= 1600 ? 490 : this.viewport() < DOCK_BREAKPOINT ? 480 : 440)));

  visible = computed(() => this.o.enabled() && this.o.open());
  rows = computed(() => this.o.catalogue().filter(x => `${x.button_label} ${x.topic_name} ${x.primary_category} ${x.tool_id}`.toLowerCase().includes(this.search().toLowerCase())));

  constructor(public o: ObjectivesService) {
    effect(() => { if (this.visible()) setTimeout(() => this.heading?.nativeElement.focus()); });
    effect(() => { this.visible(); this.expanded(); this.viewport(); this.drag = null; });
    this.setPreferred(this.preferred()); // useDrawerResize's storage effect also runs on mount (removes an invalid saved width)
  }

  private readWidth(): number | null {
    try { const value = Number(localStorage.getItem(WIDTH_KEY)); return Number.isFinite(value) && value >= 320 ? value : null; } catch { return null; }
  }
  private clamp(value: number): number { return Math.round(Math.max(this.minimum(), Math.min(this.maximum(), value))); }
  private setPreferred(value: number | null): void {
    this.preferred.set(value);
    try { if (value === null) localStorage.removeItem(WIDTH_KEY); else localStorage.setItem(WIDTH_KEY, String(value)); } catch { /* Resizing still works if browser storage is unavailable. */ }
  }

  @HostListener('window:resize') onResize(): void { this.viewport.set(window.innerWidth); }

  @HostListener('window:keydown', ['$event']) onEscape(e: KeyboardEvent): void {
    if (!this.visible() || e.key !== 'Escape') return;
    if (this.expanded()) this.expanded.set(false); else this.close();
  }

  onPointerDown(e: PointerEvent): void {
    if (e.button !== 0 || this.expanded()) return;
    const el = e.currentTarget as HTMLElement;
    e.preventDefault(); el.focus(); el.setPointerCapture(e.pointerId);
    this.drag = { id: e.pointerId, x: e.clientX, width: this.width() };
  }
  onPointerMove(e: PointerEvent): void {
    if (this.drag?.id !== e.pointerId) return;
    this.setPreferred(this.clamp(this.drag.width + this.drag.x - e.clientX));
  }
  finish(e: PointerEvent): void {
    if (this.drag?.id !== e.pointerId) return;
    this.drag = null;
    const el = e.currentTarget as HTMLElement;
    if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
  }
  onResizeKey(e: KeyboardEvent): void {
    const step = e.shiftKey ? 80 : 20, width = this.width();
    const next = e.key === 'ArrowLeft' ? width + step : e.key === 'ArrowRight' ? width - step : e.key === 'Home' ? this.minimum() : e.key === 'End' ? this.maximum() : null;
    if (next === null) return;
    e.preventDefault(); this.setPreferred(this.clamp(next));
  }
  resetWidth(): void { this.setPreferred(null); }
  lostCapture(): void { this.drag = null; }

  close(): void { this.o.setOpen(false); }

  selectSession(event: Event): void { this.o.setSelectedId((event.target as HTMLSelectElement).value); this.o.setCatalogueOpen(false); }

  // ---- derived session state ----
  get s() { return this.o.selected(); }
  get p(): any { return this.s?.plan; }
  get warehouse(): any { return this.s?.context?.warehouse_data; }
  get warehouseComparison(): boolean { return this.s?.objectiveId === 'STUDY_004' && !!this.warehouse?.comparison; }
  get localDiscovery(): boolean { return this.s?.objectiveId === 'DISCOVER_001' && this.warehouse?.status === 'READY' && !!this.warehouse.connected?.location?.region; }
  get warehouseDisabled(): boolean { const s = this.s; return this.o.busy() || !!s?.pendingAnswer || !!s?.pendingCorrection || s?.state === 'CANCELLED'; }
  get savedExploration(): any { return this.s?.pendingCorrection?.explorationChoices || this.s?.context?.exploration_choices; }
  get selectedCourseIds(): string[] { return this.s?.context?.selected_course_ids || NO_IDS; }
  private ui(required: boolean): any[] { return (this.p?.ui || []).filter((c: any) => !!c.required === required && componentType(c) !== 'action_handoff'); }
  get optional(): any[] { return this.ui(false); }
  get requiredUi(): any[] { return this.ui(true); }
  get pending(): boolean { return this.requiredUi.length > 0; }
  get derivedSignals(): any[] { return this.p?.derived_signals || []; }
  get unknowns(): string[] { return this.p?.unknowns || []; }
  get blockers(): string[] { return this.p?.readiness?.blockers || []; }
  get interestThemes(): any[] { return this.p?.result?.interest_themes || []; }

  /** React keyed each renderer by `${session}:${revision}:${component}` to reset its local answer state. */
  primitiveKey(c: any): string { return `${this.s?.id}:${this.s?.revision}:${c.id}`; }
}
