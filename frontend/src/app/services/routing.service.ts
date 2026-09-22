import { Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';

// Functional replacement for yuzee-ai-token-lab/src/services/MicroToolRouter.ts.
//
// The embedding worker (see ../workers/embedder.worker.ts) only ranks candidates by cosine
// similarity. Gating — deciding whether a ranking is confident enough to act on — happens here,
// ported from that app's routing/bgeMatching.ts (bgeGateResult) and routing/bgeProfiles.ts
// (the needs task's score/margin thresholds). The gated result is never trusted on its own: it is
// POSTed to /api/routing/validate, and only an {accepted:true} response is acted on.
//
// ponytail: retrieveObjectives() and reviewMiniPathway() from the old orchestrator are not
// ported — nothing in this app's composer/chat-area calls them yet. The worker still answers
// 'pathway' ranking requests (it mirrors the old worker faithfully); add a reviewMiniPathway()
// method here, the same way routeMessage() is built, once something needs it.

type Candidate = { toolId: string; score: number };
type NeedCandidate = { id: string; score: number };
type Gate = { score: number; margin: number; domainMargin: number };
type BgeCalibration = { version: string; route: Gate; topic: Gate; suggestion: Gate };
type Artifact = { modelId: string; revision: string };

const MODEL_ID = 'Xenova/bge-small-en-v1.5';
const OUT_OF_SCOPE = '__OUT_OF_SCOPE__';
const CF_MODEL_ID = 'cloudflare/llama-3.1-8b-fast';
// routing/bgeProfiles.ts's needs profile (not calibrated via JSON in the old app either).
const NEEDS_GATE = { score: 0.55, margin: 0.01, clarifyScore: 0.6, clarifyMargin: 0.04 };

export type RoutingTask = 'route' | 'topic' | 'suggestion' | 'needs' | 'pathway';
export type RouteResult = { toolId: string; scopedInstruction: string };
export type NeedsResult = { need: 'answer' | 'clarify' | 'research'; instruction: string };

/** routing/bgeMatching.ts's bgeGateResult, unchanged. */
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

/** routing/bgeProfiles.ts's taskRanking/rankNeeds/needGate, condensed into one function. */
function rankNeeds(candidates: NeedCandidate[]): { selected: boolean; reason: string; id?: string; score?: number; margin?: number } {
  const rank = (gate: { score: number; margin: number }) => {
    const ranked = candidates.filter(c => ['answer', 'clarify', 'research', 'other'].includes(c.id) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1)
      .sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.id === c.id) === i);
    const [first, second] = ranked;
    if (!second) return { selected: false, reason: 'incomplete-ranking' } as const;
    const margin = first.score - second.score;
    if (first.id === 'other') return { selected: false, id: first.id, score: first.score, margin, reason: 'background-wins' };
    if (first.score < gate.score) return { selected: false, score: first.score, margin, reason: 'min-similarity' };
    if (margin < gate.margin) return { selected: false, score: first.score, margin, reason: 'top2-margin' };
    return { selected: true, id: first.id, score: first.score, margin, reason: 'clear-semantic-match' };
  };
  const initial = rank(NEEDS_GATE);
  return initial.id === 'clarify' ? rank({ score: NEEDS_GATE.clarifyScore, margin: NEEDS_GATE.clarifyMargin }) : initial;
}

@Injectable({ providedIn: 'root' })
export class RoutingService {
  private readonly _status = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly status = this._status.asReadonly();

  private worker: Worker | null = null;
  private warmupTimer?: ReturnType<typeof setTimeout>;
  private nextId = 0;
  private pendingCandidates = new Map<string, (candidates: { toolId?: string; id?: string; score: number }[] | null) => void>();
  private pendingSections = new Map<string, (rankings: Candidate[][] | null) => void>();
  private artifactPromise?: Promise<Artifact>;
  private calibrationPromise?: Promise<BgeCalibration>;

  constructor(private api: ApiService) {}

  startWarmup(): void {
    if (this._status() === 'loading' || this._status() === 'ready') return;
    if (typeof Worker === 'undefined') { this._status.set('error'); return; }
    this._status.set('loading');
    try {
      this.worker = new Worker(new URL('../workers/embedder.worker.ts', import.meta.url), { type: 'module' });
      this.worker.onmessage = event => this.handleWorkerMessage(event.data);
      this.worker.onerror = () => this.teardownWorker();
      this.warmupTimer = setTimeout(() => this.teardownWorker(), 120000);
      this.worker.postMessage({ type: 'init', modelId: MODEL_ID });
    } catch { this._status.set('error'); }
  }

