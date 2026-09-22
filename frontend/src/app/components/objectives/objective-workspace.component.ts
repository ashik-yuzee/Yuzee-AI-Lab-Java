import {
  Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, Output,
  SimpleChanges, ViewChild, signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ObjectivesService } from '../../services/objectives.service';
import { DrawerResizeController } from '../mini-pathway/drawer-resize';
import { AnswerHistoryComponent } from './objective-history.component';
import { ContextDetailsComponent } from './context-details.component';
import { PrimitiveRendererComponent } from './primitive-renderer.component';
import { WorkspaceResultComponent, comparisonRows } from './workspace-result.component';
import {
  ObjectiveAnswer, ObjectiveCatalogueItem, ObjectiveDerivedSignal, ObjectiveHandoffResult,
  ObjectivePlan, ObjectivePlanComponent, ObjectiveSession, STATE_LABEL, componentType,
} from './objectives.types';

/**
 * Angular port of the old React app's `src/objectives/ObjectiveWorkspace.tsx` — the main
 * resizable side panel: browse the objective catalogue, run the active session's question/answer
 * loop, review its result, correct it, and close it. Follows this codebase's established
 * resizable-tool-panel shape (`MiniPathwayExperienceComponent`): `@Input() open`,
 * `@Input() conversationId`, `@Output() closed`.
 *
 * <p><b>Deliberate simplifications vs. the old React workspace</b> (beyond what
 * `ObjectivesService`/`ObjectiveService.java` already document):
 * <ul>
 *   <li><b>No warehouse/course-catalogue sub-panels.</b> The old `<WarehouseCourses>`/
 *       `<WarehouseConnections>` (course comparison + local/career connections) only ever render
 *       when `context.warehouse_data` is present, and this backend port never populates it
 *       (`ObjectiveService.java` class javadoc) — so there is nothing to wire up yet. The spot
 *       where they'd go is marked with a TODO(integration) comment in the template for the
 *       engineer building `app-warehouse-courses`/`app-warehouse-connections` in parallel.</li>
 *   <li><b>No "goal" text box when starting an objective.</b> The old app let you type a focus
 *       goal before opening an objective; `ChatController.java`'s `start` operation only accepts
 *       `{toolId}` — there is no field for it to flow into, so the box was dropped rather than
 *       built as a no-op.</li>
 *   <li><b>`action_handoff`-typed UI components are never rendered</b> — this matches the old
 *       app's actual behaviour, not a new cut: `ObjectiveWorkspace.tsx` excludes
 *       `component==='action_handoff'` from both its required-question and general-content loops
 *       (its options only bind to `next_actions`, which the app renders elsewhere, per
 *       `WIRE_INSTRUCTION`'s own comment) — ported as the same `componentType(c)!=='action_handoff'`
 *       filters here.</li>
 *   <li><b>"Use this result"</b> calls the `handoff` operation and emits the compact payload via
 *       `(handoffReady)` rather than sending it into the chat itself — there is no chat-message API
 *       to call from here without touching `token-lab.service.ts`/`chat-area.component.*`, which
 *       are out of scope for this port. Whoever wires the two together can subscribe to this
 *       output.</li>
 * </ul>
 */
@Component({
  selector: 'app-objective-workspace',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    AnswerHistoryComponent,
    ContextDetailsComponent,
    PrimitiveRendererComponent,
    WorkspaceResultComponent,
  ],
  templateUrl: './objective-workspace.component.html',
  styleUrl: './objective-workspace.component.scss',
})
export class ObjectiveWorkspaceComponent implements OnChanges {
  @Input() open = false;
  @Input() conversationId: string | null = null;

  @Output() closed = new EventEmitter<void>();
  /** The compact handoff payload once "Use this result" is pressed — see class doc comment. */
  @Output() handoffReady = new EventEmitter<ObjectiveHandoffResult>();

  @ViewChild('heading') headingRef?: ElementRef<HTMLElement>;

  readonly resize = new DrawerResizeController('yuzee-objectives-width');
  readonly stateLabel = STATE_LABEL;

  expanded = signal(false);
  catalogueOpen = signal(true);
  search = '';

