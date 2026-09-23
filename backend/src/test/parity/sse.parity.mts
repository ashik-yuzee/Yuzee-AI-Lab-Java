// Runs scripted chat turns through the ORIGINAL server.ts POST /api/conversations/:id/messages handler with a
// mocked Gemini (sse/genai-stub.mjs) and records the response status, SSE event sequence and payloads, and the
// provider requests. ChatSseParityTest.java replays the same scenarios through ChatTurnService and diffs them.
// No port is opened and nothing is written to either repo: the handler runs on in-memory req/res objects with a
// temporary working directory. Regenerate:
//   cd C:/websites/yuzee-ai-token-lab && npx tsx --import file:///C:/websites/yuzee-ai-lab-java/backend/src/test/parity/sse/register.mjs C:/websites/yuzee-ai-lab-java/backend/src/test/parity/sse.parity.mts
import fs from 'fs';
import os from 'os';
import path from 'path';
import net from 'net';
import http from 'http';
import crypto from 'crypto';

const ORIGINAL = 'C:/websites/yuzee-ai-token-lab';
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sse-parity-'));
fs.symlinkSync(path.join(ORIGINAL, 'src'), path.join(work, 'src'), 'junction');
process.chdir(work);
Object.assign(process.env, { VERCEL: '1', GEMINI_API_KEY: 'test-key', DATABASE_URL: '', YUZEE_WAREHOUSE_DB: path.join(work, 'none.db'),
  ADMIN_USERNAME: 'u', ADMIN_PASSWORD: 'p', AUTH_SECRET: 's' });
const TOKEN = crypto.createHmac('sha256', 's').update('u:p').digest('hex');
const sha = (s: string) => crypto.createHash('sha256').update(s).digest('hex');

// ---------------------------------------------------------------- scenarios (shared with the Java test)
const base = (text: string, over: any = {}) => ({
  schema_version: '1.3', current_mode: 'A_CONVERSATION', response_intent: 'GENERAL_DELIVERY',
  content_blocks: [{ id: 'b1', type: 'text', level: 'none', variant: 'default', title: '', text, items: [], columns: [], rows: [] }],
  interaction: { kind: 'none', input_type: 'none', question_id: '', question: '', options: [], allow_other_input: false, other_input_label: '', fields: [], recommended_actions: [] },
  service_trigger: { service_intent_detected: false, primary_requested_service: 'NONE', confidence: 'LOW', reason: '', trigger_now: false, needs_more_clarity: false, actions: [] },
  rmo_readiness: { readiness: 'NOT_READY', ready_to_generate: false, missing_inputs: [], verification_required: false },
  state: { active_response_mode: 'Standard', effective_response_mode: 'Standard', mode_source: 'default', safety_override_applied: false,
    user_confidence: { score: -1, band: 'unknown', evidence_strength: 'none', trend: 'unknown', reason_codes: [] },
    progress: { explained: false, failed_attempts: 0, loop_count_same_issue: 0, security_breach_count: 0, active_security_penalty: null } },
  followups: { enabled: false, cancel_on_user_message: true, topic_lock: false, topic_key: '', triggers: [] }, ...over });
const split3 = (s: string) => [s.slice(0, 40), s.slice(40, 200), s.slice(200)];
const textChunks = (s: string, usage: any = { promptTokenCount: 1000, candidatesTokenCount: 200, totalTokenCount: 1200 }, finishReason = 'STOP') =>
  split3(s).map((t, i, a) => ({ candidates: [{ content: { role: 'model', parts: [{ text: t }] }, ...(i === a.length - 1 ? { finishReason } : {}) }], ...(i === a.length - 1 ? { usageMetadata: usage } : {}) }));
