// Client-side BGE sentence-embedding router, running in a dedicated Web Worker.
//
// Ported from yuzee-ai-token-lab/src/workers/embedder.worker.ts, together with the small
// supporting modules it depended on: routing/EmbeddingQueue.ts, routing/loadEmbeddingModel.ts,
// routing/bgeContract.ts, routing/bgeDomain.ts, routing/bgeMatching.ts, routing/bgeProfiles.ts
// (task query-instruction flags only) and routing/bgeTaskContent.ts. Kept in one file because
// each of those was 10-40 lines and only this worker ever uses them.
//
// This worker only RANKS candidates (cosine similarity against a local catalogue index). Gating
// (deciding whether a ranking is confident enough to act on) and server re-validation happen on
// the main thread, in RoutingService — see that file for routing/policy.ts's bgeGateResult logic
// and the POST to /api/routing/validate.
//
// ponytail: the old worker also handled an `objectives` message type backed by a 316-item
// catalogue (objectives/routing.ts) for a separate mini-pathway objectives feature. Nothing in
// RoutingService's requested surface calls that yet, so it is not ported here — add the message
// type + its JSON index under src/assets/routing/ if/when retrieveObjectives() is needed.

const ROUTING_ASSETS_BASE = '/assets/routing';

// --- routing/bgeDomain.ts -----------------------------------------------------------------
const OUT_OF_SCOPE = '__OUT_OF_SCOPE__';
const DOMAIN_EXAMPLES = [
  'Write fiction, poetry, a bedtime story or entertainment unrelated to work or education.',
  'Give cooking instructions, a recipe or advice on preparing food at home.',
  'Explain household repairs, gardening, cleaning or caring for pets.',
  'Answer a general trivia question about geography, animals, history or sports results.',
  'Tell me the weather forecast, plan a holiday or arrange personal travel.',
  'Give personal medical treatment, diagnose symptoms or prescribe medication.',
  'Recommend personal investments, stock trades, tax strategies or legal action.',
  'Shop for a consumer product or compare personal purchases unrelated to education or work.',
  'Hello, thank you, goodbye. Acknowledge this message without suggesting a task.',
  'I want to stop, change the subject or correct something I said earlier.',
  'Refer to something unspecified: that one, explain more, what about it, which is best?',
];

// --- routing/bgeTaskContent.ts -------------------------------------------------------------
const NEED_SCENARIOS = [
  { id: 'answer', text: 'Explain a course or career term in plain language, with an example. What does it mean?' },
  { id: 'answer', text: 'Teach a practical skill. Demonstrate it, explain each step and give me an exercise to try.' },
  { id: 'answer', text: 'Explain the services Yuzee provides and how they can help me.' },
  { id: 'answer', text: 'Explain the general principles of fees, funding, admission and accreditation without checking a particular provider.' },
  { id: 'clarify', text: 'I need help choosing a suitable career or subject, but have not decided what matters to me.' },
  { id: 'clarify', text: 'Can I manage studying alongside shifts, work and family? I have not said how much time I have available.' },
  { id: 'clarify', text: 'Several occupations interest me. Help me understand my preferences before recommending a direction.' },
  { id: 'research', text: 'Look up the tuition price and additional charges for a named course and provider in the requested year.' },
  { id: 'research', text: 'Verify the current admission conditions and application closing date for a particular qualification and institution.' },
  { id: 'research', text: 'Find the official subject handbook, course accreditation and curriculum for this provider.' },
  { id: 'research', text: 'Check attendance, practical training, placement and timetable rules for this institution and course.' },
  { id: 'research', text: 'Find the clinical placement requirements and supervised practice hours needed for this qualification at this provider.' },
  { id: 'other', text: 'Hello. Thank you. Stop. Cancel this request. Change the topic.' },
  { id: 'other', text: 'Unrelated requests about food, weather, sport, travel bookings, household repairs or entertainment.' },
] as const;
const PATHWAY_SCENARIOS = [
  { id: 'pathway', text: 'Explore possible education and employment routes for someone who is uncertain about their direction.' },
  { id: 'pathway', text: 'Create a career transition plan, from my present occupation to a different kind of work.' },
  { id: 'pathway', text: 'Map the study, training and work experience stages needed to enter my chosen profession.' },
  { id: 'pathway', text: 'Compare routes into an occupation: university, vocational study, apprenticeship or direct employment.' },
  { id: 'pathway', text: 'Plan my return to employment after a career break, with realistic learning and work steps.' },
  { id: 'pathway', text: 'Help a school leaver explore future education and work directions before choosing a route.' },
  { id: 'pathway', text: 'I know my career goal. Build a sequence of milestones, learning, practice and experience to reach it.' },
  { id: 'other', text: 'Explain a single concept or teach a specific practical skill with examples and exercises.' },
  { id: 'other', text: 'Check the fees, student finance, entry rules, application documents or deadline for one course.' },
  { id: 'other', text: 'Give information about Yuzee services, my account, privacy or an application status.' },
  { id: 'other', text: 'Greetings, thanks, stop requests, recipes, weather, news, entertainment and general trivia.' },
] as const;

