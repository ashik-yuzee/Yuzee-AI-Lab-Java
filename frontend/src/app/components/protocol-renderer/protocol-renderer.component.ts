import { Component, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { InteractionComponent } from './interaction/interaction.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog/confirm-dialog.component';
import {
  YuzeeResponseV13, YuzeeContentBlock, YuzeeItem, YuzeeRow, ServiceAction, UserEvent, ActionExecuteResponse,
  CardItem, TimelineMilestone, FlowNode, FlowEdge, PathwayLane, ScorecardMetric, ChartSeries, ProgressStage
} from '../../models/types';

interface GuidanceRowVm {
  id: string;
  ordinal: number | null;
  title: string;
  description: string[];
  sideLabel?: string;
  sideText?: string;
  tone: string;
  statusLabel?: string;
  statusIcon?: 'flag' | 'arrow' | 'warning' | 'check' | 'info';
}

const GUIDANCE_STATES: Record<string, { label: string; tone: string; icon: GuidanceRowVm['statusIcon'] }> = {
  current: { label: 'Start here', tone: 'blue', icon: 'flag' },
  next: { label: 'Next step', tone: 'purple', icon: 'arrow' },
  warning: { label: 'Check this', tone: 'amber', icon: 'warning' },
  blocked: { label: 'Needs attention', tone: 'rose', icon: 'warning' },
  complete: { label: 'Completed', tone: 'green', icon: 'check' },
  positive: { label: 'Potential benefit', tone: 'neutral', icon: 'info' },
  negative: { label: 'Consideration', tone: 'amber', icon: 'info' },
};

const CALLOUT_META: Record<string, { cls: string; icon: string }> = {
  info: { cls: 'callout-info', icon: 'ℹ' },
  default: { cls: 'callout-info', icon: 'ℹ' },
  success: { cls: 'callout-success', icon: '✓' },
  warning: { cls: 'callout-warning', icon: '⚠' },
  danger: { cls: 'callout-danger', icon: '✕' },
  muted: { cls: 'callout-muted', icon: 'ⓘ' },
};

const CARD_STATUS_CLASS: Record<string, string> = {
  recommended: 'card-recommended', alternative: 'card-alternative', completed: 'card-completed',
  current: 'card-current', blocked: 'card-blocked', warning: 'card-warning', neutral: 'card-neutral', upcoming: 'card-neutral',
};

const TIMELINE_DOT_CLASS: Record<string, string> = {
  completed: 'dot-completed', current: 'dot-current', upcoming: 'dot-upcoming', blocked: 'dot-blocked', paused: 'dot-paused', unknown: 'dot-unknown',
};

const FLOW_NODE_CLASS: Record<string, string> = {
  recommended: 'node-recommended', current: 'node-current', completed: 'node-completed', blocked: 'node-blocked', neutral: 'node-neutral', upcoming: 'node-upcoming',
};

const LANE_STEP_STATUS_CLASS: Record<string, string> = {
  completed: 'step-status-completed', current: 'step-status-current', upcoming: 'step-status-upcoming', blocked: 'step-status-blocked',
};

const METRIC_STATUS_CLASS: Record<string, string> = {
  excellent: 'metric-excellent', good: 'metric-good', warning: 'metric-warning', critical: 'metric-critical', neutral: 'metric-neutral',
};

const CHART_SOURCE_LABEL: Record<string, string> = {
  provided: 'Provided figures', estimated: 'Estimate', to_verify: 'Needs checking', verified: 'Marked verified in the response',
};

const STAGE_STATUS_CLASS: Record<string, string> = {
  completed: 'stage-completed', current: 'stage-current', upcoming: 'stage-upcoming', blocked: 'stage-blocked', paused: 'stage-paused', failed: 'stage-failed', unknown: 'stage-unknown',
};

/**
 * Port of ProtocolV13Renderer.tsx — renders a Yuzee protocol response's content_blocks[]
 * (v1.3 + the 7 new v1.4 block types), the interaction widget, recommended-action chips
 * and the service-action confirm/execute flow.
 *
 * Public API:
 * - @Input({required:true}) response: YuzeeResponseV13
 * - @Input() conversationId, readOnly, initialFields, hideRecommendedActions, semanticValid, validationErrors
 * - @Output() interact: EventEmitter<UserEvent> — bubbles both interaction submissions and
 *   recommended-action-chip clicks. Callers must eventually call `reportInteractionResult(accepted)`
 *   once their own async submit (e.g. TokenLabService.sendMessage) settles, so the embedded
 *   <app-interaction> can leave its pending state (see InteractionComponent's doc comment).
 */
@Component({
  selector: 'app-protocol-renderer',
  standalone: true,
  imports: [CommonModule, InteractionComponent, ConfirmDialogComponent],
  templateUrl: './protocol-renderer.component.html',
  styleUrl: './protocol-renderer.component.scss'
})
export class ProtocolRendererComponent {
  @Input({ required: true }) response!: YuzeeResponseV13;
  @Input() conversationId?: string;
  @Input() readOnly = false;
  @Input() initialFields: Record<string, string> = {};
  @Input() hideRecommendedActions = false;
  @Input() semanticValid = true;
  @Input() validationErrors: string[] = [];

  @Output() interact = new EventEmitter<UserEvent>();

  @ViewChild(InteractionComponent) interactionRef?: InteractionComponent;

  readonly outputAnchor = 'pr-' + Math.random().toString(36).slice(2);

  pendingAction: ServiceAction | null = null;
  actionStatus: Record<string, ActionExecuteResponse> = {};

  constructor(private api: ApiService) {}

  /**
   * Used as the `@for` track expression over content_blocks instead of the inline
   * `track block.id ?? $index` — Angular 18's `@for` track compiler can emit a reference to an
   * undeclared temporary variable ("tmp_N_0 is not defined") for a `??` expression directly in a
   * track binding; a plain method call avoids the codegen path that triggers it.
   */
  trackBlock(block: YuzeeContentBlock, index: number): string {
    return block?.id ?? String(index);
  }

  // ---------------------------------------------------------------------
  // Envelope-level helpers
  // ---------------------------------------------------------------------

  get careText(): string {
    const raw = (this.response?.response_intent ?? '').trim();
    if (!raw || raw.toLowerCase() === 'none') return '';
    return /^[A-Z][A-Z_]{2,}$/.test(raw) ? '' : raw;
  }

  get readableSections(): { title: string; index: number }[] {
    return (this.response?.content_blocks ?? [])
      .map((block, index) => ({ title: block.title, index }))
      .filter(s => !!s.title?.trim());
  }

  get showSectionNav(): boolean {
    return this.readableSections.length >= 5;
  }

  sectionAnchor(index: number): string {
    return `${this.outputAnchor}-section-${index}`;
  }

  get service() {
    return this.response?.service_trigger;
  }

  get showServiceHandoff(): boolean {
    const s = this.service;
    return !!s && (s.primary_requested_service ?? 'NONE') !== 'NONE' && s.trigger_now !== false && !!s.actions?.length;
  }

  get showDraftOnly(): boolean {
    return this.response?.current_mode === 'S_SERVICE_HANDOFF'
      || (!!this.response?.rmo_readiness?.ready_to_generate && !!this.service?.service_intent_detected);
  }

  get showRecommendedActions(): boolean {
    return !this.hideRecommendedActions && !this.readOnly
      && this.response?.interaction?.kind === 'none'
      && !!this.response?.interaction?.recommended_actions?.length;
  }

  handleActionClick(actionId: string, message: string): void {
    if (this.readOnly) return;
    this.interact.emit({ type: 'action_clicked', action_id: actionId, value: message, timestamp: Date.now() });
  }

  onInteractionSubmit(event: UserEvent): void {
    this.interact.emit(event);
  }

  /** Call once the caller's own async submission (e.g. sendMessage) settles for the active interaction. */
  reportInteractionResult(accepted: boolean): void {
    this.interactionRef?.reportResult(accepted);
  }

  // ---------------------------------------------------------------------
  // Service action execution
  // ---------------------------------------------------------------------

  actionId(act: ServiceAction): string {
    return act.action_id || act.id || '';
  }

  /** Typed lookup (plain index access on a Record doesn't tell TS the key may be absent). */
  statusOf(actId: string): ActionExecuteResponse | undefined {
    return this.actionStatus[actId];
  }

  triggerServiceAction(act: ServiceAction): void {
    if (act.requires_confirmation) this.pendingAction = act;
    else this.executeServiceAction(act);
  }

  executeServiceAction(act: ServiceAction): void {
    const actId = this.actionId(act);
    const convId = this.conversationId || 'default';
    // Assumed contract (backend endpoint does not exist yet):
    //   POST /api/conversations/{convId}/actions/{actionId}/execute   body: {}
    //   200 response: { executed: boolean; message: string }
    this.api.post<ActionExecuteResponse>(
      `/conversations/${encodeURIComponent(convId)}/actions/${encodeURIComponent(actId)}/execute`, {}
    ).subscribe({
      next: (res) => {
        this.actionStatus = {
          ...this.actionStatus,
          [actId]: { executed: !!res?.executed, message: res?.message || (res?.executed ? 'Action Initiated' : 'Not connected in Token Lab.') }
        };
      },
      error: () => {
        this.actionStatus = { ...this.actionStatus, [actId]: { executed: false, message: 'Not connected in Token Lab.' } };
      }
    });
    this.pendingAction = null;
  }

  confirmPendingAction(): void {
    if (this.pendingAction) this.executeServiceAction(this.pendingAction);
  }

  cancelPendingAction(): void {
    this.pendingAction = null;
  }

  // ---------------------------------------------------------------------
  // Markdown (text blocks) — bold/italic/inline-code/paragraphs + GFM pipe tables.
  // No markdown library is installed in this app; this is a small, dependency-free
  // renderer covering exactly what the protocol's `text` blocks use.
  // ---------------------------------------------------------------------

  renderMarkdown(text: string | undefined): string {
    if (!text) return '';
    const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    const codeSpans: string[] = [];
    const withPlaceholders = text.replace(/`([^`\n]+)`/g, (_m, code: string) => {
      codeSpans.push(`<code>${escape(code)}</code>`);
      return ` ${codeSpans.length - 1} `;
    });

    let html = escape(withPlaceholders);

    html = html.replace(
      /^\|(.+)\|[ \t]*\r?\n\|[ :\-|]+\|[ \t]*\r?\n((?:\|.*\|[ \t]*\r?\n?)*)/gm,
      (_m, headerRow: string, bodyRows: string) => {
        const headers = headerRow.split('|').map((h: string) => h.trim()).filter(Boolean);
        const bodyHtml = bodyRows.split(/\r?\n/).map((l: string) => l.trim()).filter(Boolean).map((line: string) => {
          const cells = line.replace(/^\||\|$/g, '').split('|').map((c: string) => c.trim());
          return `<tr>${cells.map((c: string) => `<td>${c}</td>`).join('')}</tr>`;
        }).join('');
        return `<div class="response-table-scroll"><table class="table table-bordered response-md-table"><thead><tr>${headers.map((h: string) => `<th>${h}</th>`).join('')}</tr></thead><tbody>${bodyHtml}</tbody></table></div>`;
      }
    );

    html = html
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .split(/\n{2,}/)
      .map(block => /^<div class="response-table-scroll"/.test(block.trim()) ? block : `<p>${block.trim().replace(/\n/g, '<br>')}</p>`)
      .join('');

    return html.replace(/ (\d+) /g, (_m, i: string) => codeSpans[Number(i)]);
  }

  // ---------------------------------------------------------------------
  // Guidance list (list/steps) — port of GuidanceList.tsx
  // ---------------------------------------------------------------------

  guidanceOrdered(block: YuzeeContentBlock): boolean {
    return block.type === 'steps';
  }

  guidanceRows(block: YuzeeContentBlock): GuidanceRowVm[] {
    const ordered = this.guidanceOrdered(block);
    const checkSection = /\b(what.*check|needs checking|still.*check|to confirm|to verify)\b/i.test(block.title || '');
    return (block.items ?? []).map((item: YuzeeItem, index: number) => {
      const state = checkSection && item.status === 'warning' ? undefined : GUIDANCE_STATES[item.status];
      const tone = state?.tone ?? 'neutral';
      const numberedTitle = ordered
        ? item.title?.match(/^(?:(?:stage|step)\s+([1-9]\d*)\s*[:.\-–—]\s*|([1-9]\d*)[.)]\s+)(\S[\s\S]*)$/i)
        : null;
      const ordinal = numberedTitle ? Number(numberedTitle[1] || numberedTitle[2]) : index + 1;
      const repeatedValue = ordered && (
        (!!numberedTitle && item.value?.trim() === String(ordinal))
        || new RegExp(`^(?:stage|step)\\s+${ordinal}[.:]?$`, 'i').test(item.value?.trim() ?? '')
      );
      const value = repeatedValue ? '' : item.value;
      const title = numberedTitle ? numberedTitle[3] : item.title;
      const description: string[] = [];
      if (item.text || value) description.push(item.text || value);
      if (item.text && value && value !== item.text) description.push(value);
      return {
        id: item.id || String(index),
        ordinal: numberedTitle ? ordinal : null,
        title,
        description,
        sideLabel: item.side_label,
        sideText: item.side_text,
        tone,
        statusLabel: state?.label,
        statusIcon: state?.icon,
      };
    });
  }

  // ---------------------------------------------------------------------
  // table / comparison
  // ---------------------------------------------------------------------

  cellValue(row: YuzeeRow, key: string): string {
    return row.cells?.find(c => c.key === key)?.value || 'Not provided';
  }

  private rawCell(row: YuzeeRow, key: string): string {
    return row.cells?.find(c => c.key === key)?.value ?? '';
  }

  comparisonHasCriteria(block: YuzeeContentBlock): boolean {
    const rows = block.rows ?? [];
    const cols = block.columns ?? [];
    const criteriaAlreadyInColumns = rows.length > 0 && cols.some(col =>
      /^(decision\s+)?(criteria|criterion|factor)$/i.test(col.label.trim())
      && rows.every(row => !!row.criteria?.trim() && this.rawCell(row, col.key).trim().toLowerCase() === row.criteria!.trim().toLowerCase())
    );
    return rows.some(r => !!r.criteria) && !criteriaAlreadyInColumns;
  }

  // ---------------------------------------------------------------------
  // callout
  // ---------------------------------------------------------------------

  calloutMeta(block: YuzeeContentBlock) {
    return CALLOUT_META[block.variant || 'default'] ?? CALLOUT_META['default'];
  }

  // ---------------------------------------------------------------------
  // cards / timeline / flow / pathway_map / scorecard / chart / progress
  // (v1.4 typed `data` payloads)
  // ---------------------------------------------------------------------

  cardsOf(block: YuzeeContentBlock): CardItem[] { return (block.data?.['cards'] as CardItem[]) ?? []; }
  cardStatusClass(status?: string): string { return CARD_STATUS_CLASS[status || 'neutral'] ?? CARD_STATUS_CLASS['neutral']; }

  milestonesOf(block: YuzeeContentBlock): TimelineMilestone[] { return (block.data?.['milestones'] as TimelineMilestone[]) ?? []; }
  timelineDotClass(status: string): string { return TIMELINE_DOT_CLASS[status] ?? TIMELINE_DOT_CLASS['unknown']; }

  nodesOf(block: YuzeeContentBlock): FlowNode[] { return (block.data?.['nodes'] as FlowNode[]) ?? []; }
  edgesOf(block: YuzeeContentBlock): FlowEdge[] { return (block.data?.['edges'] as FlowEdge[]) ?? []; }
  flowNodeClass(status: string): string { return FLOW_NODE_CLASS[status] ?? FLOW_NODE_CLASS['neutral']; }
  flowNodeLabel(block: YuzeeContentBlock, id: string): string { return this.nodesOf(block).find(n => n.id === id)?.label ?? 'Unknown step'; }

  lanesOf(block: YuzeeContentBlock): PathwayLane[] { return (block.data?.['lanes'] as PathwayLane[]) ?? []; }
  pathwayGoal(block: YuzeeContentBlock): string { return (block.data?.['goal'] as string) ?? ''; }
  laneStepStatusClass(status: string): string { return LANE_STEP_STATUS_CLASS[status] ?? ''; }

  metricsOf(block: YuzeeContentBlock): ScorecardMetric[] { return (block.data?.['metrics'] as ScorecardMetric[]) ?? []; }
  metricStatusClass(status: string): string { return METRIC_STATUS_CLASS[status] ?? METRIC_STATUS_CLASS['neutral']; }
  metricDisplay(m: ScorecardMetric): string {
    if (m.value_type === 'percentage') return `${m.value}%`;
    if (m.value_type === 'rating') return `${m.value}/${m.max ?? 10}`;
    return `${m.value}${m.unit ? ' ' + m.unit : ''}`;
  }
  trendGlyph(trend: string): string { return trend === 'up' ? '↑' : trend === 'down' ? '↓' : ''; }

  chartCategories(block: YuzeeContentBlock): string[] { return (block.data?.['categories'] as string[]) ?? []; }
  chartSeries(block: YuzeeContentBlock): ChartSeries[] { return (block.data?.['series'] as ChartSeries[]) ?? []; }
  chartSourceLabel(block: YuzeeContentBlock): string {
    const status = (block.data?.['source_status'] as string) ?? '';
    return CHART_SOURCE_LABEL[status] ?? 'Source not specified';
  }
  chartCellValue(series: ChartSeries, index: number): string {
    const v = series.values?.[index];
    return v === undefined || v === null ? 'Not provided' : String(v);
  }

  stagesOf(block: YuzeeContentBlock): ProgressStage[] { return (block.data?.['stages'] as ProgressStage[]) ?? []; }
  stageStatusClass(status: string): string { return STAGE_STATUS_CLASS[status] ?? STAGE_STATUS_CLASS['unknown']; }
  stageGlyph(stage: ProgressStage, index: number): string { return stage.status === 'completed' ? '✓' : String(index + 1); }

  // ---------------------------------------------------------------------
  // Fallback (unknown block type) — generic card layout
  // ---------------------------------------------------------------------

  private isTypeAsTitle(block: YuzeeContentBlock): boolean {
    return block.title === block.type || /^row_/.test(block.title || '');
  }

  fallbackCardHeading(block: YuzeeContentBlock): string {
    const rows = (block.rows as any[]) ?? [];
    return this.isTypeAsTitle(block) ? (rows[0]?.value || rows[0]?.text || '') : (block.title || '');
  }

  fallbackBodyRows(block: YuzeeContentBlock): any[] {
    const rows = (block.rows as any[]) ?? [];
    return this.isTypeAsTitle(block) && rows.length > 0 ? rows.slice(1) : rows;
  }

  fallbackRowLabel(row: any): string { return row.label || row.key || row.criteria || ''; }
  fallbackRowValue(row: any): string { return row.value || row.text || ''; }
}