const ok = (text: string, over: any = {}, usage?: any) => ({ chunks: textChunks(JSON.stringify(base(text, over)), usage) });
const teaching = base('', { content_blocks: [
  { id: 'b1', type: 'text', level: 'none', variant: 'default', title: '', text: 'Placements are supervised practice.', items: [], columns: [], rows: [] },
  { id: 'b2', type: 'text', level: 'none', variant: 'default', title: 'What happens', text: 'You work shifts with a mentor. ' + 'Detail '.repeat(20), items: [], columns: [], rows: [] },
  { id: 'b3', type: 'list', level: 'none', variant: 'default', title: 'What to check', text: '', items: [{ id: 'i1', title: 'Hours', text: 'Ask the provider', value: '', status: null }], columns: [], rows: [] },
  { id: 'b4', type: 'callout', level: 'none', variant: 'info', title: 'Tip', text: 'Keep a log book.', items: [], columns: [], rows: [] }] });
const reviewed = { content_blocks: [teaching.content_blocks[0], { ...teaching.content_blocks[1], text: 'You work supervised shifts.' }, teaching.content_blocks[2]] };
const question = base('Which city suits you?', { response_intent: 'SOCRATIC_DIRECTION', interaction: { kind: 'question', input_type: 'single_select', question_id: 'q-city', question: 'Which city?',
  options: [{ id: 'syd', label: 'Sydney', value: 'Sydney', description: '' }, { id: 'mel', label: 'Melbourne', value: 'Melbourne', description: '' }], allow_other_input: false, other_input_label: '', fields: [], recommended_actions: [] } });
const long = 'I work nights in aged care and want to move into nursing, with a budget of about ten thousand dollars and two kids at school. ';

