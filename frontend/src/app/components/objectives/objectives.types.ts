/**
 * Shared TypeScript shapes for the Objectives workspace feature — Angular port of the old React
 * app's `src/objectives/contract.ts` + relevant slices of `service.ts`/`routing.ts`, trimmed to
 * match what `ObjectiveSession.java` / `ObjectiveCatalogueService.java` / `ChatController.java`
 * (the `/{id}/objectives` + `/api/objectives/catalogue` endpoints) actually return.
 *
 * `plan`/`context`/`result` stay loosely typed (`Record<string, any>` / `any`) rather than fully
 * typed, mirroring the Java side: `ObjectiveSession.java` itself keeps `plan`/`context` as
 * `Map<String,Object>` because the model's JSON is schema-guided prompt output, not a fixed
 * contract enforced end-to-end (see that class's javadoc). Only the fields this UI actually reads
 * are named here.
 */
import type { ExplorationChoice } from '../warehouse/warehouse.types';

// ---------------------------------------------------------------------------
// Catalogue — GET /api/objectives/catalogue (SystemController#objectivesCatalogue)
// ---------------------------------------------------------------------------

export interface ObjectiveCatalogueItem {
  tool_id: string;
  button_label: string;
  topic_name: string;
  primary_category: string;
  clear_purpose: string;
  when_to_serve?: string;
  do_not_serve_when?: string;
  key_inputs?: string;
  key_output?: string;
  needs_live_data?: string;
  max_interactions: number;
  allowed_primitives?: string[];
  available: boolean;
}

export interface ObjectivesCatalogueResponse {
  version: string;
  experimental: boolean;
  objectives: ObjectiveCatalogueItem[];
}

// ---------------------------------------------------------------------------
// Plan primitives — the model's per-step UI plan (ObjectiveSession#plan)
// ---------------------------------------------------------------------------

export interface ObjectiveOption {
  id: string;
  label: string;
  detail?: string;
}

/** A content/evidence note. Also reused verbatim as the shape rendered by <app-result-value>. */
export interface ObjectiveNote {
  label?: string;
  detail?: string;
  source_status?: 'USER_CONFIRMED' | 'AI_INFERRED' | 'SOURCED_CURRENT_FACT' | 'UNKNOWN' | 'GENERAL_GUIDANCE' | string;
  evidence_refs?: string[];
}

export interface ObjectiveTableRow {
  label: string;
  cells: string[];
}

export interface ObjectiveComponentSettings {
  allow_unsure?: boolean;
  min?: number;
  max?: number;
  min_label?: string;
  max_label?: string;
  buckets?: string[];
}

/** One entry of `plan.ui` (the backend normalises Gemini's `type` to `component`). */
export interface ObjectivePlanComponent {
  id: string;
  component?: string;
  required?: boolean;
  prompt?: string;
  purpose?: string;
  options?: ObjectiveOption[];
  content?: ObjectiveNote[];
  columns?: string[];
  rows?: ObjectiveTableRow[];
  settings?: ObjectiveComponentSettings;
}

/** The original reads `c.component`. */
export function componentType(c: ObjectivePlanComponent | null | undefined): string {
  return c?.component || '';
}

export interface ObjectiveReadinessDimension {
  name: string;
  score: number;
  reason: string;
}

export interface ObjectiveReadiness {
  enabled: boolean;
  name?: string | null;
  score?: number | null;
  dimensions?: ObjectiveReadinessDimension[];
  /** Global system prompt rule 11: "Always show dimensions and blockers, not just one number." Not
   *  deterministically validated server-side (ObjectiveService#validateAndParse checks
   *  name/score/dimensions only), so always guard with `?? []` when reading it. */
  blockers?: string[];
}

export interface ObjectiveNextAction {
  id: string;
  type: string;
  label?: string;
}

export interface ObjectiveHandoff {
  confirmed_user_inputs?: Record<string, unknown>;
  summary?: string;
  result?: unknown;
  recommended_next_action?: unknown;
  evidence_and_unknowns?: unknown;
}

export interface ObjectiveDerivedSignal {
  signal: string;
  basis: string;
}

