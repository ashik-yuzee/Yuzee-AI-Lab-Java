// Feeds a fixed set of inputs through the ORIGINAL TypeScript request assembly and records the outputs.
// RequestAssemblyParityTest.java feeds the same inputs through the Java port and diffs the results.
// Regenerate (cwd must be the original repo, the assembler reads its prompt files from process.cwd()):
//   cd C:/websites/yuzee-ai-token-lab && npx tsx C:/websites/yuzee-ai-lab-java/backend/src/test/parity/request-assembly.parity.mts
import fs from 'fs';
import crypto from 'crypto';
const sha = (s: string) => crypto.createHash('sha256').update(s).digest('hex');
const O = 'file:///C:/websites/yuzee-ai-token-lab/src/';
const { YuzeeRequestAssembler } = await import(O + 'services/YuzeeRequestAssembler.ts');
const mm = await import(O + 'services/TokenBudgetMemoryManager.ts');
const mt = await import(O + 'services/MultiTurnRequestBuilder.ts');
const { GEMINI_MODELS } = await import(O + 'data/models.ts');
const { shortReplyGuidance } = await import(O + 'ux/shortReplyGuidance.ts');
const { bypassCopy } = await import(O + 'ux/bypassCopy.ts');
const { WAREHOUSE_INSTRUCTION } = await import(O + 'warehouse/service.ts');
const { sharedConversationContext } = await import(O + 'orchestration/sharedContext.ts');
const { workspaceQuestionOwner } = await import(O + 'orchestration/questionOwner.ts');
const { readActivityContext } = await import(O + 'objectives/activityContext.ts');
const { splitGeminiStreamChunk } = await import(O + 'ux/streamProgress.ts');

const A = YuzeeRequestAssembler.getInstance();
const cases: { fn: string; args: any[] }[] = [];
const add = (fn: string, ...args: any[]) => cases.push({ fn, args });
const U = { __undef: true }; // JSON stand-in for undefined

const prompts = ['', 'hi', 'Hi', 'hello there', 'Hello, how are you today?', 'what is a diploma', 'What is a diploma?', 'ok', 'OK cool', 'great thanks', 'got it', 'thanks!',
  'format this', 'short one?', 'nineteen chars here', 'twenty chars here ok', 'a longer message without keywords at all', 'Compare nursing vs teaching', 'which is better for me',
  'give me a roadmap', 'pros and cons of TAFE', 'Explain HECS', 'I want to become a nurse', "I'm thinking about switching to tech", 'I AM A JUNIOR DEVELOPER', 'what jobs pay well?',
  'İstanbul course', 'ΟΔΟΣ', 'plan', 'vs', 'Can you tell me about the weather on Mars please?', '[Date: 2026-01-01 · Location: Sydney]\nWhat courses?', U];
const models = [...GEMINI_MODELS.map((m: any) => m.id), 'unknown-model'];
const levels = ['adaptive', 'minimal', 'low', 'medium', 'high', 'weird', U];
for (const m of models) for (const l of levels) for (const p of (l === 'adaptive' ? prompts : [prompts[1], prompts[17]])) add('resolveThinkingConfig', m, l, p);

const texts = ['', '   ', 'hi', 'Hi!', 'HELLO THERE', 'hey oala', 'hello?', 'hello??!', 'good morning yuzee', 'how are you doing', 'are you there', 'testing', "what's up", 'what is up with you',
  'bye', 'see ya later', 'thanks for that!!', 'thank you for your help', 'ok.', 'okay', 'sounds good', "that's helpful", 'no more questions', "i'm all set", 'i am done',
  'lol', 'loooool', 'haha', 'hehehe', '😂', '🤣', 'omg', 'meh', "i don't care", 'hmmmm', "i'm bored rn", "you're funny", 'good one', "what's the weather", 'what day is it',
  'tell me a joke', 'roll a d20', 'what is your name', 'do you sleep', 'are you a robot', 'who made you', 'how old are you', 'nevermind', 'just browsing', 'jk',
  '???', '...', '!!!', '—', 'RPL', '123', 'a', '你好', 'I want to study nursing', 'hi there friend', "hi, i'm new", 'hi\u00a0there', 'thanks\u2028', '\u0001hi', 'hi\u0001', '\u00a0bye\u00a0',
  'ok\u3000', 'hey\tthere', 'hey\u00a0there', 'ｈｉ', "thanks'", 'thanks,.', "hello'?"];
for (const t of texts) for (const ctx of [{}, { hasConversation: true }, { hasActiveQuestion: true }, { hasConversation: false, hasActiveQuestion: false }]) add('classifyUserMessage', t, ctx);
for (const k of ['greeting', 'farewell', 'idle', 'rubbish']) add('bypassCopy', k);