  /** Local BGE ranking + gating, then server re-validation. Falls back to the Cloudflare LLM
   * router (POST /api/routing/llm) when the embedding worker is unavailable or still warming up. */
  async routeMessage(text: string, options: { topic?: boolean } = {}): Promise<RouteResult | null> {
    if (!text.trim() || text.length > 1800) return null;
    const task: 'route' | 'topic' = options.topic ? 'topic' : 'route';
    const ranked = await this.rankViaWorker(task, text);
    if (ranked && ranked.length) {
      const calibration = await this.loadCalibration();
      const gate = bgeGateResult(ranked as Candidate[], calibration[task]);
      if (!gate.selected) return null;
      const artifact = await this.modelInfo();
      return this.validate({ toolId: gate.toolId!, score: gate.score!, margin: gate.margin!, domainMargin: gate.domainMargin!, modelId: artifact.modelId, modelRevision: artifact.revision, task });
    }
    if (task !== 'route') return null; // the Cloudflare fallback only ever supported 'route'/'needs'
    const cf = await this.cfRoute('route', text);
    if (!cf?.toolId) return null;
    return this.validate({ toolId: cf.toolId, score: cf.score ?? 0, margin: 1, domainMargin: 1, modelId: CF_MODEL_ID, modelRevision: 'cf-llm-v1', task });
  }

  /** Pre-generation needs classification: does this turn need a plain answer, a clarifying
   * question, or a sourced research lookup? conversationHistory is accepted for parity with the
   * old MicroToolRouter contract; ponytail: short-query history resolution (conversationQuery.ts)
   * isn't ported, so it is currently unused — wire it in if abbreviated follow-ups need it. */
  async assessMessageNeeds(text: string, conversationHistory: ReadonlyArray<{ role: string; content: string }> = []): Promise<NeedsResult | null> {
    void conversationHistory;
    if (!text.trim() || text.length > 1800) return null;
    const ranked = await this.rankViaWorker('needs', text);
    if (ranked && ranked.length) {
      const candidates = ranked as NeedCandidate[];
      const gate = rankNeeds(candidates);
      if (!gate.selected || !gate.id) return null;
      const otherScore = candidates.find(c => c.id === 'other')?.score ?? gate.score!;
      const artifact = await this.modelInfo();
      const result = await this.validate({ toolId: gate.id, score: gate.score!, margin: gate.margin!, domainMargin: gate.score! - otherScore, modelId: artifact.modelId, modelRevision: artifact.revision, task: 'needs' });
      return result ? { need: gate.id as NeedsResult['need'], instruction: result.scopedInstruction } : null;
    }
    const cf = await this.cfRoute('needs', text);
    if (!cf?.kind) return null;
    const result = await this.validate({ toolId: cf.kind, score: cf.score ?? 0, margin: 1, domainMargin: 1, modelId: CF_MODEL_ID, modelRevision: 'cf-llm-v1', task: 'needs' });
    return result ? { need: cf.kind as NeedsResult['need'], instruction: result.scopedInstruction } : null;
  }

  /** Runs after a response completes, to offer a follow-up skill. ponytail: the old
   * selectSkillOffers() built a menu of up to 3 offers across response sections; this returns
   * only the single best-gated section. Add multi-offer merging back if the UI needs a menu. */
  async reviewResponseSkills(text: string): Promise<RouteResult | null> {
    if (!text.trim() || text.length > 30000) return null;
    const rankings = await this.rankSectionsViaWorker(text);
    if (!rankings || !rankings.length) return null;
    const calibration = await this.loadCalibration();
    let best: ReturnType<typeof bgeGateResult> | null = null;
    for (const ranking of rankings) {
      const gate = bgeGateResult(ranking, calibration.suggestion);
      if (gate.selected && (!best || gate.score! > best.score!)) best = gate;
    }
    if (!best) return null;
    const artifact = await this.modelInfo();
    return this.validate({ toolId: best.toolId!, score: best.score!, margin: best.margin!, domainMargin: best.domainMargin!, modelId: artifact.modelId, modelRevision: artifact.revision, task: 'suggestion' });
  }