const scenarios: any[] = [
  { name: 'greeting-then-followup', conv: 'c-greet', steps: [{ body: { message: 'hi' } }, { body: { message: 'thanks' }, opens: [ok('Glad to help.')] }] },
  { name: 'rubbish-idle', conv: 'c-bypass', steps: [{ body: { message: '???' } }] },
  { name: 'idle', conv: 'c-idle', steps: [{ body: { message: 'lol', model: 'gemini-2.5-flash' } }] },
  { name: 'stream-thoughts-usage', conv: 'c-stream', steps: [{ body: { message: 'I want to become a nurse in Sydney. What should I study?', userContext: { date: '2026-09-23', timezone: 'Australia/Sydney', location: 'Sydney' },
    userProfileFacts: ['Works nights', 5, null], userQuestionAnswers: [{ q: 'Budget', a: 1.0, n: 0.5 }], temperature: 0.4, topP: 1, maxOutputTokens: 4096.6, responseMode: 'explain', thinkingLevel: 'high' },
    opens: [{ chunks: [{ candidates: [{ content: { parts: [{ thought: true, text: 'Thinking about nursing' }] } }] }, ...textChunks(JSON.stringify(base('Start with a Bachelor of Nursing.\nTab\there.')),
      { promptTokenCount: 1200, candidatesTokenCount: 300, thoughtsTokenCount: 40, cachedContentTokenCount: 600, totalTokenCount: 1540 })] }] }] },
  { name: 'review-applied', conv: 'c-review', steps: [{ body: { message: 'Explain how nursing placements work', useStructuredOutput: true },
    opens: [{ chunks: textChunks(JSON.stringify(teaching)) }], reviews: [{ text: JSON.stringify(reviewed), usageMetadata: { promptTokenCount: 900, candidatesTokenCount: 200, totalTokenCount: 1100 } }] }] },
  { name: 'review-failed', conv: 'c-review-fail', steps: [{ body: { message: 'Explain placements again' },
    opens: [{ chunks: textChunks(JSON.stringify(teaching)) }], reviews: [{ error: { status: 503, message: 'overloaded' } }, { error: { status: 503, message: 'overloaded' } }] }] },
  { name: 'invalid-json', conv: 'c-invalid', steps: [{ body: { message: 'Tell me about TAFE' }, opens: [{ chunks: textChunks('Sorry, plain text instead of JSON output here.') }] }] },
  { name: 'fenced-json', conv: 'c-fence', steps: [{ body: { message: 'Tell me about TAFE fees' }, opens: [{ chunks: textChunks('```json\n' + JSON.stringify(base('Fenced answer')) + '\n```') }] }] },
  { name: 'max-tokens', conv: 'c-max', steps: [{ body: { message: 'Compare every nursing course' }, opens: [{ chunks: textChunks(JSON.stringify(base('Cut')).slice(0, 300), undefined, 'MAX_TOKENS') }] }] },
  { name: 'provider-busy-retry', conv: 'c-busy', steps: [{ body: { message: 'What is a diploma of nursing?' }, opens: [{ error: { status: 503, message: 'The model is overloaded.' } }, { error: { status: 503, message: 'The model is overloaded.' } }] }] },
  { name: 'provider-busy-recovered', conv: 'c-busy2', steps: [{ body: { message: 'What is a diploma of nursing?' }, opens: [{ error: { status: 500, message: 'Internal' } }, ok('Recovered.')] }] },
  { name: 'daily-quota', conv: 'c-quota', steps: [{ body: { message: 'What is a diploma of nursing?' }, opens: [{ error: { status: 429, message: 'Quota exceeded for metric requests_per_day' } }] }] },
  { name: 'rate-limit', conv: 'c-rpm', steps: [{ body: { message: 'What is a diploma of nursing?' }, opens: [{ error: { status: 400, message: 'RESOURCE_EXHAUSTED requests per minute' } }] }] },
  { name: 'auth', conv: 'c-auth', steps: [{ body: { message: 'What is a diploma of nursing?' }, opens: [{ error: { status: 400, message: 'API_KEY_INVALID' } }] }] },
  { name: 'oala-basic', conv: 'c-oala', steps: [{ body: { message: '@Oala what is Yuzee?' } }, { body: { message: '@Oala list all services' } }] },
  { name: 'oala-gemini', conv: 'c-oala2', steps: [{ body: { message: '@Oala help me plan a move into nursing' }, opens: [ok('Here is a plan.')] }] },
  { name: 'bad-requests', conv: 'c-bad', steps: [{ body: {} }, { body: { message: 'hi', model: 'nope-model' } }, { body: { message: '', userEvent: { interaction: { question_id: 'q1', selected_option_ids: ['a'] } } } },
    { body: { message: 'hello', skillChoice: { toolId: 'COURSE_011' } } }] },
  { name: 'question-then-answer', conv: 'c-quiz', steps: [{ body: { message: 'Help me choose a city to study nursing' }, opens: [{ chunks: textChunks(JSON.stringify(question)) }] },
    { body: { message: '', userEvent: { interaction: { question_id: 'q-city', selected_option_ids: ['syd'] } }, isOptionSelection: true }, opens: [ok('Sydney has many options.')] },
    { body: { message: '', userEvent: { interaction: { question_id: 'q-city', selected_option_ids: ['zzz'] } } } }] },
  { name: 'save-tokens-compaction', conv: 'c-save', steps: [1, 2, 3, 4].map(i => ({ body: { message: long + 'Question ' + i + ' about nursing pathways?', mode: 'SAVE_TOKENS' },
    opens: [ok('Answer ' + i + ': ' + 'nursing pathway detail '.repeat(30))] })) },
  { name: 'full-context-custom-strategy', conv: 'c-full', steps: [{ body: { message: 'hello there, I am new', mode: 'FULL_CONTEXT', strategy: 'BASELINE', useMultiTurn: false,
    careerContext: { facts: 'Parent', goals: ' Nurse ', constraints: '' } }, opens: [ok('Welcome.')] }, { body: { message: 'hello there, I am new', useMultiTurn: false }, opens: [ok('Welcome again.')] }] },
];

// ---------------------------------------------------------------- scripted Gemini
let step: any = null;
const provider: any[] = [];
const normConfig = (c: any) => { const { systemInstruction, abortSignal, httpOptions, cachedContent, ...rest } = c || {};
  if (rest.responseSchema) rest.responseSchema = sha(JSON.stringify(rest.responseSchema)); return rest; };