const events: any[] = [U, null, {}, { message: 'x' }, { ui: {} }, { ui: { selected_mode: 'quick' } }, { ui: { selected_mode: ' DETAIL ' } }, { userEvent: { ui: { selected_mode: 'explore' } } },
  { interaction: { question_id: 'q1', selected_option_ids: ['a', 'b'] } }, { userEvent: { interaction: { question_id: 'q2', self_input: 'Nursing\n"quoted"' }, ui: { selected_mode: 'Decide' } } },
  { type: 'option_selected', option_id: 'opt1' }, { type: 'text_answer', value: 'My answer', interaction_id: 'q9' }, { type: 'ranked_submission', ranked_ids: ['c', 'a'], ranked_option_ids: ['z'] },
  { type: 'fields_submitted', fields: { name: 'Ann', age: '30' }, selected_option_ids: ['x'] }, { type: 'action_clicked', action_id: 'act', self_input: 'si', ranked_ids: null },
  { type: 'x', ranked_ids: null, ranked_option_ids: null, fields: null, value: '', self_input: null, action_id: null, option_id: '', selected_option_ids: null },
  { type: '', interaction: null, ui: { selected_mode: 'Explain', extra: 1 } }, { interaction: {}, ui: { other: true } }, { interaction: { question_id: 'q', fields: { note: 'tab\there \u0001 ctl \u001f / é 😀 \u2028' } } },
  { userEvent: { interaction: { question_id: 'n', selected_option_ids: [] } } }, { interaction: { question_id: 'num', score: 1.5, n: 2, big: 12345678901, neg: -0.25, t: true, z: null } }];
for (const e of events) for (const [txt, mode] of [['', 'Standard'], ['  Some text  ', U], ['Hello', 'quick']]) add('formatUserEvent', txt, e, mode);
for (const c of [{}, { facts: ' likes maths ', goals: '', constraints: '   ', decisions: 'chose TAFE', openThreads: 'fees?' }, { a: 'x', b: '\u00a0' }]) add('formatCareerContext', c);
for (const m of ['Standard', 'quick', ' EXPLORE ', 'Detail', 'decide', 'explain', 'weird', '']) add('normalizeModeCapitalization', m);

add('sanitizeSchema', { type: 'object', title: 'T', additionalProperties: false, description: '', nullable: false, format: 'date', properties: { a: { type: 'string', enum: ['', 'x', 'y'], minLength: 1 },
  b: { type: 'integer', enum: [1, 2], minimum: 0 }, c: { type: 'string', enum: [''] }, d: { type: 'array', items: { type: 'number', enum: [1.5] }, maxItems: 3 }, e: { anyOf: [{ type: 'string' }, { type: 'null' }] },
  f: { type: 'string', enum: ['a', 1, null, ''] , nullable: false }, g: { type: ['string', 'null'], enum: ['q'] } }, required: ['a'] });
add('sanitizeSchema', [{ type: 'string' }, 5, 'x', null]);
add('geminiResponseSchema');
add('warehouseInstruction');
for (const k of ['promptHash', 'schemaHash']) add(k);

// Memory + multi-turn
const rich = JSON.stringify({ schema_version: '1.3', current_mode: 'A_CONVERSATION', response_intent: 'GUIDANCE', content_blocks: [{ type: 'heading', level: 'h3', title: ' Plan ' },
  { type: 'text', text: '  Learn SQL\u00a0and Excel. ' }, { type: 'list', text: '', items: [{ title: 'Step', text: 'SQL', value: 5, status: 'done' }, {}, { text: 'Tableau' }] },
  { type: 'table', text: '', columns: [{ label: 'A' }, { key: 'b' }, 'x'], rows: [{ criteria: 'Cost', cells: [{ value: '$1' }, { value: 0 }] }, { cells: [] }] }, { type: 'callout', variant: 'warning', text: 'Careful' }],
  interaction: { kind: 'question', question: ' Which city? ', options: [{ label: 'Sydney' }, { value: 'Melb' }, { label: '' }] } });
