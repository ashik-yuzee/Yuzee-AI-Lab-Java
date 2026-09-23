// Stand-in for @google/genai while the original server.ts runs under sse.parity.mts: every call is scripted.
export class GoogleGenAI {
  constructor() {
    const g = () => globalThis.__genai;
    this.models = {
      generateContentStream: async (req) => g().stream(req),
      generateContent: async (req) => g().generate(req),
    };
    this.caches = {
      create: async () => { throw Object.assign(new Error('caching unsupported'), { status: 400 }); },
      delete: async () => {},
      update: async () => {},
    };
  }
}
