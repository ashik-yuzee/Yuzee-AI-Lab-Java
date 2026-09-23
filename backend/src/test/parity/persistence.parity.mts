// Runs a fixed sequence of API flows through the ORIGINAL server.ts (file-store mode: no DATABASE_URL) with a scripted
// Gemini (sse/genai-stub.mjs), then records every data file the original wrote and the JSON responses.
// PersistenceParityTest.java runs the same flows through the Spring app in file-store mode and compares the files
// byte for byte (after masking clock values and random ids). It also records db.ts's DDL statements in initDb()
// order for DbSchemaParityTest. Nothing is written to either repo except the goldens; the original runs in a
// temporary working directory. Regenerate:
//   cd C:/websites/yuzee-ai-token-lab && npx tsx --import file:///C:/websites/yuzee-ai-lab-java/backend/src/test/parity/sse/register.mjs C:/websites/yuzee-ai-lab-java/backend/src/test/parity/persistence.parity.mts
import fs from 'fs';
import os from 'os';
import path from 'path';
import net from 'net';
import http from 'http';
import crypto from 'crypto';

const ORIGINAL = 'C:/websites/yuzee-ai-token-lab';
const OUT = new URL('../resources/parity/persistence/', import.meta.url);
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'persistence-parity-'));
fs.symlinkSync(path.join(ORIGINAL, 'src'), path.join(work, 'src'), 'junction');
process.chdir(work);
Object.assign(process.env, { VERCEL: '1', GEMINI_API_KEY: 'test-key', DATABASE_URL: '', YUZEE_WAREHOUSE_DB: path.join(work, 'none.db'),
  ADMIN_USERNAME: 'u', ADMIN_PASSWORD: 'p', AUTH_SECRET: 's' });
const TOKEN = crypto.createHmac('sha256', 's').update('u:p').digest('hex');