  constructor(public objectives: ObjectivesService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open']?.currentValue && this.conversationId) {
      void this.objectives.loadCatalogue();
      void this.objectives.loadSessions(this.conversationId);
      setTimeout(() => this.headingRef?.nativeElement.focus());
    }
    if (changes['conversationId'] && !changes['conversationId'].firstChange) {
      this.objectives.reset();
      this.catalogueOpen.set(true);
      this.expanded.set(false);
      this.search = '';
      if (this.open && this.conversationId) {
        void this.objectives.loadCatalogue();
        void this.objectives.loadSessions(this.conversationId);
      }
    }
  }

  @HostListener('window:keydown', ['$event'])
  onWindowKeydown(event: KeyboardEvent): void {
    if (!this.open || event.key !== 'Escape') return;
    if (this.expanded()) this.expanded.set(false);
    else this.close();
  }

  // -- header / nav -----------------------------------------------------------

  toggleExpanded(): void {
    this.expanded.set(!this.expanded());
  }

  close(): void {
    this.closed.emit();
  }

  openCatalogue(): void {
    this.catalogueOpen.set(!this.catalogueOpen());
  }

  selectSession(id: string): void {
    this.objectives.select(id);
    this.catalogueOpen.set(false);
  }

  activityTitle(s: ObjectiveSession | null): string {
    if (!s) return 'Your activity';
    const jobs = (s.plan?.result as any)?.job_comparison?.jobs;
    const names = Array.isArray(jobs)
      ? jobs.map((j: any) => j.occupation_or_vacancy).filter((n: any) => typeof n === 'string' && n.trim())
      : [];
    return names.length >= 2 ? names.slice(0, 3).join(' vs ') : s.label || 'Your activity';
  }

  // -- catalogue ----------------------------------------------------------------

  filteredCatalogue(): ObjectiveCatalogueItem[] {
    const q = this.search.trim().toLowerCase();
    const items = this.objectives.catalogue();
    if (!q) return items;
    return items.filter(o => `${o.button_label} ${o.topic_name} ${o.primary_category} ${o.tool_id}`.toLowerCase().includes(q));
  }

  async startObjective(toolId: string): Promise<void> {
    await this.objectives.start(toolId);
    this.catalogueOpen.set(false);
  }

  // -- active session content ----------------------------------------------------

  plan(): ObjectivePlan | null {
    return this.objectives.selected()?.plan ?? null;
  }

  isComparisonResult(result: any): boolean {
    return !!comparisonRows(result);
  }

  nonRequiredComponents(p: ObjectivePlan): ObjectivePlanComponent[] {
    return (p.ui ?? []).filter(c => !c.required && componentType(c) !== 'action_handoff');
  }

  hasNonRequiredComponents(p: ObjectivePlan): boolean {
    return this.nonRequiredComponents(p).length > 0;
  }

  requiredComponents(p: ObjectivePlan): ObjectivePlanComponent[] {
    return (p.ui ?? []).filter(c => c.required && componentType(c) !== 'action_handoff');
  }

  pending(p: ObjectivePlan): boolean {
    return this.requiredComponents(p).length > 0;
  }

  /** `@for` sources — strictTemplates needs a definite array, so these optional plan fields get a
   *  `?? []` accessor here rather than being read directly in the template. */
  derivedSignals(p: ObjectivePlan): ObjectiveDerivedSignal[] {
    return p.derived_signals ?? [];
  }

  readinessBlockers(p: ObjectivePlan): string[] {
    return p.readiness.blockers ?? [];
  }

  /** Composite track key: forces Angular to destroy/recreate the primitive-renderer instance
   *  whenever the session advances to a new revision, resetting its local answer-draft state —
   *  see `PrimitiveRendererComponent`'s own doc comment. */
  trackPrimitive(c: ObjectivePlanComponent): string {
    const s = this.objectives.selected();
    return `${s?.id}:${s?.revision}:${c.id}`;
  }

  onAnswer(answer: ObjectiveAnswer): void {
    void this.objectives.answer(answer);
  }

  isResultReady(s: ObjectiveSession): boolean {
    const p = s.plan;
    if (s.state === 'CANCELLED' || s.pendingAnswer || s.pendingCorrection || !p) return false;
    if (this.pending(p)) return false;
    const blockers = p.readiness?.blockers?.length ?? 0;
    return p.status === 'COMPLETE' || p.status === 'NEEDS_RESEARCH' || (p.status === 'NEEDS_INPUT' && blockers > 0);
  }

  async useResult(): Promise<void> {
    const result = await this.objectives.handoff();
    if (result) this.handoffReady.emit(result);
  }
}
