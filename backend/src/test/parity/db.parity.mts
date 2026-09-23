// Runs the ORIGINAL db.ts against a recording node-postgres stub (db/pg-stub.mjs) and records every query it sends
// (SQL text and parameter values): initDb(), saveConversation() and saveMessage() for every conversation and message
// the persistence flows produced (parity/persistence/files.json), logTurn(), deleteConversation(), pruneExpired() and
// the stats loaders. DbQueryParityTest.java sends the same inputs through JdbcConversationRepository and
// JdbcConversationLogService and requires the same queries and parameters. Nothing connects to a database. Regenerate
// (after persistence.parity.mts):
//   cd C:/websites/yuzee-ai-token-lab && npx tsx --import file:///C:/websites/yuzee-ai-lab-java/backend/src/test/parity/db/register.mjs C:/websites/yuzee-ai-lab-java/backend/src/test/parity/db.parity.mts
import fs from 'fs';

const ORIGINAL = 'C:/websites/yuzee-ai-token-lab';
const OUT = new URL('../resources/parity/persistence/', import.meta.url);
(globalThis as any).__pgLog = [];
process.env.DATABASE_URL = 'postgres://stub';
const db = await import('file:///' + ORIGINAL + '/src/services/db.ts');

await db.initDb();
const golden = JSON.parse(fs.readFileSync(new URL('files.json', OUT), 'utf8'));
for (const conv of JSON.parse(golden.files['data/conversations.json'])) {
  await db.saveConversation(conv);
  for (const m of conv.messages) await db.saveMessage(m, conv.id);
}
const turns = JSON.parse(fs.readFileSync(new URL('db-turns.json', OUT), 'utf8'));
for (const t of turns) await db.logTurn(t.assistantOutput === 'LONG' ? { ...t, assistantOutput: 'x'.repeat(4100) } : t);
await db.deleteConversation('conv-gone');
await db.pruneExpired();
await db.loadLifetimeStats();
await db.loadDailyCost();
await db.loadSessionStats();
fs.writeFileSync(new URL('db-queries.json', OUT), JSON.stringify((globalThis as any).__pgLog, null, 1));
console.log('queries', (globalThis as any).__pgLog.length);
process.exit(0);
