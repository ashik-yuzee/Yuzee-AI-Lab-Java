import { Component, EventEmitter, Input, Output, SecurityContext, ViewChild } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { NgTemplateOutlet } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { IconComponent } from '../shared/icon/icon.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog/confirm-dialog.component';
import { PathwayLearningCuesComponent } from '../mini-pathway/pathway-learning-cues.component';
import { InteractionComponent, InteractHandler } from './interaction/interaction.component';
import { GuidanceListComponent } from './guidance-list/guidance-list.component';
import { TRUSTED_SERVICE_ACTIONS, TrustedServiceAction } from './protocol-validator';
import { learningToneIn, markdownToHtml, readableMarkdown } from './protocol-presentation';
import type { YuzeeResponseV13, YuzeeContentBlock, ServiceAction, UserEvent, ActionExecuteResponse } from '../../models/types';

const CALLOUT_ICON: Record<string, string> = {
  info: 'Info', default: 'Info', success: 'CheckCircle2', warning: 'AlertTriangle', danger: 'XCircle', muted: 'AlertCircle',
};
const CARD_STATUS = ['recommended', 'alternative', 'completed', 'current', 'blocked', 'warning', 'neutral', 'upcoming'];
const MILESTONE_STATUS = ['completed', 'current', 'upcoming', 'blocked', 'paused', 'unknown'];
const NODE_STATUS = ['recommended', 'current', 'completed', 'blocked', 'neutral', 'upcoming'];
const LANE_STATUS = ['completed', 'current', 'upcoming', 'blocked'];
const METRIC_STATUS = ['excellent', 'good', 'warning', 'critical', 'neutral'];
const STAGE_STATUS = ['completed', 'current', 'upcoming', 'blocked', 'paused', 'failed', 'unknown'];
const SOURCE_LABEL: Record<string, string> = { provided: 'Provided figures', estimated: 'Estimate', to_verify: 'Needs checking', verified: 'Marked verified in the response' };

let nextAnchor = 0;

/**
 * Port of ProtocolV13Renderer.tsx — turns a Yuzee protocol response (v1.3 / v1.4 content_blocks,
 * interaction, recommended actions, service handoff) into the counselling-output UI.
 *
 * Inputs mirror the React props: data (alias `response`), initialFields, rawJson, schemaValid,
 * semanticValid, validationErrors, readOnly, conversationId, hideRecommendedActions,
 * deferQuestionToWorkspace, pathwayLearningCues. React's `onInteract` is either the
 * `interactHandler` function input (awaited; `false` = not accepted) or the `(interact)` output,
 * in which case the caller completes the question widget with `reportInteractionResult(accepted)`.
 * `(openPathway)` is React's `onOpenPathway`: the "Build my pathway" button only appears when bound.
 */
@Component({
  selector: 'app-protocol-renderer',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, ConfirmDialogComponent, PathwayLearningCuesComponent, InteractionComponent, GuidanceListComponent],
  templateUrl: './protocol-renderer.component.html',
  styleUrls: ['./protocol-renderer.component.scss', './protocol-renderer-blocks.scss']
})
export class ProtocolRendererComponent {
  @Input() data?: YuzeeResponseV13 | null;
  /** Backwards-compatible alias for `data`. */
  @Input() set response(value: YuzeeResponseV13 | null | undefined) { this.data = value; }
  @Input() initialFields: Record<string, string> = {};
  @Input() rawJson?: string;
  @Input() schemaValid = true;
  @Input() semanticValid = true;
  @Input() validationErrors: string[] = [];
  @Input() readOnly = false;
  @Input() conversationId?: string;
  @Input() hideRecommendedActions = false;
  @Input() deferQuestionToWorkspace = false;
  @Input() pathwayLearningCues = false;
  @Input() interactHandler?: InteractHandler;

  @Output() interact = new EventEmitter<UserEvent>();
  @Output() openPathway = new EventEmitter<void>();

  @ViewChild(InteractionComponent) interactionRef?: InteractionComponent;

  readonly outputAnchor = 'pr' + (++nextAnchor);
  pendingAction: ServiceAction | null = null;
  actionStatus: Record<string, { executed: boolean; message: string }> = {};
  private mdCache = new Map<string, SafeHtml>();

  constructor(private api: ApiService, private sanitizer: DomSanitizer) {}

  // ---- envelope ---------------------------------------------------------

  get blocks(): YuzeeContentBlock[] {
    const d: any = this.data;
    const b = d?.content_blocks || d?.blocks || [];
    return Array.isArray(b) ? b : [];
  }