export interface ObjectivePlan {
  objective_id: string;
  status: 'NEEDS_INPUT' | 'NEEDS_RESEARCH' | 'COMPLETE' | string;
  stage?: string;
  ui: ObjectivePlanComponent[];
  confirmed_inputs?: Record<string, unknown>;
  derived_signals?: ObjectiveDerivedSignal[];
  unknowns: string[];
  /** Untyped by design (like `plan`/`context` — see this file's top-of-file doc comment): the
   *  model's arbitrary per-objective result JSON. Read via `any` rather than `Record<string,any>`
   *  so templates can use plain dot-property access under this project's `strict` tsconfig
   *  (`noPropertyAccessFromIndexSignature` would otherwise force bracket notation everywhere). */
  result: any;
  readiness: ObjectiveReadiness;
  next_actions: ObjectiveNextAction[];
  handoff: ObjectiveHandoff;
}

// ---------------------------------------------------------------------------
// Answers / sessions — ObjectiveSession.java
// ---------------------------------------------------------------------------

export interface ObjectiveAnswer {
  component_id: string;
  value: unknown;
  unsure?: boolean;
  /** answer/correct operations also accept these two control flags in the same body map. */
  retry?: boolean;
  cancel?: boolean;
}

/** Port of history.ts's AnswerReceipt. */
export interface ObjectiveAnswerReceipt {
  id: string;
  question: string;
  answer: unknown;
  submittedAt?: number;
  component?: ObjectivePlanComponent | null;
  input?: ObjectiveAnswer;
  localOnly?: boolean;
}

export interface ObjectivePendingCorrection {
  text: string;
  createdAt: number;
  courseIds?: string[];
  explorationChoices?: ExplorationChoice;
}

export interface ObjectiveUserCorrection {
  text: string;
  createdAt: number;
  courseIds?: string[];
}

export interface ObjectiveSession {
  id: string;
  conversationId: string;
  objectiveId: string;
  label: string;
  sourceMessageId: string;
  revision: number;
  interactionCount: number;
  state: 'ACTIVE' | 'COMPLETE' | 'CANCELLED';
  createdAt: number;
  updatedAt: number;
  plan: ObjectivePlan | null;
  /** user_message, prior_context_text, confirmed_facts, approved_evidence, shared_context,
   *  user_corrections, context_limited, ... — see ObjectiveService.java#buildContext. Always
   *  `warehouse_data`-free in this deployment (ObjectiveService.java class javadoc). */
  context: any;
  answers: ObjectiveAnswerReceipt[];
  pendingAnswer?: ObjectiveAnswerReceipt | null;
  pendingCorrection?: ObjectivePendingCorrection | null;
  activation?: 'manual' | 'automatic';
  routing?: any;
  autoHandoff?: boolean;
  handoffAt?: number | null;
  handoffMessageId?: string | null;
}

/** Port of service.ts's handoff() result shape (ObjectiveService.java#handoff). */
export interface ObjectiveHandoffResult {
  sessionId: string;
  revision: number;
  objective: string;
  status: string;
  confirmed_inputs?: unknown;
  summary?: string;
  result?: unknown;
  unknowns?: string[];
  recommended_next_action?: unknown;
  evidence_and_unknowns?: unknown;
  readiness?: ObjectiveReadiness;
  source: string;
}

// ---------------------------------------------------------------------------
// Pure helpers ported from the original's objectives/{history,contract,WorkspaceResult,
// workspacePolicy,routing}.ts
// ---------------------------------------------------------------------------

export const stateLabel = (s: ObjectiveSession) => s.state === 'COMPLETE' ? 'Complete' : s.state === 'CANCELLED' ? 'Closed' : 'In progress';

/** history.ts answerText(). */
export function answerText(value: unknown): string {
  if (Array.isArray(value)) return value.map(answerText).join(' · ');
  if (value && typeof value === 'object') return Object.entries(value as object).map(([k, v]) => `${k}: ${answerText(v)}`).join('\n');
  return value == null ? 'Not sure' : String(value);
}

/** history.ts answerHistory(). */
export function answerHistory(session: ObjectiveSession): ObjectiveAnswerReceipt[] {
  if (session.answers) return session.answers;
  // Older workspaces retained exact question/answer facts but no submission time.
  return Object.entries(session.context?.['confirmed_facts'] || {}).filter(([k]) => /^answer_\d+$/.test(k)).map(([id, value]) => {
    try { return { id, ...JSON.parse(String(value)) }; } catch { return { id, question: 'Your earlier answer', answer: value }; }
  });
}

