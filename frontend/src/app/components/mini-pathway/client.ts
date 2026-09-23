import { YuzeeContentBlock } from '../../models/types';
import { PathwayHint } from './policy';
import { MiniPathwayRun } from './history';

/** Port of the original miniPathway/client.ts (fetch + SSE frames), with the auth header this app needs. */
export type PathwayDraftEvent = { type: 'reset' } | { type: 'block'; block: YuzeeContentBlock };
type Request = { sourceMessageId: string; mode: 'automatic' | 'manual'; hint: PathwayHint; location: string };

const authHeaders = (token: string | null): Record<string, string> => token ? { Authorization: `Bearer ${token}` } : {};
const url = (conversationId: string) => `/api/conversations/${encodeURIComponent(conversationId)}/mini-pathway`;

export async function listMiniPathways(conversationId: string, token: string | null, signal: AbortSignal): Promise<MiniPathwayRun[]> {
  const r = await fetch(url(conversationId), { headers: authHeaders(token), signal });
  if (!r.ok) throw Error('Saved mini pathways could not be loaded.');
  return r.json();
}

export async function generateMiniPathway(conversationId: string, token: string | null, request: Request, signal: AbortSignal,
  onProgress: (stage: string) => void, onDraft: (event: PathwayDraftEvent) => void = () => {}): Promise<MiniPathwayRun> {
  const r = await fetch(url(conversationId), { method: 'POST', headers: { 'Content-Type': 'application/json', ...authHeaders(token) }, body: JSON.stringify(request), signal });
  if (!r.ok || !r.body) throw Error(r.status === 429 ? 'Please wait a moment before trying again.' : 'The mini pathway could not be started.');
  const reader = r.body.getReader(), decoder = new TextDecoder();
  let buffer = '', result: MiniPathwayRun | undefined;
  try {
    while (true) {
      const part = await reader.read(); buffer += decoder.decode(part.value, { stream: !part.done });
      let end: number;
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, end); buffer = buffer.slice(end + 2);
        if (!frame.startsWith('data: ')) continue;
        const event = JSON.parse(frame.slice(6));
        if (signal.aborted) throw new DOMException('Stopped', 'AbortError');
        if (event.type === 'progress') onProgress(event.stage);
        if (event.type === 'reset' || event.type === 'block') onDraft(event);
        if (event.type === 'error') throw Error(event.error);
        if (event.type === 'result') result = event.result;
      }
      if (part.done) break;
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  if (!result) throw Error('The mini pathway stopped before finishing. You can try again.');
  return result;
}