  get restBlocks(): YuzeeContentBlock[] { return this.blocks.slice(1); }

  get readableSections(): { title: string; index: number }[] {
    return this.blocks.map((block, index) => ({ title: block.title, index })).filter(section => section.title?.trim());
  }

  get careText(): string {
    const raw = this.data?.response_intent?.trim();
    const isProtocolLabel = raw ? /^[A-Z][A-Z_]{2,}$/.test(raw) : false;
    const text = isProtocolLabel ? '' : raw;
    return text && text !== 'none' ? text : '';
  }

  get suggestPathway(): boolean {
    if (!this.openPathway.observed || !this.data) return false;
    const hasWorkflowBlocks = this.blocks.some(b => (b.type === 'list' || b.type === 'steps') && Array.isArray(b.items) && b.items.some(i => i.status === 'current' || i.status === 'next'));
    const responseIntentStr = (this.data.response_intent || '') + (this.data as any).response_direction;
    return hasWorkflowBlocks || /ACTION_PLAN|GOAL|PATHWAY|ROADMAP|PLAN/i.test(responseIntentStr);
  }

  get showDraftOnly(): boolean {
    const d: any = this.data;
    return d?.current_mode === 'S_SERVICE_HANDOFF' || !!(d?.rmo_readiness?.ready_to_generate && d?.service_trigger?.service_intent_detected);
  }

  get showRecommendedActions(): boolean {
    const inter = this.data?.interaction;
    return !this.hideRecommendedActions && !!inter && inter.kind === 'none' && Array.isArray(inter.recommended_actions) && inter.recommended_actions.length > 0 && !this.readOnly;
  }

  get showService(): boolean {
    const s = this.data?.service_trigger;
    return !!s && (s.primary_requested_service || 'NONE') !== 'NONE' && s.trigger_now !== false && Array.isArray(s.actions) && s.actions.length > 0;
  }

  get serviceActions(): ServiceAction[] { return this.data?.service_trigger?.actions ?? []; }

  sectionId(index: number): string { return `${this.outputAnchor}-section-${index}`; }

  // ---- interaction ------------------------------------------------------

  handleActionClick(actionId: string, message: string): void {
    if (this.readOnly) return;
    const event: UserEvent = { type: 'action_clicked', action_id: actionId, value: message, timestamp: Date.now() };
    if (this.interactHandler) this.interactHandler(event); else this.interact.emit(event);
  }

  /** Call once the caller's own async submission settles for the active question (output path). */
  reportInteractionResult(accepted: boolean): void {
    this.interactionRef?.reportResult(accepted);
  }

  // ---- service actions --------------------------------------------------

  actId(act: ServiceAction): string { return act.action_id || act.id || ''; }
  trusted(act: ServiceAction): TrustedServiceAction | undefined { return TRUSTED_SERVICE_ACTIONS[this.actId(act)]; }
  statusOf(act: ServiceAction): { executed: boolean; message: string } | undefined { return this.actionStatus[this.actId(act)]; }

  triggerServiceAction(act: ServiceAction): void {
    if (act.requires_confirmation) this.pendingAction = act;
    else this.executeServiceAction(act);
  }

  executeServiceAction(act: ServiceAction): void {
    const actId = this.actId(act);
    const convId = this.conversationId || 'default';
    // Like the original's `await res.json()`: any parsed JSON body counts, whatever the HTTP status;
    // a network failure or unparseable body falls back to "Not connected". The dialog closes after.
    const settle = (data: any) => {
      const status = data && typeof data === 'object'
        ? { executed: !!data.executed, message: data.message || (data.executed ? 'Action Initiated' : 'Not connected in Token Lab.') }
        : { executed: false, message: 'Not connected in Token Lab.' };
      this.actionStatus = { ...this.actionStatus, [actId]: status };
      this.pendingAction = null;
    };
    this.api.post<ActionExecuteResponse>(`/conversations/${encodeURIComponent(convId)}/actions/${encodeURIComponent(actId)}/execute`, act).subscribe({
      next: res => settle(res),
      error: err => settle(err?.status && !(err.error instanceof ProgressEvent) ? err.error : null),
    });
  }

  confirmPendingAction(): void { if (this.pendingAction) this.executeServiceAction(this.pendingAction); }

  // ---- block helpers ----------------------------------------------------

  items(block: YuzeeContentBlock): any[] { return Array.isArray(block.items) ? block.items : []; }
  cols(block: YuzeeContentBlock): { key: string; label: string }[] { return Array.isArray(block.columns) ? block.columns : []; }
  rows(block: YuzeeContentBlock): any[] { return Array.isArray(block.rows) ? block.rows : []; }
  d(block: YuzeeContentBlock): any { return (block as any).data || {}; }
  list(value: unknown): any[] { return Array.isArray(value) ? value : []; }