const messages = [
  { id: 'u1', role: 'user', content: 'I want to become a data analyst in Sydney with Python skills', createdAt: 1000 },
  { id: 'a1', role: 'assistant', content: rich, createdAt: 2000 },
  { id: 'u2', role: 'user', content: 'What about SQL certification costs?', createdAt: 3000 },
  { id: 'a2', role: 'assistant', content: 'Rejected reply', createdAt: 4000, telemetry: { validation: { protocolAccepted: false } } },
  { id: 'u3', role: 'user', content: 'Consecutive user one', createdAt: 5000 },
  { id: 'u4', role: 'user', content: 'Tell me about Python data analyst bootcamps in Sydney please', createdAt: 6000 },
  { id: 'a4', role: 'assistant', content: '   Bootcamps range widely.\n\nSome   are cheap.  ', createdAt: 7000 },
  { id: 's1', role: 'system', content: 'system note', createdAt: 7500 },
  { id: 'u5', role: 'user', content: 'salaries for analysts in sydney?', createdAt: 8000 },
  { id: 'a5', role: 'assistant', content: '```json\n{"content_blocks":[{"type":"text","text":"Fenced"}]}\n```', createdAt: 9000 },
  { id: 'u6', role: 'user', content: 'ok\u00a0thanks\u2003now what', createdAt: 10000 },
  { id: 'a6', role: 'assistant', content: JSON.stringify({ content_blocks: [{ type: 'text', text: 'Next: ' + 'x'.repeat(900) }] }), createdAt: 11000 },
  { id: 'u7', role: 'user', content: 'last question about python', createdAt: 12000 },
];
add('estimateTokens', ['', '  ', 'hello world', 'a\u00a0b\u2003c  d', rich, U, 'x\u0001y', ' \u2028 ']);
add('groupIntoTurns', messages);
for (const r of [rich, '', '   ', 'plain text', '{"content_blocks":"nope"}', '[1,2]', 'null', '{"interaction":{"question":"Q?","options":[{"label":"L","description":"D"}]}}', messages[9].content, messages[11].content]) {
  add('formatAssistantMessageRich', r); add('formatAssistantMessageForContext', r);
}
for (const s of ['BASELINE', 'ADAPTIVE_HYBRID', 'SUMMARY_RECENT', 'SEMANTIC_EVIDENCE', 'UNKNOWN'])
  for (const [b, k] of [[40, 100], [2000, 2], [5, 1], [100000, 100], [300, 3], [0, 0]])
    add('assembleMemory', messages, b, k, s, 'Prior summary text here', 'sydney data analyst python salaries');
add('assembleMemory', messages, 270000, 100, 'ADAPTIVE_HYBRID', '', '');
add('assembleMemory', [], 2000, 100, 'ADAPTIVE_HYBRID', '', 'hi');
add('buildMultiTurnContents', 'CAPSULE', '  sum  ', 'memory:ADAPTIVE_HYBRID:100000:100', ' now ', true);
add('buildMultiTurnContents', '', '', 'none', 'only', true);
add('buildMultiTurnContents', '', ' ', 'all', 'x', false);
add('buildMultiTurnContents', 'CAP', '', 'all', 'y', true);

// shortReplyGuidance
const hist = (last: string) => [{ role: 'user', content: 'first' }, { role: 'assistant', content: 'a' }, { role: 'user', content: last }, { role: 'assistant', content: 'b' }];
for (const [t, last, st] of [['What is RPL?', 'what is rpl', false], ['what is rpl', 'What is RPL?', true], ['one two three four five six', 'one two three four five six', false], ['?!', '?!', false],
  ['Tell\u00a0me more', 'tell me more', false], ['  why  ', 'why.', false], ['why', 'how', false], ['123', '123', false], ['', '', false], ['a\u00a0b c d e f', 'a\u00a0b c d e f', false]] as const)
  add('shortReplyGuidance', t, hist(last), st);
add('shortReplyGuidance', 'why', [], false);

// assembleRequest
const mem = new mm.TokenBudgetMemoryManager().assembleMemory(messages as any, 100000, 100, 'ADAPTIVE_HYBRID', 'Prior summary', 'python');
const baseParams = { model: 'gemini-3.5-flash-lite', messageText: '[Date: 2026-09-23 · Location: Sydney]\nHow do I become a nurse?', microToolInstruction: 'SKILL\n\nWAREHOUSE', careerContext: { facts: 'likes people', goals: '' },
  summaryText: mem.summaryText, recentHistoryText: mem.recentHistoryText, responseMode: 'standard', thinkingLevel: 'adaptive', useMultiTurn: true, useStructuredOutput: false };
const variants: any[] = [
  { ...baseParams, keptTurns: 'mem' },
  { ...baseParams, keptTurns: 'mem', useStructuredOutput: true, temperature: 0.7, topP: 0.95, maxOutputTokens: 2048 },
  { ...baseParams, useMultiTurn: false, keptTurns: 'mem' },
  { ...baseParams, useMultiTurn: true },
  { ...baseParams, keptTurns: 'mem', customSystemPrompt: '  Custom prompt  ', systemPromptMode: 'custom', oalaInstruction: 'OALA RULES' },
  { ...baseParams, keptTurns: 'mem', customSystemPrompt: '   ', systemPromptMode: 'custom' },
  { ...baseParams, keptTurns: 'mem', customSystemPrompt: 'X', systemPromptMode: 'default', oalaInstruction: '' },
  { ...baseParams, keptTurns: 'mem', userEvent: events[9], messageText: 'Nursing', microToolInstruction: '' },
  { ...baseParams, keptTurns: 'none', useMultiTurn: true, careerContext: U, summaryText: '', recentHistoryText: '' },
  { model: '', messageText: 'hi', keptTurns: 'none', useMultiTurn: false, thinkingLevel: '', responseMode: '', temperature: 0, topP: 1, maxOutputTokens: 0 },
  { ...baseParams, model: 'gemini-2.5-flash', thinkingLevel: 'high', keptTurns: 'mem', useStructuredOutput: true },
  { ...baseParams, model: 'gemini-3.7-flash', thinkingLevel: 'minimal', keptTurns: 'mem', summaryText: '   ', recentHistoryText: ' r ' },
];
for (const v of variants) add('assembleRequest', v);