/** contract.ts answerFact() + history.ts answerReceipt(). */
export function answerReceipt(plan: any, input: ObjectiveAnswer): ObjectiveAnswerReceipt {
  const c = plan.ui.find((x: any) => x.id === input.component_id);
  const label = (v: unknown) => typeof v === 'string' ? (c?.options?.find((o: any) => o.id === v)?.label || v) : v;
  const fact = { question: c?.prompt || c?.purpose, answer: input.unsure ? 'Not sure' : Array.isArray(input.value) ? input.value.map(label) : label(input.value) };
  return { id: input.component_id + '-' + Date.now(), ...fact, submittedAt: Date.now(), component: c, input };
}

const equalKeys = (a: string[], b: string[]) => a.length === b.length && a.every(x => b.includes(x));
/** contract.ts validateObjectiveAnswer(). */
export function validateObjectiveAnswer(plan: any, answer: ObjectiveAnswer): ObjectiveAnswer {
  const c = plan.ui.find((x: any) => x.required && componentType(x) !== 'action_handoff');
  if (!c || !answer || answer.component_id !== c.id) throw Error('This question has changed. Reload the workspace.');
  if (Object.keys(answer).some(k => !['component_id', 'value', 'unsure'].includes(k))) throw Error('Unexpected answer fields.');
  if (answer.unsure === true) { if (!c.settings?.allow_unsure) throw Error('Please answer the current question.'); return { component_id: c.id, value: null, unsure: true }; }
  const v: any = answer.value, ids = (c.options || []).map((x: any) => x.id), kind = componentType(c);
  if (['single_choice', 'yes_no_unsure'].includes(kind)) {
    if (typeof v !== 'string' || !ids.includes(v)) throw Error('Choose one of the available options.');
  } else if (kind === 'multi_select' || kind === 'ranking') {
    if (!Array.isArray(v) || !v.length || v.some(x => typeof x !== 'string' || !ids.includes(x)) || new Set(v).size !== v.length) throw Error('Choose valid options.');
    if (kind === 'ranking' && !equalKeys(v, ids)) throw Error('Rank every option once.');
  } else if (kind === 'spectrum') {
    if (typeof v !== 'number' || !Number.isFinite(v) || v < c.settings.min || v > c.settings.max) throw Error('Choose a value within the range.');
  } else if (kind === 'card_sort') {
    if (!v || typeof v !== 'object' || Array.isArray(v) || !equalKeys(Object.keys(v), ids) || Object.values(v).some(x => !c.settings.buckets.includes(x))) throw Error('Place each card into a group.');
  } else if (typeof v !== 'string' || !v.trim() || v.length > 4000) throw Error('Add an answer of up to 4,000 characters.');
  return { component_id: c.id, value: v };
}

/** WorkspaceResult.tsx activityTitle(): titles come from the saved comparison, never an inferred replacement goal. */
export function activityTitle(session: any): string {
  const selected = session?.context?.selected_course_ids || [];
  const chosen = selected.length === 1 ? session?.context?.warehouse_data?.courses?.find((c: any) => c.id === selected[0]) : null;
  if (session?.objectiveId === 'STUDY_004' && chosen) return 'Explore ' + (chosen.code || chosen.name) + ' at ' + chosen.provider;
  const comparison = session?.context?.warehouse_data?.comparison;
  if (session?.objectiveId === 'STUDY_004' && comparison) { const codes = comparison.qualifications?.map((q: any) => q.code) || []; return codes.length === 1 ? 'Compare ' + codes[0] + ' providers' : 'Compare training providers'; }
  const jobs = session?.plan?.result?.job_comparison?.jobs;
  const names = Array.isArray(jobs) ? jobs.map((j: any) => j.occupation_or_vacancy).filter((n: any) => typeof n === 'string' && n.trim()) : [];
  return names.length >= 2 ? names.slice(0, 3).join(' vs ') : session?.label || 'Your activity';
}

/** WorkspaceResult.tsx comparisonRows(). */
export function comparisonRows(result: any): { jobs: any[]; labels: string[] } | null {
  const jobs = result?.job_comparison?.jobs;
  if (!Array.isArray(jobs) || jobs.length < 2) return null;
  const labels = [...new Set<string>(jobs.flatMap((j: any) => (j.criteria || []).map((c: any) => c.criterion)).filter((x: any) => typeof x === 'string' && x.trim()))];
  return labels.length ? { jobs, labels } : null;
}