const toContents = (c: any) => typeof c === 'string' ? [{ role: 'user', parts: [{ text: c }] }] : c;
(globalThis as any).__genai = {
  stream(req: any) {
    provider.push({ kind: 'stream', model: req.model, contents: toContents(req.contents), system: sha(req.config?.systemInstruction || ''), config: normConfig(req.config) });
    const o = step?.opens?.shift();
    if (!o) throw Object.assign(new Error('unscripted stream'), { status: 400 });
    if (o.error) throw Object.assign(new Error(o.error.message), { status: o.error.status });
    return (async function* () { for (const c of o.chunks) yield c; })();
  },
  generate(req: any) {
    if (!req.config?.responseSchema) return { text: '- Goal: nursing', usageMetadata: { promptTokenCount: 50, candidatesTokenCount: 10, totalTokenCount: 60 } };
    provider.push({ kind: 'review', model: req.model, contents: sha(req.contents), system: sha(req.config.systemInstruction), config: normConfig(req.config) });
    const r = step?.reviews?.shift();
    if (!r) throw Object.assign(new Error('unscripted review'), { status: 400 });
    if (r.error) throw Object.assign(new Error(r.error.message), { status: r.error.status });
    return { text: r.text, usageMetadata: r.usageMetadata, candidates: [{ finishReason: r.finishReason || 'STOP' }] };
  },
};

const wh = await import('file:///' + ORIGINAL + '/src/warehouse/service.ts');
wh.warehouseService.start = () => {};
wh.warehouseService.retrieve = async () => null;
const { app } = await import('file:///' + ORIGINAL + '/server.ts');

let calls = 0;
function call(url: string, body: any): Promise<{ status: number; type: string; text: string }> {
  return new Promise(resolve => {
    const json = JSON.stringify(body);
    const socket = new net.Socket();
    Object.defineProperty(socket, 'remoteAddress', { value: '10.0.0.' + (++calls) }); // one rate-limit window per call
    const req: any = new http.IncomingMessage(socket);
    req.method = 'POST'; req.url = url;
    req.headers = { authorization: 'Bearer ' + TOKEN, 'content-type': 'application/json', 'content-length': String(Buffer.byteLength(json)) };
    req.push(json); req.push(null);
    const res: any = new http.ServerResponse(req);
    let text = '';
    res.write = (c: any) => { text += Buffer.isBuffer(c) ? c.toString('utf8') : String(c); return true; };
    res.flushHeaders = () => {};
    res.end = (c?: any) => { if (c && typeof c !== 'function') res.write(c); res.finished = true; res.emit('finish'); res.emit('close');
      resolve({ status: res.statusCode, type: String(res.getHeader('content-type') || ''), text }); return res; };
    app(req, res);
  });
}

const VOLATILE = /"(requestReceivedAt|preProviderLatencyMs|conversationLoadMs|userEventValidationMs|memoryAssemblyMs|requestAssemblyMs|providerRequestStartedAt|providerTtftMs|providerGenerationDurationMs|providerCompletedAt|validationDurationMs|validationCompletedAt|totalLatencyMs|latencyMs|timestamp|ttlMs)":\d+/g;
const normalize = (s: string) => s.replace(VOLATILE, '"$1":0').replace(/msg-\d+-[a-z0-9]*/g, 'msg-ID').replace(/req-\d+-[a-z0-9]*/g, 'req-ID').replace(/"compactionEventId":"[^"]*"/g, '"compactionEventId":"ID"');
const parseSse = (text: string) => text.split('\n\n').filter(Boolean).map(f => { const m = f.match(/^event: (.*)\ndata: ([\s\S]*)$/); return m ? { event: m[1], data: normalize(m[2]) } : { raw: f }; });

const out: any[] = [];
for (const sc of scenarios) {
  const results: any[] = [];
  for (const st of sc.steps) {
    step = structuredClone(st); provider.length = 0;
    const r = await call('/api/conversations/' + sc.conv + '/messages', st.body);
    await new Promise(res => setTimeout(res, 150)); // post-response summarisation
    results.push({ status: r.status, sse: r.type.startsWith('text/event-stream'), events: r.type.startsWith('text/event-stream') ? parseSse(r.text) : undefined,
      body: r.type.startsWith('text/event-stream') ? undefined : r.text, provider: structuredClone(provider) });
  }
  out.push({ ...sc, results });
}
fs.writeFileSync(new URL('../resources/parity/chat-sse.json', import.meta.url), JSON.stringify(out, null, 1));
console.log('scenarios', out.length, 'steps', out.reduce((n, s) => n + s.results.length, 0));
process.exit(0);