// Chat-path helpers in server.ts's orbit
const conv = { id: 'c1', messages: [
  { id: 'm1', role: 'user', content: 'I live in Parramatta and work nights', createdAt: 1000 },
  { id: 'm2', role: 'assistant', content: JSON.stringify({ interaction: { kind: 'question', question: 'Which field interests you?' } }), createdAt: 2000 },
  { id: 'm3', role: 'user', content: 'Nursing or aged care, "flexible"\n\ttab \u0001', createdAt: 3000 },
  { id: 'm4', role: 'assistant', content: 'not json', structuredResponse: { interaction: { kind: 'none' } }, createdAt: 4000 },
  { id: 'm5', role: 'user', content: 'Transferred', objectiveTransfer: { sessionId: 's' }, createdAt: 5000 },
  { id: 'm6', role: 'assistant', content: '{"interaction":{"kind":"question","question":"Budget?"}}', createdAt: 6000 },
  { id: 'm7', role: 'user', content: 'y'.repeat(1700), createdAt: 7000 },
  { id: 'm8', role: 'user', content: 'nursing fees at deakin', createdAt: 8000 },
  { id: 'm9', role: 'user', content: 'no timestamp here' },
] };
const sessions = [
  { id: 's1', conversationId: 'c1', label: 'Plan study', state: 'ACTIVE', answers: [{ id: 'q1-1', question: 'Hours per week?', answer: 10, submittedAt: 2500 }], pendingAnswer: null,
    context: { user_corrections: [{ text: 'I actually work days', createdAt: 3500 }] }, plan: { ui: [{ id: 'c0', component: 'action_handoff', required: true }, { id: 'c1', component: 'text', required: true, prompt: 'Which campus?' }] } },
  { id: 's2', conversationId: 'c1', label: 'Old', state: 'COMPLETE', context: { confirmed_facts: { answer_1: '{"question":"Nursing?","answer":["yes","no"]}', answer_2: 'not json', other: 'x' } },
    pendingCorrection: { text: 'fix nursing', createdAt: '1970-01-01T00:00:09.000Z' } },
  { id: 's3', conversationId: 'other', label: 'Other', state: 'ACTIVE', context: {} },
];
for (const q of ['nursing fees', 'Parramatta campus nights', undefined]) add('sharedConversationContext', conv, sessions, q ?? U);
add('sharedConversationContext', { id: 'c2', messages: [] }, [], U);
for (const v of ['s1', 's2', 'nope', null]) add('workspaceQuestionOwner', sessions, v);
add('workspaceQuestionOwner', [{ ...sessions[0], pendingAnswer: { id: 'p' } }], 's1');
for (const ac of [U, null, [], 'x', { current_goal: '  Become a nurse  ', confirmed_facts: ['Parramatta', 'made up', '  nights  ', 5, 'x'.repeat(200)], possible_need: 7, missing_information: ['a', '', ' b ', 'c', 'd', 'e'],
  relevant_question: 'Q'.repeat(400), user_constraints: 'nope' }]) add('readActivityContext', { state: { activity_context: ac } }, ['I live in Parramatta and work nights', 'nights']);
for (const ch of [{}, { candidates: [{ content: { parts: [{ text: 'a' }, { thought: true, text: 'think' }, { text: 'b' }] }, finishReason: 'STOP' }] },
  { candidates: [{ content: { parts: [{ thought: true, text: '' }, { thought: 'yes', text: 'c' }, { text: 5 }, null] } }] }, { candidates: [] }, null]) add('splitGeminiStreamChunk', ch);

// JavaScript text semantics: JSON.parse numbers are doubles, JSON.stringify escapes lone surrogates.
for (const raw of ['{"interaction":{"question_id":"n","big":9007199254740993,"neg":-9007199254740995,"huge":123456789012345678901234567890,"inf":1e400,"f":1.0,"tiny":1e-7,"z":-0,"e":1e21,"safe":9007199254740992}}',
  '{"interaction":{"question_id":"s","fields":{"lone":"a\\udc00b","high":"\\ud83d","pair":"\\ud83d\\ude00","rev":"\\ude00\\ud83d","\\udc01key":"v"}}}',
  '{"type":"text_answer","value":"x\\ud800","interaction_id":"q"}'])
  add('formatUserEventRaw', '', raw, 'Standard');
