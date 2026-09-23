import { Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { choosePathwayHint, type PathwayHint } from '../components/mini-pathway/policy';
import type { SkillOffer, SkillReview } from '../utils/chat-helpers';
import { objectiveShortlist, type ObjectiveMatch, type ObjectiveCatalogueItem } from '../components/objectives/objectives.types';
import catalogue from '../components/objectives/catalogue.json';
import artifact from '../routing/bgeArtifact.json';
import bgeCalibration from '../routing/bgeCalibration.json';
import registry from '../routing/microtools.json';

// Port of yuzee-ai-token-lab/src/services/MicroToolRouter.ts plus the browser-side parts of
// routing/models.ts, policy.ts, skillSuggestions.ts, turnNeeds.ts, conversationQuery.ts,
// bgeProfiles.ts, bgeMatching.ts, bgeContract.ts and oala/invocation.ts.
// The worker (../workers/embedder.worker.ts) ranks; gating happens here, exactly as the original.

// ---- routing/models.ts ----
export const ROUTER_MODEL_KEY = 'oala-router-model';
const BGE_MODEL_ID = 'Xenova/bge-small-en-v1.5';
const CF_MODEL_ID = 'cloudflare/llama-3.1-8b-fast';
const ROUTER_MODEL_IDS = ['Xenova/all-MiniLM-L6-v2', 'Xenova/all-MiniLM-L12-v2', BGE_MODEL_ID];
export const RUNTIME_ROUTER_MODELS = [
  { id: BGE_MODEL_ID, label: 'BGE-small', revision: 'ea104dacec62c0de699686887e3f920caeb4f3e3', pooling: 'cls', tokenBudget: 512, description: 'Calibrated skill matching · 512-token input window' },
  { id: CF_MODEL_ID, label: 'Llama 3.1-8b Fast', description: 'Server-side LLM routing via Cloudflare Workers AI · requires CLOUDFLARE_API_TOKEN' },
] as const;
export const DEFAULT_ROUTER_MODEL = BGE_MODEL_ID;

// ---- types ----
export type RouterStatus = 'idle' | 'loading' | 'ready' | 'unavailable';
export type Candidate = { toolId: string; score: number };
export type RoutingFlow = 'route' | 'topic' | 'suggestion';
export type RoutingDecision = { modelId?: string; calibrationVersion?: string; routingFlow?: RoutingFlow; domainMargin?: number; status: 'selected' | 'abstained'; toolId?: string; score?: number; margin?: number; reason: string; version: string; latencyMs?: number };
export type NeedKind = 'answer' | 'clarify' | 'research';
export type NeedHint = { status: 'selected' | 'abstained'; kind?: NeedKind; score?: number; margin?: number; reason: string; modelId?: string; profileVersion?: string; failedGates?: string[] };
export type RoutingHistory = readonly { role: string; content: string }[];
type NeedsHistory = readonly { role: string; content: string; preflight?: any; telemetry?: { preflight?: any } }[];
type Gate = { score: number; margin: number; domainMargin: number };
type Tool = { id: string; name: string; purpose: string };

// ---- oala/invocation.ts ----
/** Explicit, per-message addressing. Quoted mentions, email addresses and old turns do not activate Oala. */
export function parseOalaMention(value: unknown): { active: boolean; message: string } {
  const text = typeof value === 'string' ? value : '';
  const match = text.match(/^\s*@\s*oala(?=$|\s|[:,!?])[:,!?]?\s*/i);
  return { active: !!match, message: match ? text.slice(match[0].length).trim() : text };
}

// ---- routing/policy.ts ----
export const ROUTER_VERSION = 'minilm-guarded-v1';
const OUT_OF_SCOPE = '__OUT_OF_SCOPE__';
const MIN_SCORE = 0.48;
const MIN_MARGIN = 0.06;
// These catalogue entries describe internal control operations, not user-facing answers.
const internalOnly = new Set(['CORE_001', 'CORE_002']);
const eligibleTools: Tool[] = (registry as Tool[]).filter(t => !internalOnly.has(t.id));
export const abstain = (reason: string): RoutingDecision => ({ status: 'abstained', reason, version: ROUTER_VERSION });

export function routingSkipReason(text: string, structuredAnswer = false): string | null {
  const s = text.trim();
  if (structuredAnswer) return 'structured-answer';
  if (!s || s.length > 1800) return 'input-length';
  if (!/[a-z]/i.test(s) || /[^\u0000-ɏ -⁯]/.test(s)) return 'language-or-symbols';
  if (/^(hi|hello|hey|thanks|thank you|yes|no|okay|ok|sure|not sure|i[’']?m not sure)[.!\s]*$/i.test(s)) return 'conversation';
  if (/^actually\b/i.test(s) || /\b(pause|cancel|forget|ignore|instead|rather than|do not|don[’']t|not interested|not looking)\b/i.test(s) || /^(?:please\s+)?stop\b|\bstop (?:suggesting|searching|asking|the|this|that)\b/i.test(s)) return 'correction-or-boundary';
  if (/\b(kill myself|suicid|self.harm|hurt myself|emergency)\b/i.test(s)) return 'sensitive-boundary';
  if (/^(more|tell me more|go on|continue|what about that|what about this|what about (?:that|this|the second) one|does (?:that|this) qualify me|compare (?:them|these|those)|why|how much|what next)[?.!\s]*$/i.test(s)) return 'needs-context';
  if (s.split(/\s+/).length < 4) return 'needs-context';
  if ((s.match(/\?/g) || []).length > 1) return 'multiple-questions';
  return null;
}

// ---- routing/conversationQuery.ts ----
const resetTopic = /\b(?:new topic|different topic|forget that|instead|not that course|cancel|stop)\b/i;
const subjectRe = /\b(?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)\b[^\n.!?;]{2,180}/gi;
export function resolveShortQuery(request: string, history: RoutingHistory): string | null {
  const s = request.trim();
  if (s.length > 160 || s.split(/\s+/).length > 12 || !history.length) return null;
  const intent = /^(?:what about (?:the )?costs?|(?:course )?(?:costs?|fees)|how much)[?.!\s]*$/i.test(s) ? 'Explain course tuition, funding and additional costs' :
    /^(?:what jobs|what about jobs)[?.!\s]*$/i.test(s) ? 'Explain career outcomes and jobs after this course' :
    /^(?:quality|course quality)[?.!\s]*$/i.test(s) ? 'Explain how to evaluate this course and provider quality' :
    /^(?:compare (?:them|these|those)|compare (?:the )?two)[?.!\s]*$/i.test(s) ? 'Compare these two courses for the user' :
    /^(?:does (?:that|this) qualify me)[?.!\s]*$/i.test(s) ? 'Explain eligibility and qualification requirements; identify missing evidence' :
    /^(?:go deeper|tell me more|more details|explain (?:it|that|this)|why)[?.!\s]*$/i.test(s) ? 'Explain the current course topic in more detail, with examples' : null;
  if (!intent) return null;
  const turns = history.filter(m => m.role === 'user').slice(-3).reverse();
  for (const turn of turns) {
    const content = turn.content.replace(/^@oala\s*/i, '').trim();
    if (resetTopic.test(content)) return null;
    if (content.length > 700) return null; // Do not truncate away a correction in a long turn.
    const matches = [...content.matchAll(subjectRe)].map(m => m[0].trim());
    if (!matches.length) { if (content.split(/\s+/).length >= 4) return null; continue; }
    // Each qualification marker counts as an object, even if joined by "and".
    const count = (content.match(/\b(?:Bachelor|Master|Diploma|Certificate)\b/gi) || []).length;
    const comparing = intent.startsWith('Compare');
    if ((comparing && count !== 2) || (!comparing && count !== 1)) return null;
    return `${intent}.\nUser's request: ${s}\nUser-provided subject and constraints: ${content}`;
  }
  return null;
}

export function routingInput(text: string, history: RoutingHistory = [], structured = false): { text: string; skip: string | null; resolved: boolean } {
  const skip = routingSkipReason(text, structured);
  if (skip !== 'needs-context') return { text, skip, resolved: false };
  const query = resolveShortQuery(text, history);
  return query ? { text: query, skip: null, resolved: true } : { text, skip, resolved: false };
}

/** routing/bgeMatching.ts bgeGateResult. */
function bgeGateResult(candidates: Candidate[], gate: Gate): { selected: boolean; reason: string; toolId?: string; score?: number; margin?: number; domainMargin?: number } {
  const valid = candidates.filter(c => Number.isFinite(c.score) && c.score >= -1 && c.score <= 1);
  const domain = valid.find(c => c.toolId === OUT_OF_SCOPE);
  const ranked = valid.filter(c => c.toolId !== OUT_OF_SCOPE).sort((a, b) => b.score - a.score)
    .filter((c, i, a) => a.findIndex(x => x.toolId === c.toolId) === i);
  if (ranked.length < 2 || !domain) return { selected: false, reason: 'incomplete-ranking' };
  const [a, b] = ranked, margin = a.score - b.score, domainMargin = a.score - domain.score;
  const reason = a.score < gate.score ? 'low-similarity' : domainMargin < gate.domainMargin ? 'outside-scope' : margin < gate.margin ? 'ambiguous' : 'clear-semantic-match';
  return { selected: reason === 'clear-semantic-match', reason, toolId: a.toolId, score: a.score, margin, domainMargin };
}

// ---- routing/bgeProfiles.ts / bgeContract.ts ----
const NEEDS_PROFILE = { score: 0.55, margin: 0.01, clarifyScore: 0.60, clarifyMargin: 0.04, version: 'bge-input-needs-v3' };
const BGE_RELEASE = 'bge-single-encoder-v2';
const bgeReadyContract = { modelId: artifact.modelId, revision: artifact.revision, tokenBudget: artifact.maxTokens, pooling: artifact.pooling, dimensions: artifact.dimensions, dtype: artifact.dtype, artifact: artifact.artifact, release: BGE_RELEASE };
function validBgeReady(value: unknown) { const m = value as any; return !!m && Object.entries(bgeReadyContract).every(([k, v]) => m[k] === v); }
function taskRanking(candidates: { id: string; score: number }[], allowed: readonly string[], gate: { score: number; margin: number }) {
  const ranking = candidates.filter(c => allowed.includes(c.id) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1)
    .sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.id === c.id) === i);
  const [first, second] = ranking;
  const failedGates = !second ? ['incomplete-ranking'] : [...(first.score < gate.score ? ['min-similarity'] : []), ...(first.score - second.score < gate.margin ? ['top2-margin'] : []), ...(first.id === 'other' ? ['background-wins'] : [])];
  return { ranking, failedGates, selected: failedGates.length === 0, id: first?.id, score: first?.score, margin: second ? first.score - second.score : undefined };
}
function rankNeeds(candidates: { id: string; score: number }[]) {
  const allowed = ['answer', 'clarify', 'research', 'other'];
  const initial = taskRanking(candidates, allowed, NEEDS_PROFILE);
  return initial.id === 'clarify' ? taskRanking(candidates, allowed, { score: NEEDS_PROFILE.clarifyScore, margin: NEEDS_PROFILE.clarifyMargin }) : initial;
}

// ---- routing/turnNeeds.ts ----
// Only the ids of turnNeeds.ts needScenarios are read here.
const NEED_SCENARIO_IDS = ['answer', 'clarify', 'research'];
export function chooseNeed(candidates: { id: string; score: number }[], modelId?: string): NeedHint {
  if (modelId === BGE_MODEL_ID) {
    const r = rankNeeds(candidates);
    return { status: r.selected ? 'selected' : 'abstained', kind: r.selected ? r.id as NeedKind : undefined, score: r.score, margin: r.margin, reason: r.selected ? 'semantic-match' : 'uncertain', modelId, profileVersion: NEEDS_PROFILE.version, failedGates: r.failedGates };
  }
  const ranked = candidates.filter(c => NEED_SCENARIO_IDS.includes(c.id) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1)
    .sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.id === c.id) === i);
  if (ranked.length < 2) return { status: 'abstained', reason: 'incomplete-ranking' };
  const [a, b] = ranked, margin = a.score - b.score;
  if (a.score < .5 || margin < .08) return { status: 'abstained', reason: 'uncertain' };
  return { status: 'selected', kind: a.id as NeedKind, score: a.score, margin, reason: 'semantic-match' };
}
function explicitTarget(text: string): string {
  // Only explicit user wording is copied. No assistant claims are promoted to scope.
  const labelled = text.match(/(?:^|\n)\s*(?:course|qualification|option)\s*:\s*([^\n?!.]{3,250})/i)?.[1];
  if (labelled) return labelled.trim();
  const named = text.match(/\b((?:Bachelor|Master|Diploma|Certificate|Graduate Certificate|Graduate Diploma)[\w\s'’&()-]{2,110}?\s+(?:at|from)\s+)([^\n?.!,;]{2,140})/i);
  if (!named) return '';
  const provider = named[2].split(/\s+(?:in\s+20\d\d|for|cost|costs|fees|with|while|but|and I|because|please)\b/i)[0].trim();
  return provider ? `${named[1]}${provider}`.trim() : '';
}
const needsBoundary = /\b(stop|pause|cancel|no more|don['’]t search|do not search|no research|never mind|suicid\w*|self.harm|emergency)\b/i;
const freshTopic = /\b(?:instead|different (?:course|topic)|new topic|forget (?:that|the course)|not (?:that|this) course)\b/i;
/** assessTurnNeeds({text,history}).scope.target — the only part needsQuery reads (same early returns). */
function turnNeedsTarget(text: string, history: NeedsHistory): string {
  if (needsBoundary.test(text) || /Research reference: [a-f0-9-]{36}/.test(text)) return '';
  if (!text.trim() || text.length > 1800) return '';
  if (/^actually\b|^correction\b/i.test(text) && !explicitTarget(text)) return '';
  if ((text.match(/\?/g) || []).length > 1 || /\b(compare|versus)\b/i.test(text)) return '';
  if (/\b(calculate|add|total|sum)\b/i.test(text) && /\d/.test(text)) return '';
  if (/^what (?:is|are) (?:an? |the )?(?:entry requirements?|tuition fees?|scholarships?|accreditation)[?.!\s]*$/i.test(text)) return '';
  if (/^(?:hi|hello|thanks|thank you|yes|no|okay|not sure)[.!\s]*$/i.test(text) || /\b(?:what (?:does yuzee|services)|who are you|what is yuzee)\b/i.test(text)) return '';
  const previous = history.at(-1);
  const pending = previous?.role === 'assistant' ? (previous.preflight || previous.telemetry?.preflight) : undefined;
  const continuation = pending?.action === 'clarify' && pending.missing.includes('course-and-provider') && !freshTopic.test(text) && !!explicitTarget(text);
  const question = continuation ? pending.question : text;
  const ownTarget = explicitTarget(text);
  // Only carry a previous target for an explicit referent, not into an unrelated topic.
  const referent = /\b(this|that|it|same|the course|these units)\b/i.test(question) || continuation;
  let previousTarget = '';
  if (referent && !freshTopic.test(text)) {
    for (const message of history.filter(m => m.role === 'user').slice(-3).reverse()) {
      if (freshTopic.test(message.content)) break;
      previousTarget = explicitTarget(message.content);
      if (previousTarget) break;
    }
  }
  return ownTarget || previousTarget;
}
/** Add only a resolved user-stated course, not a transcript or inferred personal profile. */
export function needsQuery(text: string, history: NeedsHistory): string {
  const current = parseOalaMention(text).message;
  const target = turnNeedsTarget(current, history);
  if (!target || current.includes(target)) return current;
  const query = `Current question: ${current}\nUser-stated course: ${target}`;
  return query.length <= 1800 ? query : current;
}

// ---- routing/skillSuggestions.ts ----
const SKILL_LABELS: Record<string, string> = { COURSE_011: 'Understand my study costs', COURSE_012: 'Explore study and placement commitments', CORE_010: 'Check the supporting evidence', CAREER_004: 'Plan my next career step' };
export const noSkills = (reason: string): SkillReview => ({ status: 'abstained', offers: [], reason });
/** Navigation menus offer tasks; intake/eligibility/preferences questions remain Quiz answers. */
export function isTopicMenu(interaction: any): boolean {
  return interaction?.kind === 'question' && interaction.input_type === 'single_select' &&
    /(?:which (?:aspect|topic|area)|what would you like to (?:explore|focus|learn)|focus on next|explore next)/i.test(interaction.question || '') &&
    Array.isArray(interaction.options) && interaction.options.length > 0;
}
export function selectedTopic(interaction: any, event: any): string {
  if (!isTopicMenu(interaction)) return '';
  const selection = event?.userEvent?.interaction || event?.interaction;
  if (selection?.selected_option_ids?.length !== 1 || selection.self_input) return '';
  const option = interaction.options.find((o: any) => o.id === selection.selected_option_ids[0]);
  return option ? [option.label, option.description].filter(Boolean).join('. ') : '';
}

type Finish<T> = { finish: (value: T) => void };

@Injectable({ providedIn: 'root' })
export class RoutingService {
  private readonly _status = signal<RouterStatus>('idle');
  readonly status = this._status.asReadonly();
  private readonly _model = signal<string>(DEFAULT_ROUTER_MODEL);
  /** The selected router model id (original getRouterModel()). */
  readonly selectedModel = this._model.asReadonly();

  private worker: Worker | null = null;
  private warmupTimer?: ReturnType<typeof setTimeout>;
  private nextId = 0;
  private readonly objectivePending = new Map<string, Finish<ObjectiveMatch[]>>();
  private readonly suggestPending = new Map<string, Finish<SkillReview>>();
  private readonly needPending = new Map<string, Finish<NeedHint>>();
  private readonly pending = new Map<string, Finish<RoutingDecision> & { flow: RoutingFlow }>();
  private readonly pathwayPending = new Map<string, Finish<PathwayHint>>();
  /** routing/policy.ts eligibleTools. */
  readonly eligibleTools = eligibleTools;

  constructor(private api: ApiService) {
    // Migrate saved L6/L12 choices to default; preserve BGE and Cloudflare selections.
    try {
      const saved = localStorage.getItem(ROUTER_MODEL_KEY);
      if (saved && RUNTIME_ROUTER_MODELS.some(m => m.id === saved)) this._model.set(saved);
      else localStorage.setItem(ROUTER_MODEL_KEY, DEFAULT_ROUTER_MODEL);
      if (this._model() === CF_MODEL_ID) this.setStatus('ready');
    } catch { /* Storage can be disabled. */ }
  }

  private get isCFModel(): boolean { return this._model() === CF_MODEL_ID; }

  getRouterModel(): string { return this._model(); }

  private readonly listeners = new Set<(status: RouterStatus) => void>();
  /** Called immediately with the current status, then on every change (including a brief 'unavailable'). */
  onRouterStatus(callback: (status: RouterStatus) => void): () => void {
    this.listeners.add(callback); callback(this._status());
    return () => { this.listeners.delete(callback); };
  }
  private setStatus(value: RouterStatus): void { this._status.set(value); this.listeners.forEach(cb => cb(value)); }

  setRouterModel(id: string): boolean {
    if (!RUNTIME_ROUTER_MODELS.some(m => m.id === id)) return false;
    if (this._model() === id && (this._status() === 'loading' || this._status() === 'ready')) return true;
    this._model.set(id);
    try { localStorage.setItem(ROUTER_MODEL_KEY, id); } catch { /* The in-memory choice still works. */ }
    if (id === CF_MODEL_ID) {
      clearTimeout(this.warmupTimer); this.worker?.terminate(); this.worker = null;
      this.setStatus('ready'); // Server-side model — always ready once selected
      return true;
    }
    this.unavailable(); this.startWarmup(); return true;
  }

  private unavailable(): void {
    for (const { finish } of [...this.objectivePending.values()]) finish([]);
    clearTimeout(this.warmupTimer); this.worker?.terminate(); this.worker = null; this.setStatus('unavailable');
    for (const { finish } of [...this.suggestPending.values()]) finish(noSkills('unavailable'));
    for (const { finish } of [...this.pending.values()]) finish(abstain('unavailable'));
    for (const { finish } of [...this.needPending.values()]) finish({ status: 'abstained', reason: 'unavailable' });
    for (const { finish } of [...this.pathwayPending.values()]) finish({ status: 'abstained', reason: 'unavailable' });
  }

  startWarmup(): void {
    if (this._status() === 'loading' || this._status() === 'ready') return;
    if (this.isCFModel) { this.setStatus('ready'); return; }
    if (typeof Worker === 'undefined') { this.setStatus('unavailable'); return; }
    this.setStatus('loading');
    try {
      const worker = new Worker(new URL('../workers/embedder.worker.ts', import.meta.url), { type: 'module' });
      this.worker = worker;
      const source = worker;
      worker.onmessage = event => {
        if (this.worker !== source) return;
        const m = event.data;
        const cands = Array.isArray(m?.candidates) ? m.candidates : [];
        if (m?.type === 'ready') { if (!validBgeReady(m)) { this.unavailable(); return; } clearTimeout(this.warmupTimer); this.setStatus('ready'); }
        else if (m?.type === 'objectives-result') this.objectivePending.get(m.id)?.finish(objectiveShortlist(cands, catalogue.objectives as ObjectiveCatalogueItem[]));
        else if (this.objectivePending.has(m?.id) && ['abstained', 'error'].includes(m?.type)) this.objectivePending.get(m.id)?.finish([]);
        else if (m?.type === 'pathway-result') this.pathwayPending.get(m.id)?.finish(choosePathwayHint(cands, this._model()));
        else if (this.pathwayPending.has(m?.id) && ['abstained', 'error'].includes(m?.type)) this.pathwayPending.get(m.id)?.finish({ status: 'abstained', reason: m.type === 'error' ? 'inference-failed' : 'token-budget' });
        else if (m?.type === 'unavailable') this.unavailable();
        else if (m?.type === 'suggest-result') this.suggestPending.get(m.id)?.finish(this.selectSkillOffers(Array.isArray(m.rankings) ? m.rankings : [], this._model()));
        else if (this.suggestPending.has(m?.id) && ['abstained', 'error'].includes(m?.type)) this.suggestPending.get(m.id)?.finish(noSkills(m.type === 'error' ? 'inference-failed' : 'token-budget'));
        else if (m?.type === 'needs-result') this.needPending.get(m.id)?.finish(chooseNeed(cands, this._model()));
        else if (this.needPending.has(m?.id) && ['abstained', 'error'].includes(m?.type)) this.needPending.get(m.id)?.finish({ status: 'abstained', reason: m.type === 'error' ? 'inference-failed' : 'token-budget' });
        else if (m?.type === 'abstained') this.pending.get(m.id)?.finish(abstain(['token-budget', 'busy', 'input-length'].includes(m.reason) ? m.reason : 'inference-failed'));
        else if (m?.type === 'result') this.pending.get(m.id)?.finish(this.chooseRoute(cands, this._model(), this.pending.get(m.id)?.flow || 'route'));
        else if (m?.type === 'error') this.pending.get(m.id)?.finish(abstain('inference-failed'));
      };
      worker.onerror = () => { if (this.worker === source) this.unavailable(); };
      this.warmupTimer = setTimeout(() => this.unavailable(), 120000);
      worker.postMessage({ type: 'init', modelId: this._model() });
    } catch { this.unavailable(); }
  }

  /** routing/policy.ts chooseRoute. */
  private chooseRoute(candidates: Candidate[], modelId: string, flow: RoutingFlow): RoutingDecision {
    if (!ROUTER_MODEL_IDS.includes(modelId)) return abstain('unknown-model');
    if (modelId === BGE_MODEL_ID) {
      const d = bgeGateResult(candidates.filter(c => c.toolId === OUT_OF_SCOPE || eligibleTools.some(t => t.id === c.toolId)), bgeCalibration[flow]);
      const metadata = { modelId, calibrationVersion: bgeCalibration.version, routingFlow: flow, score: d.score, margin: d.margin, domainMargin: d.domainMargin };
      return d.selected ? { ...metadata, status: 'selected', toolId: d.toolId, reason: d.reason, version: ROUTER_VERSION } : { ...abstain(d.reason), ...metadata };
    }
    const ranked = candidates.filter(c => eligibleTools.some(t => t.id === c.toolId) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1).sort((a, b) => b.score - a.score);
    const unique = ranked.filter((c, i) => ranked.findIndex(r => r.toolId === c.toolId) === i);
    if (unique.length < 2) return abstain('incomplete-ranking');
    const [first, second] = unique, margin = first.score - second.score;
    if (first.score < MIN_SCORE) return { ...abstain('low-similarity'), score: first.score, margin };
    if (margin < MIN_MARGIN) return { ...abstain('ambiguous'), score: first.score, margin };
    return { modelId, routingFlow: flow, status: 'selected', toolId: first.toolId, score: first.score, margin, reason: 'clear-semantic-match', version: ROUTER_VERSION };
  }

  /** routing/skillSuggestions.ts selectSkillOffers: each section must have a clear match; up to 3 offers. */
  private selectSkillOffers(rankings: Candidate[][], modelId: string): SkillReview {
    const matches = new Map<string, SkillOffer>();
    for (const ranking of rankings) {
      const d = this.chooseRoute(ranking, modelId, 'suggestion');
      const unique = ranking.filter(c => eligibleTools.some(t => t.id === c.toolId) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1).sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.toolId === c.toolId) === i);
      // An optional menu can offer two strong related matches. Automatic topic routing still abstains.
      const choices = d.status === 'selected' ? [{ toolId: d.toolId!, score: d.score! }] :
        modelId !== BGE_MODEL_ID && unique.length >= 3 && unique[1].score >= .60 && unique[1].score - unique[2].score >= .10 ? unique.slice(0, 2) : [];
      for (const c of choices) {
        const t = eligibleTools.find(t => t.id === c.toolId)!;
        if (matches.has(t.id) && matches.get(t.id)!.score >= c.score) continue;
        matches.set(t.id, { toolId: t.id, label: SKILL_LABELS[t.id] || t.name, description: t.purpose, score: c.score });
      }
    }
    const offers = [...matches.values()].sort((a, b) => b.score - a.score).slice(0, 3);
    return offers.length ? { status: 'ready', offers, reason: 'minilm-response-review' } : noSkills('no-clear-match');
  }

  private async cfRoute(task: 'route' | 'needs', text: string): Promise<any> {
    try {
      const d = await firstValueFrom(this.api.post<{ result?: any }>('/routing/llm', { task, text }));
      return d?.result ?? null;
    } catch { return null; }
  }

  /** Never delay chat for a cold model. Ready inference has a short bounded wait and cancellation. */
  routeMessage(text: string, { signal, structuredAnswer = false, timeoutMs = 1500, topic = false, history = [] }: { signal?: AbortSignal; structuredAnswer?: boolean; timeoutMs?: number; topic?: boolean; history?: RoutingHistory } = {}): Promise<RoutingDecision> {
    if (signal?.aborted) return Promise.resolve(abstain('cancelled'));
    const input = routingInput(text, history, structuredAnswer); if (input.skip) return Promise.resolve(abstain(input.skip));
    if (this.isCFModel) {
      return this.cfRoute('route', text).then((r): RoutingDecision => {
        if (!r?.toolId) return abstain('no-match');
        return { status: 'selected', toolId: r.toolId, score: r.score ?? 0, reason: r.reason ?? 'cf-llm', version: 'cf-llm-v1', modelId: CF_MODEL_ID };
      }).catch(() => abstain('inference-failed'));
    }
    if (this._status() !== 'ready' || !this.worker) { if (this._status() === 'idle') this.startWarmup(); return Promise.resolve(abstain('not-ready')); }
    const id = String(++this.nextId), started = performance.now();
    return new Promise(resolve => {
      const onAbort = () => { this.worker?.postMessage({ type: 'cancel', id }); finish(abstain('cancelled')); };
      const timer = setTimeout(() => { finish(abstain('timeout')); this.unavailable(); }, timeoutMs);
      const finish = (result: RoutingDecision) => {
        if (!this.pending.has(id)) return;
        clearTimeout(timer); signal?.removeEventListener('abort', onAbort); this.pending.delete(id);
        resolve({ ...result, latencyMs: Math.round(performance.now() - started) });
      };
      this.pending.set(id, { finish, flow: topic ? 'topic' : 'route' }); signal?.addEventListener('abort', onAbort, { once: true });
      try { this.worker!.postMessage({ type: topic ? 'topic' : 'route', id, text: input.text }); } catch { finish(abstain('unavailable')); this.unavailable(); }
    });
  }

  /** Pre-generation needs classification is independent of explicit @Oala skill injection. */
  assessMessageNeeds(text: string, { signal, timeoutMs = 1000 }: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<NeedHint> {
    const fallback = (reason: string): NeedHint => ({ status: 'abstained', reason });
    if (signal?.aborted) return Promise.resolve(fallback('cancelled'));
    if (!text.trim() || text.length > 1800) return Promise.resolve(fallback('input-length'));
    if (this.isCFModel) {
      return this.cfRoute('needs', text).then((r): NeedHint => {
        if (!r?.kind) return fallback('no-match');
        return { status: 'selected', kind: r.kind, score: r.score ?? 0, reason: r.reason ?? 'cf-llm', modelId: CF_MODEL_ID };
      }).catch(() => fallback('inference-failed'));
    }
    if (this._status() !== 'ready' || !this.worker) { if (this._status() === 'idle') this.startWarmup(); return Promise.resolve(fallback('not-ready')); }
    const id = 'needs-' + String(++this.nextId);
    return new Promise(resolve => {
      const onAbort = () => { this.worker?.postMessage({ type: 'cancel', id }); finish(fallback('cancelled')); };
      const timer = setTimeout(() => { finish(fallback('timeout')); this.unavailable(); }, timeoutMs);
      const finish = (hint: NeedHint) => { if (!this.needPending.has(id)) return; clearTimeout(timer); signal?.removeEventListener('abort', onAbort); this.needPending.delete(id); resolve(hint); };
      this.needPending.set(id, { finish }); signal?.addEventListener('abort', onAbort, { once: true });
      try { this.worker!.postMessage({ type: 'needs', id, text }); } catch { finish(fallback('unavailable')); this.unavailable(); }
    });
  }

  /** Runs after Gemini completes. This ranks topics, not factual correctness. */
  reviewResponseSkills(text: string, { signal, timeoutMs = 20000 }: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<SkillReview> {
    if (signal?.aborted) return Promise.resolve(noSkills('cancelled'));
    if (!text.trim() || text.length > 30000) return Promise.resolve(noSkills('input-length'));
    if (this._status() !== 'ready' || !this.worker) return Promise.resolve(noSkills('not-ready'));
    if (this.suggestPending.size) return Promise.resolve(noSkills('busy'));
    const id = 'suggest-' + String(++this.nextId);
    return new Promise(resolve => {
      const onAbort = () => { this.worker?.postMessage({ type: 'cancel', id }); finish(noSkills('cancelled')); };
      const timer = setTimeout(() => { this.worker?.postMessage({ type: 'cancel', id }); finish(noSkills('timeout')); }, timeoutMs);
      const finish = (result: SkillReview) => { if (!this.suggestPending.has(id)) return; clearTimeout(timer); signal?.removeEventListener('abort', onAbort); this.suggestPending.delete(id); resolve(result); };
      this.suggestPending.set(id, { finish }); signal?.addEventListener('abort', onAbort, { once: true });
      try { this.worker!.postMessage({ type: 'suggest', id, text }); } catch { finish(noSkills('unavailable')); this.unavailable(); }
    });
  }

  /** Lazy 316-catalogue retrieval using the same pinned BGE instance. */
  retrieveObjectives(text: string, signal?: AbortSignal): Promise<ObjectiveMatch[]> {
    if (signal?.aborted || this._status() !== 'ready' || !this.worker || text.length > 30000) return Promise.resolve([]);
    const id = 'objectives-' + String(++this.nextId);
    return new Promise(resolve => {
      const cancel = () => { this.worker?.postMessage({ type: 'cancel', id }); finish([]); };
      const timer = setTimeout(cancel, 90000);
      const finish = (matches: ObjectiveMatch[]) => { if (!this.objectivePending.has(id)) return; clearTimeout(timer); signal?.removeEventListener('abort', cancel); this.objectivePending.delete(id); resolve(matches); };
      this.objectivePending.set(id, { finish }); signal?.addEventListener('abort', cancel, { once: true });
      this.worker!.postMessage({ type: 'objectives', id, text });
    });
  }

  /** Dedicated pathway relevance check. It queues behind an existing worker job without changing skill routing. */
  reviewMiniPathway(text: string, { signal, timeoutMs = 25000 }: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<PathwayHint> {
    const fallback = (reason: string): PathwayHint => ({ status: 'abstained', reason });
    if (signal?.aborted) return Promise.resolve(fallback('cancelled'));
    if (!text.trim() || text.length > 1800) return Promise.resolve(fallback('input-length'));
    if (this._status() !== 'ready' || !this.worker) return Promise.resolve(fallback('not-ready'));
    if (this.pathwayPending.size) return Promise.resolve(fallback('busy'));
    const id = 'pathway-' + String(++this.nextId);
    return new Promise(resolve => {
      const onAbort = () => { this.worker?.postMessage({ type: 'cancel', id }); finish(fallback('cancelled')); };
      const timer = setTimeout(() => { this.worker?.postMessage({ type: 'cancel', id }); finish(fallback('timeout')); }, timeoutMs);
      const finish = (hint: PathwayHint) => { if (!this.pathwayPending.has(id)) return; clearTimeout(timer); signal?.removeEventListener('abort', onAbort); this.pathwayPending.delete(id); resolve(hint); };
      this.pathwayPending.set(id, { finish }); signal?.addEventListener('abort', onAbort, { once: true });
      try { this.worker!.postMessage({ type: 'pathway', id, text }); } catch { finish(fallback('unavailable')); }
    });
  }
}
