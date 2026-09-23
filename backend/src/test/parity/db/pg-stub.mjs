// Stand-in for node-postgres while db.ts runs under db.parity.mts: every query is recorded, nothing connects.
class Pool {
  constructor() {}
  async query(sql, params) {
    globalThis.__pgLog.push({ sql, params: (params || []).map(p => p instanceof Date ? { date: p.toISOString() } : p) });
    return { rows: [{}], rowCount: 0 };
  }
}
export default { Pool };