// JS /i (no u flag) folds only ASCII letters for these patterns: K (U+212A), long s and dotted I do not match.
for (const [m, c] of [['what courses', ''], ['WHAT COURSES', ''], ['\u212Aills', ''], ['\u017Fkills', ''], ['\u017Fkill\u017F to learn', ''], ['İstanbul', ''], ['I\u0307stanbul courses', ''],
  ['ok\u00a0', ''], ['thanks\u2028', ''], ['a\u00a0b\u00a0c', ''], ['a\u0085b c', ''], ['hi', ''], ['that one', 'Diploma'], ['that one', 'DIPLOMA'], ['that one', 'diplom\u0430'], ['part\u2028time?', 'course'],
  ['part-time?', 'course'], ['don\u2019t search', ''], ['DO NOT FETCH anything', ''], ['CPC30220', ''], ['cpc30220', '']] as const) add('needsWarehouse', m, c);

// Objective planner: schema.mjs / providerSchema.ts / validate.mjs / service.ts plan() through a stubbed model.
const { WIRE_INSTRUCTION, compileResult, plannerSchema } = await import('file:///C:/websites/yuzee-ai-token-lab/qa/objectives/schema.mjs');
const { validateOutput } = await import('file:///C:/websites/yuzee-ai-token-lab/qa/objectives/validate.mjs');
const { objectiveProviderSchema } = await import(O + 'objectives/providerSchema.ts');
const { ObjectiveService, workspaceInstructions } = await import(O + 'objectives/service.ts');
const registry = (await import('file:///C:/websites/yuzee-ai-token-lab/qa/objectives/registry/workbook.json', { with: { type: 'json' } })).default;
const byId = (id: string) => registry.objectives.find((o: any) => o.tool_id === id);
const os = await import('os');
const path = await import('path');
add('objectiveWire');
for (const id of ['STUDY_004', 'DISCOVER_001', 'GOAL_001']) add('workspaceInstructions', id);
add('contractWarnings');
for (const c of ['a; b[]; c: X|Y; d =true; e[] {f, g: A|B, source_status}; h {i}', 'a, a; b{c}d; 1x; e: lower; f[]: NOPE', '', ' ; ;x ']) add('compileResultDsl', c);

const answer = '{"question":"Priority?","answer":"Cost"}';
const conv0 = { id: 'objconv', messages: [
  { id: 'om1', role: 'user', content: 'I want to understand what kind of work suits me', createdAt: 1000 },
  { id: 'om2', role: 'assistant', content: JSON.stringify({ response_intent: 'GUIDANCE', interaction: { kind: 'question', question: 'Do you prefer people or things?' }, content_blocks: [{ type: 'text', text: 'Let us explore.' }] }), createdAt: 2000 },
] };
const d3 = (o: any = {}) => ({ objective_id: 'DISCOVER_003', status: 'NEEDS_INPUT', stage: 'UNDERSTAND',
  ui: [{ component: 'spectrum', id: 'pace', purpose: 'Work pace', prompt: 'Fast or steady?', required: true, options: [], content: [], columns: [], rows: [],
    settings: { allow_unsure: true, min: 0, max: 10, min_label: 'Steady', max_label: 'Fast', buckets: [] } }],
  confirmed_inputs: {}, derived_signals: [], unknowns: ['Preferred pace'],
  result: { summary: 'Exploring preferences.', work_preference_dimensions: [], next_actions: [] },
  readiness: { enabled: false, name: null, score: null, dimensions: [], blockers: [] },
  next_actions: [{ id: 'continue_chat', label: 'Keep chatting', type: 'CONTINUE_CHAT' }],
  handoff: { summary: 'Asked about pace.', confirmed_user_inputs: {}, result: { summary: 'Exploring.' }, evidence_and_unknowns: [], recommended_next_action: 'Answer the pace question' }, ...o });
const body = (text: string) => ({ status: 'completed', model: 'gemini-3.7-flash', outputs: [{ type: 'text', text }] });
const startCases: [any, any[]][] = [
  [{ objectiveId: 'DISCOVER_003', goal: '  Help me see my work style  ' }, [body(JSON.stringify(d3()))]],
  [{ objectiveId: 'DISCOVER_003' }, [body('{"objective_id":"DISCOVER_003","status":"DONE","extra":1,"ui":[{"component":"spectrum","id":"x"}],"readiness":{"enabled":true,"score":150}}'), body(JSON.stringify(d3({ status: 'COMPLETE', ui: [] })))]],
  [{ objectiveId: 'DISCOVER_003' }, [{ steps: [{ type: 'model_output', content: [{ type: 'text', text: 'not json' }] }] }, body(JSON.stringify(d3({ next_actions: [{ id: 'act_x', label: 'Go https://x', type: 'CONTINUE_CHAT' }] })))]],
  [{ objectiveId: 'DISCOVER_003' }, [body(JSON.stringify(d3({ ui: [d3().ui[0], { ...d3().ui[0], id: 'pace' }] }))), body(JSON.stringify(d3({ result: { summary: 'x'.repeat(700), work_preference_dimensions: [], next_actions: [] } })))]],
  [{ objectiveId: 'GOAL_001' }, []],
  [{ objectiveId: 'NOPE_1' }, []],
];
for (const [b, outputs] of startCases) add('objectiveStart', conv0, b, outputs);

