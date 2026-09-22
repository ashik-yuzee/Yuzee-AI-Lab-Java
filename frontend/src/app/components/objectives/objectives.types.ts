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

/**
 * One entry of `plan.ui`. `component`/`type`: ObjectiveService.java's `componentType()` notes that
 * Gemini reliably emits this field as `"type"` even though the prompt calls it `"component"` —
 * read `component` and fall back to `type`, exactly like the Java side does. Use the
 * `componentType()` helper below rather than reading either field directly.
 */
export interface ObjectivePlanComponent {
  id: string;
  component?: string;
  type?: string;
  required?: boolean;
  prompt?: string;
  purpose?: string;
  options?: ObjectiveOption[];
  content?: ObjectiveNote[];
  columns?: string[];
  rows?: ObjectiveTableRow[];
  settings?: ObjectiveComponentSettings;
}

/** Java-side fallback: read `component`, then `type`. Mirrors ObjectiveService#componentType(). */
export function componentType(c: ObjectivePlanComponent | null | undefined): string {
  if (!c) return '';
  return c.component || c.type || '';
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
  context: Record<string, any>;
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
// UI-only helpers
// ---------------------------------------------------------------------------

export const STATE_LABEL: Record<ObjectiveSession['state'], string> = {
  ACTIVE: 'In progress',
  COMPLETE: 'Complete',
  CANCELLED: 'Closed',
};

/** Port of history.ts's answerText(): renders an arbitrary answer value as readable plain text. */
export function answerText(value: unknown): string {
  if (Array.isArray(value)) return value.map(answerText).join(' · ');
  if (value && typeof value === 'object') {
    return Object.entries(value as object).map(([k, v]) => `${k}: ${answerText(v)}`).join('\n');
  }
  return value === null || value === undefined ? 'Not sure' : String(value);
}