// Per-task query-instruction prefix, from routing/bgeProfiles.ts's bgeProfiles table.
const QUERY_INSTRUCTION: Record<string, boolean> = { route: false, topic: false, suggest: false, needs: true, pathway: false };
function bgeQuery(text: string, task: string): string {
  return (QUERY_INSTRUCTION[task] ? 'Represent this sentence for searching relevant passages: ' : '') + text;
}

// --- routing/EmbeddingQueue.ts (cooperative priority scheduler) ---------------------------
const TASK_PRIORITY = { route: 0, needs: 1, pathway: 2, topic: 3, suggest: 4 } as const;
type TaskType = keyof typeof TASK_PRIORITY;
class EmbeddingQueue {
  private jobs = new Map<string, { priority: number; order: number; work: AsyncGenerator<void, void, unknown> }>();
  private order = 0;
  private running = false;
  enqueue(id: string, type: TaskType, work: AsyncGenerator<void, void, unknown>): boolean {
    if (this.jobs.size >= 32 || this.jobs.has(id)) return false;
    this.jobs.set(id, { priority: TASK_PRIORITY[type], order: this.order++, work });
    void this.pump();
    return true;
  }
  cancel(id: string): void { this.jobs.delete(id); }
  has(id: string): boolean { return this.jobs.has(id); }
  private async pump(): Promise<void> {
    if (this.running) return;
    this.running = true;
    try {
      while (this.jobs.size) {
        const [id, job] = [...this.jobs].sort((a, b) => a[1].priority - b[1].priority || a[1].order - b[1].order)[0];
        try {
          const step = await job.work.next();
          if (step.done) this.jobs.delete(id);
        } catch { this.jobs.delete(id); }
        // Let pending postMessage/cancel events run before selecting the next chunk.
        await new Promise(resolve => setTimeout(resolve, 0));
      }
    } finally { this.running = false; }
  }
}

// --- routing/tokenBudget.ts ----------------------------------------------------------------
type Tokenizer = (text: string, options: { truncation: false; padding: false }) => { input_ids: { dims: number[] } };
function checkEmbeddingInput(tokenizer: Tokenizer, text: string, budget: number): { tokens: number; fits: boolean } {
  const result = tokenizer(text, { truncation: false, padding: false });
  const tokens = result.input_ids.dims.at(-1);
  if (!Number.isSafeInteger(tokens) || tokens! < 1) throw new Error('Invalid tokenizer result');
  return { tokens: tokens!, fits: tokens! <= budget };
}

// --- routing/skillSuggestions.ts's embeddingSections (used only by the 'suggest' task) ----
function embeddingSections(text: string, fits: (s: string) => boolean): string[] {
  if (!text.trim()) return [];
  if (fits(text)) return [text];
  if (text.length < 2) throw new Error('Unencodable section');
  let middle = Math.floor(text.length / 2);
  const space = text.lastIndexOf(' ', middle);
  if (space > middle / 2) middle = space + 1;
  return [...embeddingSections(text.slice(0, middle), fits), ...embeddingSections(text.slice(middle), fits)];
}

// --- state, populated once during initialize() ---------------------------------------------
type Artifact = { artifact: string; modelId: string; revision: string; dimensions: number; pooling: string; dtype: string; maxTokens: number };
let artifact: Artifact;
let matchingIndex: { toolId: string; text: string }[] = [];
let extractor: any;
let skillVectors: Float32Array[] = [];
let needVectors: Float32Array[] = [];
let pathwayVectors: Float32Array[] = [];
let initPromise: Promise<void> | null = null;
const cache = new Map<string, Float32Array>(); // Exact constructed query, bounded, memory only.
const queue = new EmbeddingQueue();