const s4ctx = { user_message: 'Compare CPC30220 at TAFE NSW and Master Builders', prior_context_text: 'Earlier: wants to build', confirmed_facts: { answer_1: answer },
  approved_evidence: [{ id: 'warehouse_course_1' }, { id: 'warehouse_course_2' }, { title: 'no id' }], session: { interaction_count: 1 } };
add('plannerSchema', 'STUDY_004', s4ctx);
add('plannerSchema', 'DISCOVER_001', { confirmed_facts: {} });
add('objectiveProviderSchema', 'STUDY_004', s4ctx);
const s4 = (o: any = {}) => ({ objective_id: 'STUDY_004', status: 'COMPLETE', stage: 'PROCEED',
  ui: [{ component: 'comparison_grid', id: 'grid', purpose: 'Compare', prompt: '', required: false, options: [],
    content: [{ label: 'Cost', detail: 'Varies', source_status: 'GENERAL_GUIDANCE', evidence_refs: [] }], columns: ['TAFE NSW', 'Master Builders'], rows: [{ label: 'Duration', cells: ['12 months', '18 months'] }],
    settings: { allow_unsure: false, min: null, max: null, min_label: null, max_label: null, buckets: [] } }],
  confirmed_inputs: { answer_1: answer }, derived_signals: [{ signal: 'cost-focused', basis: 'answer_1', confidence: 'MEDIUM' }], unknowns: ['Fees'],
  result: { summary: 'Both courses share CPC30220.', courses: ['TAFE NSW', 'Master Builders'],
    comparison_dimensions: [{ criterion: 'Duration', course_values: ['12 months', { label: 'x', detail: 'y', source_status: 'SOURCED_CURRENT_FACT', evidence_refs: ['warehouse_course_2'] }], source_status: 'SOURCED_CURRENT_FACT', evidence_refs: ['warehouse_course_1'] }],
    user_priorities: ['Cost'], tradeoffs: [], unknowns: [], next_actions: [] },
  readiness: { enabled: false, name: null, score: null, dimensions: [], blockers: [] },
  next_actions: [{ id: 'continue_chat', label: 'Continue', type: 'CONTINUE_CHAT' }],
  handoff: { summary: 'Compared two providers.', confirmed_user_inputs: { answer_1: answer }, result: { summary: 'Shared qualification.' }, evidence_and_unknowns: [], recommended_next_action: 'Ask about fees' }, ...o });
const g = s4();
const variants4: any[] = [
  s4(), '{', '{"a":1} x', '', ' null ', '[1]',
  s4({ status: 'DONE', stage: 7, extra: true, ui: [{ component: 'spectrum', id: 1, required: 'yes', options: [{ id: 'a' }], settings: null }, g.ui[0], g.ui[0], g.ui[0]] }),
  s4({ readiness: { enabled: true, name: 5, score: 150, dimensions: [{ name: 'd', score: -1, reason: 'r', x: 1 }], blockers: [null] }, confirmed_inputs: { answer_1: 'changed' }, unknowns: [{ a: 1 }] }),
  s4({ result: { summary: 7, courses: [{ label: 'x' }, [1], null], comparison_dimensions: [null, { criterion: {} }], other: 1 }, handoff: { summary: '' } }),
  s4({ next_actions: [{ id: 'research_required', label: 'Look up /api/x', type: 'RESEARCH' }, { id: 'continue_chat', label: 'x', type: 'RESEARCH' }] }),
  s4({ result: { ...g.result, summary: 'y'.repeat(2001), comparison_dimensions: [{ ...g.result.comparison_dimensions[0], evidence_refs: ['invented'] }, { criterion: 'x', course_values: [], source_status: 'SOURCED_CURRENT_FACT', evidence_refs: [] }] },
    handoff: { ...g.handoff, summary: 'z'.repeat(1601), confirmed_user_inputs: { answer_1: answer } }, confirmed_inputs: { answer_1: answer } }),
  s4({ ui: [{ ...g.ui[0], rows: [{ label: 'r', cells: ['one'] }], content: [{ label: '<script>x', detail: 'onclick = y', source_status: 'SOURCED_CURRENT_FACT', evidence_refs: [] }] }, { ...g.ui[0], component: 'action_handoff', id: ' ', required: true, options: [{ id: 'go', label: 'Go', detail: '' }, { id: 'go', label: 'Go', detail: '' }] }] }),
  s4({ status: 'NEEDS_INPUT', ui: [], readiness: { enabled: false, name: 'x', score: null, dimensions: [], blockers: [] } }),
  s4({ status: 'COMPLETE', ui: [{ ...g.ui[0], component: 'entity_picker', required: true }, { ...g.ui[0], id: 'g2', component: 'evidence_panel', required: true }], session: undefined }),
  s4({ result: { summary: 'Only summary' }, readiness: { enabled: false, name: null, score: null, dimensions: [], blockers: [] } }),
  s4({ result: { summary: 'Partial', courses: ['A'] } }),
  s4({ readiness: { enabled: true, name: 'Comparison readiness', score: 60, dimensions: [{ name: 'Evidence', score: 60, reason: 'Some data' }], blockers: [] }, ui: [{ ...g.ui[0], component: 'evidence_panel' }] }),
];
for (const v of variants4) add('validateOutput', 'STUDY_004', typeof v === 'string' ? v : JSON.stringify(v), s4ctx);
const d1ctx = { user_message: 'What interests me?', confirmed_facts: {}, approved_evidence: [], session: { interaction_count: 5 } };
const d1 = (ui: any[], o: any = {}) => ({ objective_id: 'DISCOVER_001', status: 'NEEDS_INPUT', stage: 'UNDERSTAND', ui, confirmed_inputs: {}, derived_signals: [], unknowns: [],
  result: { summary: 's', interest_themes: [], unknowns: [], next_actions: [] }, readiness: { enabled: false, name: null, score: null, dimensions: [], blockers: [] },
  next_actions: [], handoff: { summary: 'h', confirmed_user_inputs: {}, result: { summary: 'r' }, evidence_and_unknowns: [], recommended_next_action: 'n' }, ...o });