  private handleWorkerMessage(m: any): void {
    if (m?.type === 'ready') { clearTimeout(this.warmupTimer); this._status.set('ready'); return; }
    if (m?.type === 'unavailable') { this.teardownWorker(); return; }
    if (m?.type === 'progress') return;
    if (m?.type === 'suggest-result') { this.pendingSections.get(m.id)?.(m.rankings ?? []); this.pendingSections.delete(m.id); return; }
    if (m?.type === 'result' || m?.type === 'needs-result' || m?.type === 'pathway-result') {
      this.pendingCandidates.get(m.id)?.(m.candidates ?? []);
      this.pendingCandidates.delete(m.id);
      return;
    }
    if (m?.type === 'abstained' || m?.type === 'error') {
      this.pendingCandidates.get(m.id)?.(null); this.pendingCandidates.delete(m.id);
      this.pendingSections.get(m.id)?.(null); this.pendingSections.delete(m.id);
    }
  }

  private teardownWorker(): void {
    clearTimeout(this.warmupTimer);
    this.worker?.terminate();
    this.worker = null;
    for (const resolve of this.pendingCandidates.values()) resolve(null);
    this.pendingCandidates.clear();
    for (const resolve of this.pendingSections.values()) resolve(null);
    this.pendingSections.clear();
    this._status.set('error');
  }

  private rankViaWorker(task: 'route' | 'topic' | 'needs' | 'pathway', text: string, timeoutMs = 1500): Promise<{ toolId?: string; id?: string; score: number }[] | null> {
    if (this._status() === 'idle') this.startWarmup();
    if (this._status() !== 'ready' || !this.worker) return Promise.resolve(null);
    const id = String(++this.nextId);
    return new Promise(resolve => {
      const timer = setTimeout(() => { this.pendingCandidates.delete(id); resolve(null); }, timeoutMs);
      this.pendingCandidates.set(id, candidates => { clearTimeout(timer); resolve(candidates); });
      try { this.worker!.postMessage({ type: task, id, text }); } catch { clearTimeout(timer); this.pendingCandidates.delete(id); resolve(null); }
    });
  }

  private rankSectionsViaWorker(text: string, timeoutMs = 20000): Promise<Candidate[][] | null> {
    if (this._status() === 'idle') this.startWarmup();
    if (this._status() !== 'ready' || !this.worker) return Promise.resolve(null);
    const id = 'suggest-' + String(++this.nextId);
    return new Promise(resolve => {
      const timer = setTimeout(() => { this.pendingSections.delete(id); resolve(null); }, timeoutMs);
      this.pendingSections.set(id, rankings => { clearTimeout(timer); resolve(rankings); });
      try { this.worker!.postMessage({ type: 'suggest', id, text }); } catch { clearTimeout(timer); this.pendingSections.delete(id); resolve(null); }
    });
  }

  private async modelInfo(): Promise<Artifact> {
    if (!this.artifactPromise) this.artifactPromise = fetch('/assets/routing/bgeArtifact.json').then(r => r.json());
    return this.artifactPromise;
  }

  private async loadCalibration(): Promise<BgeCalibration> {
    if (!this.calibrationPromise) this.calibrationPromise = fetch('/assets/routing/bgeCalibration.json').then(r => r.json());
    return this.calibrationPromise;
  }

  /** Ports MicroToolRouter.ts's cfRoute(): a server-side LLM fallback for when the browser can't
   * run the embedding model at all (Worker unsupported, model load failed, or still warming up). */
  private async cfRoute(task: 'route' | 'needs', text: string): Promise<{ toolId?: string; kind?: string; score?: number } | null> {
    try {
      const res = await firstValueFrom(this.api.post<{ result: any }>('/routing/llm', { task, text }));
      return res?.result ?? null;
    } catch { return null; }
  }

  private async validate(payload: { toolId: string; score: number; margin: number; domainMargin: number; modelId: string; modelRevision: string; task: RoutingTask }): Promise<{ toolId: string; scopedInstruction: string } | null> {
    try {
      const res = await firstValueFrom(this.api.post<{ accepted: boolean; reason: string | null; scopedInstruction: string | null }>('/routing/validate', payload));
      return res?.accepted ? { toolId: payload.toolId, scopedInstruction: res.scopedInstruction ?? '' } : null;
    } catch { return null; }
  }
}