// --- routing/bgeContract.ts's checkedBgeVector, parameterised by the fetched artifact -------
function checkedBgeVector(values: ArrayLike<number>): Float32Array {
  if (values.length !== artifact.dimensions) throw new Error('Unexpected embedding dimensions');
  let norm = 0;
  for (const n of Array.from(values)) { if (!Number.isFinite(n)) throw new Error('Nonfinite embedding'); norm += n * n; }
  if (Math.abs(Math.sqrt(norm) - 1) > 0.002) throw new Error('Embedding must be L2-normalized');
  return Float32Array.from(values);
}

async function loadJson<T>(name: string): Promise<T> {
  const res = await fetch(`${ROUTING_ASSETS_BASE}/${name}`);
  if (!res.ok) throw new Error(`Failed to fetch ${name}`);
  return res.json() as Promise<T>;
}

// --- routing/bgeMatching.ts ------------------------------------------------------------------
function buildMatchingIndex(
  registry: { id: string; name: string; purpose: string; use_when: string; trigger_examples: string }[],
  content: { id: string; text: string }[],
): { toolId: string; text: string }[] {
  const out: { toolId: string; text: string }[] = [];
  for (const t of content) {
    const legacy = registry.find(r => r.id === t.id);
    if (!legacy) continue;
    out.push({ toolId: t.id, text: t.text });
    out.push({ toolId: t.id, text: `${legacy.name}. ${legacy.use_when} ${legacy.purpose} Examples: ${legacy.trigger_examples}` });
  }
  for (const text of DOMAIN_EXAMPLES) out.push({ toolId: OUT_OF_SCOPE, text });
  return out;
}
/** Max per ID, including a background competitor; never normalise scores across models. */
function rankBge(query: Float32Array, vectors: Float32Array[]): { toolId: string; score: number }[] {
  if (vectors.length !== matchingIndex.length) throw new Error('Incomplete BGE index');
  const scores = new Map<string, number>();
  vectors.forEach((v, i) => {
    const score = v.reduce((sum, n, j) => sum + n * query[j], 0), id = matchingIndex[i].toolId;
    scores.set(id, Math.max(scores.get(id) ?? -1, score));
  });
  const ranked = [...scores].map(([toolId, score]) => ({ toolId, score })).sort((a, b) => b.score - a.score);
  // Preserve background evidence even if it is not among the top skill matches.
  return [...ranked.filter(c => c.toolId !== OUT_OF_SCOPE).slice(0, 4), ranked.find(c => c.toolId === OUT_OF_SCOPE)!];
}
const dot = (a: Float32Array, b: Float32Array) => a.reduce((s, n, j) => s + n * b[j], 0);

async function encode(text: string): Promise<Float32Array> {
  const cached = cache.get(text);
  if (cached) { cache.delete(text); cache.set(text, cached); return cached; }
  if (!checkEmbeddingInput(extractor.tokenizer, text, artifact.maxTokens).fits) throw new Error('token-budget');
  const q = checkedBgeVector((await extractor(text, { pooling: 'cls', normalize: true })).data);
  cache.set(text, q);
  if (cache.size > 64) cache.delete(cache.keys().next().value!);
  return q;
}
async function index(texts: string[]): Promise<Float32Array[]> {
  const result: Float32Array[] = [];
  for (let i = 0; i < texts.length; i += 12) {
    const batch = texts.slice(i, i + 12);
    if (batch.some(text => !checkEmbeddingInput(extractor.tokenizer, text, 512).fits)) throw new Error('Prototype exceeds token budget');
    const out = await extractor(batch, { pooling: 'cls', normalize: true });
    if (out.dims.at(-1) !== artifact.dimensions) throw new Error('Unexpected embedding shape');
    result.push(...batch.map((_: unknown, j: number) => checkedBgeVector(out.data.slice(j * artifact.dimensions, (j + 1) * artifact.dimensions))));
  }
  return result;
}

// --- routing/loadEmbeddingModel.ts -----------------------------------------------------------
async function loadEmbeddingModel(progress_callback?: (p: any) => void) {
  const { AutoModel, AutoTokenizer, FeatureExtractionPipeline } = await import('@huggingface/transformers');
  const common = { revision: artifact.revision, progress_callback };
  const [model, tokenizer] = await Promise.all([
    AutoModel.from_pretrained(artifact.modelId, { ...common, dtype: 'q8', device: 'wasm' }),
    AutoTokenizer.from_pretrained(artifact.modelId, common),
  ]);
  return new FeatureExtractionPipeline({ task: 'feature-extraction', model, tokenizer });
}

