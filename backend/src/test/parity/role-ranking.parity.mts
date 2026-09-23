// Feeds fixed queries/candidate roles through the ORIGINAL src/warehouse/roleRanking.ts (pinned local BGE) and records
// the cosine scores, their order and the selected role ids. RoleRankingParityTest.java replays the same inputs in Java.
// Needs the pinned model in the original repo (scripts/download-bge-model.ts). Regenerate (cwd = original repo):
//   cd C:/websites/yuzee-ai-token-lab && npx tsx C:/websites/yuzee-ai-lab-java/backend/src/test/parity/role-ranking.parity.mts
import fs from 'fs';
import path from 'path';
const O = 'file:///C:/websites/yuzee-ai-token-lab/src/';
const { rankRoleCandidates } = await import(O + 'warehouse/roleRanking.ts');
const { loadEmbeddingModel } = await import(O + 'routing/loadEmbeddingModel.ts');
const { routerModel, DEFAULT_ROUTER_MODEL } = await import(O + 'routing/models.ts');
const { checkEmbeddingInput } = await import(O + 'routing/tokenBudget.ts');

const R = (id: string, title: string, description: string, tasks: string[]) =>
  ({ id, evidenceId: 'E-' + id, title, description, tasks, matchedSkills: [], skills: [], mappings: [], source: 'test', scope: 'test', matchReason: '' });
const nurse = R('r-nurse', 'Registered Nurse', 'Provides nursing care to patients in hospitals, aged care and community settings', ['Assess patient health', 'Administer medication', 'Record patient care', 'Educate families']);
const aged = R('r-aged', 'Aged or Disabled Carer', 'Provides general household assistance, emotional support, care and companionship for aged and disabled persons', ['Help clients bathe and dress', 'Prepare meals', 'Provide companionship']);
const dev = R('r-dev', 'Software Engineer', 'Designs, develops, modifies, documents, tests and maintains software applications', ['Write code', 'Test programs', 'Review designs']);
const support = R('r-ict', 'ICT Support Technician', 'Provides support to computer users by diagnosing and resolving hardware and software problems', ['Troubleshoot computer faults', 'Install software', 'Answer help desk queries', 'Maintain records']);
const retail = R('r-retail', 'Sales Assistant (General)', 'Sells goods and services to customers in retail stores', ['Greet customers', 'Operate cash register', 'Stock shelves']);
const csr = R('r-csr', 'Customer Service Representative', 'Answers enquiries and resolves complaints from customers by phone, email and in person', ['Respond to customer enquiries', 'Process orders', 'Resolve complaints']);
const sparky = R('r-elec', 'Electrician (General)', 'Installs, tests, connects, commissions, maintains and modifies electrical equipment, wiring and control systems', ['Read blueprints', 'Install wiring', 'Test circuits']);
const plumber = R('r-plumb', 'Plumber (General)', 'Installs and repairs water, drainage, gas and sewerage pipes and systems', ['Install pipes', 'Repair leaks', 'Read plans']);
const carpenter = R('r-carp', 'Carpenter', 'Constructs, erects, installs and repairs structures and fixtures of wood on building sites', ['Measure and cut timber', 'Build frameworks', 'Install fixtures']);
const labourer = R('r-lab', 'Building and Construction Labourer', 'Performs routine tasks on construction sites using hand and power tools', ['Clean sites', 'Carry materials', 'Operate power tools']);
const chef = R('r-chef', 'Chef', 'Plans and organises the preparation and cooking of food in a dining or catering establishment', ['Plan menus', 'Cook food', 'Supervise kitchen staff']);
const accountant = R('r-acct', 'Accountant (General)', 'Plans and provides systems and services relating to the financial dealings of organisations and individuals', ['Prepare financial statements', 'Audit accounts', 'Advise on tax']);
const barista = R('r-barista', 'Barista', 'Prepares and serves espresso coffee and other café beverages', ['Grind coffee', 'Steam milk', 'Create latte art']);
const cafe = R('r-cafe', 'Café or Restaurant Manager', 'Organises and controls the operations of a café or restaurant', ['Roster staff', 'Order supplies', 'Handle customer complaints']);
const nurseTwin = { ...nurse, id: 'r-nurse-twin', evidenceId: 'E-r-nurse-twin' };
const nurse3 = { ...nurse, id: 'r-nurse-3', evidenceId: 'E-r-nurse-3' }, nurse4 = { ...nurse, id: 'r-nurse-4', evidenceId: 'E-r-nurse-4' };
const longRole = R('r-long', 'Community Services Worker', 'Supports individuals and families in the community with welfare, housing and health needs. '.repeat(60), ['Case management', 'Referral to services', 'Crisis support', 'This fourth task is never embedded']);
const nullish = R('r-empty', '', '', []);
const longMessage = 'I have worked in hospitality for years and I am thinking about moving into healthcare or community work where I can help people directly. '.repeat(20);