// ---------------------------------------------------------------- db.ts DDL, in initDb() order
{
  const src = fs.readFileSync(path.join(ORIGINAL, 'src/services/db.ts'), 'utf8');
  const consts: Record<string, any> = {};
  for (const m of src.matchAll(/const (TABLE_\w+) = (`[^`]*`);/g)) consts[m[1]] = (0, eval)(m[2]);
  for (const m of src.matchAll(/const (\w+_SQLS) = (\[[\s\S]*?\n\]);/g)) consts[m[1]] = (0, eval)(m[2]);
  const init = src.slice(src.indexOf('export async function initDb'), src.indexOf('export async function pruneExpired'));
  const statements: string[] = [];
  for (const m of init.matchAll(/pool\.query\((TABLE_\w+)\)|for \(const sql of (\w+_SQLS)\)/g)) statements.push(...(m[1] ? [consts[m[1]]] : consts[m[2]]));
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(new URL('db-schema.json', OUT), JSON.stringify(statements, null, 1));
}

// ---------------------------------------------------------------- scripted Gemini
const opens: any[] = [];
const base = (text: string) => ({
  schema_version: '1.3', current_mode: 'A_CONVERSATION', response_intent: 'GENERAL_DELIVERY',
  content_blocks: [{ id: 'b1', type: 'text', level: 'none', variant: 'default', title: '', text, items: [], columns: [], rows: [] }],
  interaction: { kind: 'question', input_type: 'single_select', question_id: 'q-next', question: 'What next?',
    options: [{ id: 'a', label: 'Courses', value: 'Courses', description: '' }, { id: 'b', label: 'Jobs', value: 'Jobs', description: '' }],
    allow_other_input: false, other_input_label: '', fields: [], recommended_actions: [] },
  service_trigger: { service_intent_detected: false, primary_requested_service: 'NONE', confidence: 'LOW', reason: '', trigger_now: false, needs_more_clarity: false, actions: [] },
  rmo_readiness: { readiness: 'NOT_READY', ready_to_generate: false, missing_inputs: [], verification_required: false },
  state: { active_response_mode: 'Standard', effective_response_mode: 'Standard', mode_source: 'default', safety_override_applied: false,
    user_confidence: { score: -1, band: 'unknown', evidence_strength: 'none', trend: 'unknown', reason_codes: [] },
    progress: { explained: false, failed_attempts: 0, loop_count_same_issue: 0, security_breach_count: 0, active_security_penalty: null } },
  followups: { enabled: false, cancel_on_user_message: true, topic_lock: false, topic_key: '', triggers: [] } });
const reply = JSON.stringify(base('Glad to help.'));
(globalThis as any).__genai = {
  stream() {
    const o = opens.shift();
    if (!o) throw Object.assign(new Error('unscripted stream'), { status: 400 });
    return (async function* () { for (const c of o) yield c; })();
  },
  generate(req: any) {
    const text = typeof req.contents === 'string' && req.contents.startsWith('Write a short title') ? 'Career Change Plan.' : '- Goal: nursing';
    return { text, usageMetadata: { promptTokenCount: 11, candidatesTokenCount: 5, totalTokenCount: 16 }, candidates: [{ finishReason: 'STOP' }] };
  },
};

const wh = await import('file:///' + ORIGINAL + '/src/warehouse/service.ts');
wh.warehouseService.start = () => {};
wh.warehouseService.retrieve = async () => null;
const { app } = await import('file:///' + ORIGINAL + '/server.ts');

let calls = 0;
function call(method: string, url: string, body?: any): Promise<{ status: number; text: string }> {
  return new Promise(resolve => {
    const json = body === undefined ? '' : JSON.stringify(body);
    const socket = new net.Socket();
    Object.defineProperty(socket, 'remoteAddress', { value: '10.0.0.' + (++calls) }); // one rate-limit window per call
    const req: any = new http.IncomingMessage(socket);
    req.method = method; req.url = url;
    req.headers = { authorization: 'Bearer ' + TOKEN, ...(body === undefined ? {} : { 'content-type': 'application/json', 'content-length': String(Buffer.byteLength(json)) }) };
    if (json) req.push(json);
    req.push(null);
    const res: any = new http.ServerResponse(req);
    let text = '';
    res.write = (c: any) => { text += Buffer.isBuffer(c) ? c.toString('utf8') : String(c); return true; };
    res.flushHeaders = () => {};
    res.end = (c?: any) => { if (c && typeof c !== 'function') res.write(c); res.finished = true; res.emit('finish'); res.emit('close');
      resolve({ status: res.statusCode, text }); return res; };
    app(req, res);
  });
}
const settle = () => new Promise(r => setTimeout(r, 250)); // fire-and-forget writes
const responses: any[] = [];
async function step(name: string, method: string, url: string, body?: any) {
  const r = await call(method, url, body);
  await settle();
  responses.push({ name, status: r.status, body: r.text.startsWith('{') || r.text.startsWith('[') ? r.text : r.text ? 'SSE' : '' });
  return r.text.startsWith('{') ? JSON.parse(r.text) : null;
}

// ---------------------------------------------------------------- flows (mirrored in PersistenceParityTest.java)
await step('shared-settings', 'PUT', '/api/shared-settings', { strategy: 'BASELINE', contextBudget: 200000 });
const c1 = await step('create', 'POST', '/api/conversations', { model: 'gemini-3.5-flash', mode: 'AUTO', responseMode: 'standard', thinkingLevel: 'adaptive' });
await step('update', 'PUT', '/api/conversations/' + c1.id, { title: 'Renamed', useMultiTurn: false, temperature: 0.4, careerContext: { facts: 'Parent', goals: 'Nurse' } });
await step('turn-bypass', 'POST', '/api/conversations/' + c1.id + '/messages', { message: 'hi' });
opens.push([reply.slice(0, 40), reply.slice(40)].map((t, i) => ({ candidates: [{ content: { role: 'model', parts: [{ text: t }] }, ...(i ? { finishReason: 'STOP' } : {}) }],
  ...(i ? { usageMetadata: { promptTokenCount: 1000, candidatesTokenCount: 200, totalTokenCount: 1200 } } : {}) })));
await step('turn-gemini', 'POST', '/api/conversations/' + c1.id + '/messages', { message: 'thanks, what can I study?' });
const afterTurn = await step('get', 'GET', '/api/conversations/' + c1.id);
await step('feedback', 'POST', '/api/conversations/' + c1.id + '/feedback?messageId=' + encodeURIComponent(afterTurn.messages[3].id), { rating: 'up', comment: 'Clear – thanks' });
await step('reset-memory', 'POST', '/api/conversations/' + c1.id + '/reset-memory');
await step('generate-title', 'POST', '/api/conversations/' + c1.id + '/generate-title');
await step('demo-1', 'POST', '/api/conversations/load-demo');
await new Promise(r => setTimeout(r, 5)); // distinct conv-demo-<ms> ids
await step('demo-2', 'POST', '/api/conversations/load-demo');
await step('restore', 'POST', '/api/conversations/restore', { id: 'restored-1', title: 'Restored plan', createdAt: 1600000000000, updatedAt: 1600000001000, model: 'gemini-3.5-flash',
  careerContext: { facts: 'Works nights' }, summary: 'Earlier summary', summaryVersion: 2,
  messages: [{ id: 'r-user-1', role: 'user', content: 'Which course?', createdAt: 1600000000500, isStreaming: false },
    { id: 'r-asst-1', role: 'assistant', content: reply, structuredResponse: JSON.parse(reply), telemetry: { model: 'gemini-3.5-flash', validation: { protocolAccepted: true } }, createdAt: 1600000000900 }] });
const c2 = await step('create-2', 'POST', '/api/conversations', {});
await step('delete', 'DELETE', '/api/conversations/' + c2.id);

// ---------------------------------------------------------------- side-panel stores (store level: the original classes' own files)
const { MiniPathwayService } = await import('file:///' + ORIGINAL + '/src/miniPathway/service.ts');
const { DetailResearchService } = await import('file:///' + ORIGINAL + '/src/research/DetailResearchService.ts');
const { ObjectiveService } = await import('file:///' + ORIGINAL + '/src/objectives/service.ts');
const sample = JSON.parse(fs.readFileSync(new URL('store-sample.json', OUT), 'utf8'));
const mini: any = new MiniPathwayService(() => null);
for (const run of sample.miniPathways) await mini.store.save(run);
await mini.store.save({ ...sample.miniPathways[0], status: 'complete' });
await mini.remove('conv-gone');
const detail: any = new DetailResearchService(() => null);
for (const result of sample.details) await detail.store.save(result);
await detail.remove('conv-gone');
const objectives: any = new ObjectiveService();
await objectives.write(path.join(objectives.folder(sample.objective.conversationId), sample.objective.id + '.json'), sample.objective);

// ---------------------------------------------------------------- record
const files: Record<string, string> = {};
const walk = (dir: string) => { for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
  const p = path.join(dir, f.name);
  if (f.isDirectory()) walk(p); else files[path.relative(work, p).split(path.sep).join('/')] = fs.readFileSync(p, 'utf8');
} };
walk(path.join(work, 'data'));
fs.writeFileSync(new URL('files.json', OUT), JSON.stringify({ reply, files, responses }, null, 1));
console.log('files', Object.keys(files).length, 'responses', responses.length);
process.exit(0);