async function initialize(modelId: unknown): Promise<void> {
  if (modelId !== 'Xenova/bge-small-en-v1.5') throw new Error('Unsupported runtime encoder');
  const { env } = await import('@huggingface/transformers');
  env.allowLocalModels = false;
  if (env.backends.onnx.wasm) env.backends.onnx.wasm.numThreads = 1;
  const [fetchedArtifact, registry, content] = await Promise.all([
    loadJson<Artifact>('bgeArtifact.json'),
    loadJson<{ id: string; name: string; purpose: string; use_when: string; trigger_examples: string }[]>('microtools.json'),
    loadJson<{ id: string; text: string }[]>('bgeSkillContent.json'),
  ]);
  artifact = fetchedArtifact;
  matchingIndex = buildMatchingIndex(registry, content);
  extractor = await loadEmbeddingModel((p: any) => { if (p.status === 'progress') (self as any).postMessage({ type: 'progress', label: 'Preparing guidance' }); });
  skillVectors = await index(matchingIndex.map(p => p.text));
  needVectors = await index(NEED_SCENARIOS.map(s => s.text));
  pathwayVectors = await index(PATHWAY_SCENARIOS.map(s => s.text));
  (self as any).postMessage({ type: 'ready', modelId: artifact.modelId, revision: artifact.revision, dimensions: artifact.dimensions, tokenBudget: artifact.maxTokens, pooling: artifact.pooling, dtype: artifact.dtype, artifact: artifact.artifact });
}

self.onmessage = (event: MessageEvent) => {
  const { type, id, text } = (event.data || {}) as { type?: string; id?: string; text?: string; modelId?: unknown };
  if (type === 'init') {
    if (!initPromise) initPromise = initialize(event.data.modelId);
    initPromise.catch(() => (self as any).postMessage({ type: 'unavailable' }));
    return;
  }
  if (type === 'cancel') { queue.cancel(id!); return; }
  if (!type || !(type in TASK_PRIORITY) || typeof id !== 'string' || typeof text !== 'string') return;
  if (text.length > (type === 'suggest' ? 30000 : 1800)) { (self as any).postMessage({ type: 'abstained', id, reason: 'input-length' }); return; }
  const enqueued = performance.now();
  async function* run(): AsyncGenerator<void, void, unknown> {
    const began = performance.now();
    const reply = (result: any) => {
      if (queue.has(id!)) (self as any).postMessage({ ...result, id, timing: { queueMs: began - enqueued, workMs: performance.now() - began, totalMs: performance.now() - enqueued } });
    };
    try {
      if (!initPromise) throw new Error('Not initialized');
      await initPromise;
      if (type === 'suggest') {
        const fits = (s: string) => checkEmbeddingInput(extractor.tokenizer, bgeQuery(s, 'suggest'), 512).fits;
        const sections = text.split(/\n\s*\n/).flatMap(part => embeddingSections(part, fits));
        if (sections.length > 96) { reply({ type: 'abstained', reason: 'token-budget' }); return; }
        const rankings: { toolId: string; score: number }[][] = [];
        for (const section of sections) {
          if (!queue.has(id!)) return;
          const q = await encode(bgeQuery(section, 'suggest'));
          rankings.push(rankBge(q, skillVectors));
          yield;
        }
        reply({ type: 'suggest-result', rankings, chunks: sections.length });
        return;
      }
      const q = await encode(bgeQuery(text, type));
      if (!queue.has(id!)) return;
      if (type === 'needs') reply({ type: 'needs-result', candidates: needVectors.map((v, i) => ({ id: NEED_SCENARIOS[i].id, score: dot(v, q) })) });
      else if (type === 'pathway') reply({ type: 'pathway-result', candidates: pathwayVectors.map((v, i) => ({ id: PATHWAY_SCENARIOS[i].id, score: dot(v, q) })) });
      else reply({ type: 'result', task: type, candidates: rankBge(q, skillVectors) });
    } catch (error) {
      reply(error instanceof Error && error.message === 'token-budget' ? { type: 'abstained', reason: 'token-budget' } : { type: 'error' });
    }
  }
  if (!queue.enqueue(id, type as TaskType, run())) (self as any).postMessage({ type: 'abstained', id, reason: 'busy' });
};