const cases = [
  { message: 'I like helping people and caring for the elderly', skills: ['patient care', 'empathy', 'active listening'], roles: [nurse, aged, retail, dev] },
  { message: 'I enjoy fixing computers and helping people with tech problems', skills: ['troubleshooting', 'customer service', 'technical support'], roles: [support, dev, retail, sparky, csr] },
  { message: 'hello', skills: [], roles: [chef, accountant] },
  { message: 'I want to care for patients in a hospital', skills: ['nursing'], roles: [nurse, retail, nurseTwin, aged] },
  { message: longMessage, skills: ['case management', 'crisis support'], roles: [longRole, nurse, chef, aged] },
  { message: 'hands-on trade work with tools on construction sites', skills: ['power tools', 'reading plans', 'repairing'], roles: [sparky, plumber, carpenter, labourer, accountant] },
  { message: 'Café barista with latte art — naïve question: what jobs?', skills: ['espresso', 'customer service'], roles: [barista, chef, cafe, dev] },
  { message: 'I like helping people and caring for the elderly', skills: ['patient care', 'empathy', 'active listening'], roles: [dev, retail, aged, nurse] },
  { message: 'I want to care for patients in a hospital', skills: ['nursing'], roles: [aged, nurse4, nurse, nurseTwin, nurse3] },
  { message: 'zzz 0000 ####', skills: [], roles: [plumber, accountant, sparky] },
  { message: 'my cat likes to sleep on the sofa', skills: [], roles: [plumber, accountant] },
  { message: 'Numbers, spreadsheets & tax; also C++/Java?', skills: ['bookkeeping', 'Excel', 'programming'], roles: [accountant, dev, csr, nullish] },
];

const config = routerModel(DEFAULT_ROUTER_MODEL);
const embed = await loadEmbeddingModel(config, { sourceDirectory: path.resolve('data/minilm-cache', config.id, config.revision), device: 'cpu', local_files_only: true, session_options: { intraOpNumThreads: 1, interOpNumThreads: 1 } });
async function encode(text: string) { // same steps as roleRanking.ts encode()
  while (text.length && !checkEmbeddingInput(embed.tokenizer, text, 512).fits) text = text.slice(0, Math.floor(text.length * .85));
  return { len: text.length, vector: Array.from((await embed(text, { pooling: 'cls', normalize: true })).data as Float32Array) };
}
const out = [];
for (const c of cases) {
  const q = await encode(`${c.message.slice(0, 1800)}\nSkills being explored: ${c.skills.join(', ')}`);
  const ranked = [];
  for (const role of c.roles) {
    const v = await encode(`${role.title}. ${role.description}. Tasks: ${role.tasks.slice(0, 3).join('; ')}`);
    ranked.push({ id: role.id, textLength: v.len, score: v.vector.reduce((sum, x, i) => sum + x * q.vector[i], 0) });
  }
  ranked.sort((a, b) => b.score - a.score);
  const best = ranked[0]?.score ?? 0;
  const expected = ranked.filter(r => r.score >= .55 && r.score >= best - .06).slice(0, 3).map(r => r.id);
  const selected = await rankRoleCandidates(c.message, c.skills, c.roles as any, new AbortController().signal);
  if (JSON.stringify(selected) !== JSON.stringify(expected)) throw Error(`script scores disagree with roleRanking.ts: ${selected} vs ${expected}`);
  out.push({ message: c.message, skills: c.skills, roles: c.roles.map(r => ({ id: r.id, title: r.title, description: r.description, tasks: r.tasks })), queryTextLength: q.len, ranked, selected });
}
const file = 'C:/websites/yuzee-ai-lab-java/backend/src/test/resources/parity/role-ranking.json';
fs.writeFileSync(file, JSON.stringify({ model: config.id, revision: config.revision, cases: out }, null, 1) + '\n');
console.log(`wrote ${out.length} cases to ${file}`);
for (const c of out) console.log(c.selected, c.ranked.map(r => `${r.id}=${r.score.toFixed(4)}`).join(' '));