  /** Sanitised by Angular's HTML sanitizer; only the fixed task-checkbox / footnote markup is added after it. */
  markdown(block: YuzeeContentBlock): SafeHtml {
    const b: any = block;
    const src = readableMarkdown(b.text || b.content || b.body || '');
    let html = this.mdCache.get(src);
    if (html === undefined) {
      html = this.sanitizer.bypassSecurityTrustHtml(markdownToHtml(src, h => this.sanitizer.sanitize(SecurityContext.HTML, h) ?? ''));
      this.mdCache.set(src, html);
    }
    return html;
  }

  cellValue(row: any, key: string): string | undefined { return row?.cells?.find((c: any) => c.key === key)?.value; }
  rowTone(row: any): string | undefined { return this.pathwayLearningCues ? learningToneIn((row.cells || []).map((c: any) => c.value).join(' ')) : undefined; }
  cellTone(value: string | undefined): string | undefined { return this.pathwayLearningCues ? learningToneIn(value || '') : undefined; }

  comparisonHasCriteria(block: YuzeeContentBlock): boolean {
    const cmpRows = this.rows(block);
    const cmpCols = this.cols(block);
    // Avoid duplicating the row label when Gemini also supplies it as an explicit criteria column.
    const criteriaAlreadyInColumns = cmpRows.length > 0 && cmpCols.some(col => /^(decision\s+)?(criteria|criterion|factor)$/i.test(col.label.trim()) && cmpRows.every(row => row.criteria?.trim() && this.cellValue(row, col.key)?.trim().toLowerCase() === row.criteria.trim().toLowerCase()));
    return cmpRows.some(r => r.criteria) && !criteriaAlreadyInColumns;
  }

  variant(block: YuzeeContentBlock): string {
    const v = block.variant || 'default';
    return Object.prototype.hasOwnProperty.call(CALLOUT_ICON, v) ? v : 'default';
  }
  calloutIcon(block: YuzeeContentBlock): string { return CALLOUT_ICON[this.variant(block)]; }

  status(value: string, allowed: string[], fallback: string): string { return allowed.includes(value) ? value : fallback; }
  cardStatus(s: string): string { return this.status(s, CARD_STATUS, 'neutral'); }
  milestoneStatus(s: string): string { return this.status(s, MILESTONE_STATUS, 'unknown'); }
  nodeStatus(s: string): string { return this.status(s, NODE_STATUS, 'neutral'); }
  laneStatus(s: string): string { return this.status(s, LANE_STATUS, 'upcoming'); }
  metricStatus(s: string): string { return this.status(s, METRIC_STATUS, 'neutral'); }
  stageStatus(s: string): string { return this.status(s, STAGE_STATUS, 'unknown'); }

  nodeLabel(block: YuzeeContentBlock, id: string): string { return this.list(this.d(block).nodes).find((n: any) => n.id === id)?.label || 'Unknown step'; }

  metricValue(m: any): string {
    return m.value_type === 'percentage' ? `${m.value}%` : m.value_type === 'rating' ? `${m.value}/${m.max ?? 10}` : `${m.value}${m.unit ? ` ${m.unit}` : ''}`;
  }

  sourceLabel(block: YuzeeContentBlock): string { return SOURCE_LABEL[this.d(block).source_status] || 'Source not specified'; }
  seriesHead(s: any): string { return s.label + (s.unit ? ' (' + s.unit + ')' : ''); }
  seriesValue(s: any, i: number): string { const v = s.values?.[i]; return v === undefined || v === null ? 'Not provided' : String(v); }

  isChecked(item: any): boolean { return ['complete', 'completed'].includes(item.status); }

  // default fallback
  private isTypeAsTitle(block: YuzeeContentBlock): boolean { return block.title === block.type || /^row_/.test(block.title || ''); }
  fallbackHeading(block: YuzeeContentBlock): string {
    const r = this.rows(block);
    return this.isTypeAsTitle(block) ? (r[0]?.value || r[0]?.text || '') : (block.title || '');
  }
  fallbackRows(block: YuzeeContentBlock): any[] {
    const r = this.rows(block);
    return this.isTypeAsTitle(block) && r.length > 0 ? r.slice(1) : r;
  }
  rowLabel(row: any): string { return row.label || row.key || row.criteria || ''; }
  rowValue(row: any): string { return row.value || row.text || ''; }
}