/** workspacePolicy.ts resultReady(). */
export function resultReady(session: ObjectiveSession): boolean {
  const p: any = session.plan;
  return session.state !== 'CANCELLED' && !session.pendingAnswer && !session.pendingCorrection && !!p
    && (['COMPLETE', 'NEEDS_RESEARCH'].includes(p.status) || (p.status === 'NEEDS_INPUT' && p.readiness?.blockers?.length > 0))
    && !(p.ui || []).some((c: any) => c.required && componentType(c) !== 'action_handoff');
}

/** workspacePolicy.ts workspaceResultMessage(). */
export function workspaceResultMessage(session: ObjectiveSession): string {
  const prefix = session.plan?.status === 'NEEDS_RESEARCH' ? 'Evidence still needed' : session.plan?.status === 'NEEDS_INPUT' ? 'Review the next options' : 'Activity result';
  return `${prefix}: ${session.label}`;
}

/** routing.ts objectiveSelectionBoundary(). */
export function objectiveSelectionBoundary(text: string): string | null {
  const t = text.trim();
  if (!t || t.length > 16000) return 'input-limit';
  if (/^(hi|hello|hey|thanks?|thank you|ok|okay|yes|no|stop|cancel|never mind)[!. ]*$/i.test(t)) return 'conversation';
  if (/\b(?:do not|don['’]?t)\s+(?:need|want|open|suggest|offer|show|start|run)\b.{0,45}\b(?:activit(?:y|ies)|tools?|workspace|suggestions?)\b/i.test(t)) return 'declined';
  if (/\b(stop suggesting|no more (tools|suggestions)|do not suggest|don.t suggest)\b/i.test(t)) return 'declined';
  return null;
}

export type ObjectiveMatch = { id: string; score: number; sources?: ('user' | 'context')[]; userScore?: number; contextScore?: number };

/** routing.ts objectiveShortlist(): preview retrieval gate over the available catalogue. */
export function objectiveShortlist(matches: ObjectiveMatch[], catalogue: ObjectiveCatalogueItem[]): ObjectiveMatch[] {
  const ids = new Set(catalogue.filter(o => o.available).map(o => o.tool_id));
  return matches.filter((x, i) => ids.has(x.id) && Number.isFinite(x.score) && x.score >= 0.5 && x.score <= 1.001 && matches.findIndex(y => y.id === x.id) === i).slice(0, 20);
}

/** routing.ts fuseObjectiveMatches(): reciprocal-rank fusion; context cannot erase raw-user candidates. */
export function fuseObjectiveMatches(user: ObjectiveMatch[], context: ObjectiveMatch[], limit = 20, userText = ''): ObjectiveMatch[] {
  const all = new Map<string, ObjectiveMatch & { fusion: number }>();
  for (const [source, ranking] of [['user', user], ['context', context]] as const) ranking.forEach((c, i) => {
    const row = all.get(c.id) || { id: c.id, score: c.score, sources: [], fusion: 0 };
    row.score = Math.max(row.score, c.score); row.sources!.push(source); if (source === 'user') row.userScore = c.score; else row.contextScore = c.score;
    row.fusion += (source === 'user' ? 1.2 : 1) / (60 + i + 1); all.set(c.id, row);
  });
  const fused = [...all.values()].sort((a, b) => b.fusion - a.fusion);
  const reference = /^(both|either|that one|this one|the first|the second|same|yes|no)(?:[,.! ]|$)/i.test(userText.trim()) && userText.length < 80;
  const ordered = reference && context.length ? [...context.slice(0, Math.max(1, limit - 4)).map(c => all.get(c.id)!), ...user.slice(0, 4).map(c => all.get(c.id)!), ...fused] : fused;
  return ordered.filter((x, i, rows) => rows.findIndex(y => y.id === x.id) === i).slice(0, limit).map(({ fusion, ...row }) => row);
}

// orchestration/workspacePresence.ts: the workspace visible on screen, sent with each chat message.
let visible: { conversationId: string; sessionId: string } | null = null;
export function setVisibleWorkspace(value: typeof visible): void { visible = value; }
export function visibleWorkspaceId(conversationId: string): string | null { return visible?.conversationId === conversationId ? visible.sessionId : null; }