const cmp = (component: string, o: any = {}) => ({ component, id: component, purpose: 'p', prompt: 'q', required: true, options: [], content: [], columns: [], rows: [],
  settings: { allow_unsure: false, min: null, max: null, min_label: null, max_label: null, buckets: [] }, ...o });
for (const v of [d1([cmp('multi_select', { options: [{ id: 'a', label: 'A', detail: '' }] })]), d1([cmp('spectrum', { settings: { allow_unsure: false, min: 5, max: 5, min_label: 'lo', max_label: '' } })]),
  d1([cmp('card_sort', { options: [{ id: 'a', label: 'A', detail: '' }, { id: 'b', label: 'B', detail: '' }], settings: { allow_unsure: false, min: null, max: null, min_label: null, max_label: null, buckets: ['x'] } })]),
  d1([cmp('short_answer'), cmp('multi_select', { id: 'm2', required: false })], { status: 'NEEDS_RESEARCH' }), d1([], { status: 'NEEDS_INPUT' }),
  d1([cmp('spectrum', { settings: { allow_unsure: true, min: 1, max: 9, min_label: 'a', max_label: 'b' } })])])
  add('validateOutput', 'DISCOVER_001', JSON.stringify(v), d1ctx);

// ---- run
const undef = (v: any) => (v && typeof v === 'object' && !Array.isArray(v) && v.__undef ? undefined : v);
const deep = (v: any): any => Array.isArray(v) ? v.map(deep) : v && typeof v === 'object' ? (v.__undef ? undefined : Object.fromEntries(Object.entries(v).map(([k, x]) => [k, deep(x)]).filter(([, x]) => x !== undefined))) : v;
const memResult = (args: any[]) => { const r = new mm.TokenBudgetMemoryManager().assembleMemory(...args.map(deep) as [any]);
  if (r.compactionMetrics) { r.compactionMetrics.timestamp = 0; r.compactionMetrics.compactionEventId = r.compactionMetrics.compactionEventId.replace(/\d+$/, 'N'); } return r; };
