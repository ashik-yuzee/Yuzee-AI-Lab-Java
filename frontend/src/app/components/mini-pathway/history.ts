import { ChatMessage, YuzeeResponseV13 } from '../../models/types';
import { PathwayHint, messageText } from './policy';

/** Port of the original miniPathway/history.ts + presentation.ts, and the MiniPathwayRun shape from service.ts (runs arrive in that shape; createdAt is an ISO string). */
export type MiniPathwayRun = {
  id: string; conversationId: string; sourceMessageId: string; version: string;
  status: 'running' | 'complete' | 'error'; mode: 'automatic' | 'manual'; createdAt: string;
  response?: YuzeeResponseV13; error?: string; hint?: PathwayHint;
};

export function completedPathways(runs: MiniPathwayRun[], conversationId: string): MiniPathwayRun[] {
  const unique = new Map<string, MiniPathwayRun>();
  for (const run of runs) if (run.conversationId === conversationId && run.status === 'complete' && run.response) unique.set(run.id, run);
  return [...unique.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id));
}

export function selectSavedPathway(runs: MiniPathwayRun[], preferredId: string | null): MiniPathwayRun | null {
  return runs.find(r => r.id === preferredId) || runs.at(-1) || null;
}

export function pathwayContext(run: MiniPathwayRun, messages: ChatMessage[]): string {
  const index = messages.findIndex(m => (m.serverMessageId || m.id) === run.sourceMessageId);
  if (index < 0) return 'Saved from an earlier answer';
  const message = messages.slice(0, index).reverse().find(m => m.role === 'user');
  const text = messageText(message?.content).replace(/^\[QUESTION_ANSWERS:.*?\]\n/, '').replace(/\s+/g, ' ').trim() || '';
  return text ? text.length > 110 ? text.slice(0, 107) + '…' : text : 'Saved from an earlier answer';
}

export function pathwayDate(createdAt: string): string {
  const date = new Date(createdAt);
  return Number.isNaN(date.getTime()) ? 'Date unavailable' : date.toLocaleString('en-AU', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/** presentation.ts: promote table.text to a visible intro block; the shared renderer omits it. */
export function miniPathwayPanelResponse(response: YuzeeResponseV13): YuzeeResponseV13 {
  return {
    ...response, content_blocks: response.content_blocks.flatMap((block: any) => {
      if (!['table', 'comparison'].includes(block.type) || !block.text) return [block];
      return [
        { ...block, id: block.id + '-intro', type: 'text' as const, title: '', columns: [], rows: [], items: [] },
        { ...block, text: '' },
      ];
    }),
  } as YuzeeResponseV13;
}