const turnsFor = (key: string) => key === 'none' ? [] : key === 'all' ? mm.groupIntoTurns(messages) : key === 'mem' ? mem.keptTurns : (() => { const [, s, b, k] = key.split(':'); return memResult([messages, +b, +k, s, 'Prior summary text here', 'sydney data analyst python salaries']).keptTurns; })();
const run: Record<string, (...a: any[]) => any> = {
  resolveThinkingConfig: (m, l, p) => A.resolveThinkingConfig(m, l, p),
  classifyUserMessage: (t, c) => A.classifyUserMessage(t, c),
  bypassCopy: k => bypassCopy(k),
  formatUserEvent: (t, e, m) => A.formatUserEvent(t, e, m),
  formatCareerContext: c => A.formatCareerContext(c),
  normalizeModeCapitalization: m => A.normalizeModeCapitalization(m),
  sanitizeSchema: s => (A as any).sanitizeSchemaForGemini(s),
  geminiResponseSchema: () => A.getGeminiResponseSchema(),
  warehouseInstruction: () => WAREHOUSE_INSTRUCTION,
  promptHash: () => A.getPromptHash(),
  schemaHash: () => A.getSchemaHash(),
  estimateTokens: xs => xs.map((x: any) => mm.estimateTokens(undef(x))),
  groupIntoTurns: ms => mm.groupIntoTurns(ms),
  formatAssistantMessageRich: r => mt.formatAssistantMessageRich(r),
  formatAssistantMessageForContext: r => mm.formatAssistantMessageForContext(r),
  assembleMemory: (...a) => memResult(a),
  buildMultiTurnContents: (cap, sum, turns, cur, richHistory) => { const c = mt.buildMultiTurnContents({ careerCapsule: cap, summary: sum, keptTurns: turnsFor(turns), currentUserInput: cur, richHistory });
    return { contents: c, tokens: mt.estimateContentsTokens(c) }; },
  shortReplyGuidance: (t, h, s) => shortReplyGuidance(t, h, s),
  assembleRequest: p => { const q = deep({ ...p }); if (typeof p.keptTurns === 'string') q.keptTurns = turnsFor(p.keptTurns);
    const r: any = A.assembleRequest(q); delete r.aiRequestId; delete r.timeline;
    // The 213KB prompt and the schema are compared in full by other cases; here their SHA-256 is enough.
    const out = { ...r, systemInstruction: sha(r.systemInstruction) + ':' + r.systemInstruction.length + ':' + r.systemInstruction.slice(-40), geminiConfig: { ...r.geminiConfig } };
    out.geminiConfig.systemInstruction = out.systemInstruction;
    if (out.geminiConfig.responseSchema) out.geminiConfig.responseSchema = sha(JSON.stringify(out.geminiConfig.responseSchema));
    return { ...out, contentsTokens: mt.estimateContentsTokens(r.contents), systemTokens: mm.estimateTokens(r.systemInstruction) }; },
  sharedConversationContext: (c, s, q) => sharedConversationContext(c, s, undef(q)),
  workspaceQuestionOwner: (s, v) => workspaceQuestionOwner(s, v),
  readActivityContext: (r, u) => readActivityContext(deep(r), u),
  splitGeminiStreamChunk: c => splitGeminiStreamChunk(c),
  formatUserEventRaw: (t, raw, m) => A.formatUserEvent(t, JSON.parse(raw), m),
  needsWarehouse: (m, c) => needsWarehouse(m, c),
  objectiveWire: () => WIRE_INSTRUCTION,
  workspaceInstructions: id => workspaceInstructions(byId(id)),
  contractWarnings: () => Object.fromEntries(registry.objectives.map((o: any) => { const r = compileResult(o.qa_output_contract_v2 || ''); return [o.tool_id, { keys: r.completionKeys, warnings: r.warnings }]; })),
  compileResultDsl: c => compileResult(c),
  plannerSchema: (id, ctx) => plannerSchema(byId(id), ctx),
  objectiveProviderSchema: (id, ctx) => objectiveProviderSchema(plannerSchema(byId(id), ctx)),
  validateOutput: (id, raw, ctx) => validateOutput(raw, byId(id), ctx),
  objectiveStart: async (conv, b, outputs) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'objparity-'));
    const payloads: any[] = [], queue = [...outputs];
    const svc = new ObjectiveService(root, async (request: any) => { payloads.push(JSON.parse(JSON.stringify(request))); if (!queue.length) throw Error('No stub output'); return queue.shift(); },
      async () => ({ status: 'NOT_NEEDED', message: '', queries: [], courses: [], retrievedAt: '2026-01-01T00:00:00.000Z', sourcePolicy: 'USER_APPROVED_CATALOGUE' }));
    try {
      const s: any = await svc.start(conv, b, new AbortController().signal);
      return { payloads, session: { objectiveId: s.objectiveId, label: s.label, sourceMessageId: s.sourceMessageId, revision: s.revision, interactionCount: s.interactionCount, state: s.state,
        plan: s.plan, context: s.context, answers: s.answers, activation: s.activation, routing: s.routing, autoHandoff: s.autoHandoff } };
    } catch (e: any) { return { payloads, err: String(e?.message || e) }; }
    finally { fs.rmSync(root, { recursive: true, force: true }); }
  },
};
const { needsWarehouse } = await import(O + 'warehouse/service.ts');
const results: any[] = [];
for (const c of cases) { try { const v = await run[c.fn](...c.args.map(undef)); results.push(v === undefined ? { undef: true } : { ok: JSON.stringify(v) }); } catch (e: any) { results.push({ err: String(e?.message || e) }); } }
fs.writeFileSync(new URL('../resources/parity/request-assembly.json', import.meta.url), JSON.stringify({ cases, results }, null, 1));
console.log('cases', cases.length, 'errors', results.filter(r => 'err' in r).length);
