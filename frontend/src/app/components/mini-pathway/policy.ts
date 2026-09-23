import { ChatMessage, YuzeeResponseV13 } from '../../models/types';
import { acceptedResponse } from '../../utils/response-presentation';

/** Port of the original miniPathway/policy.ts (+ routing/bgeProfiles pathway gate,
 * routing/skillSuggestions allowSkillReview). */

export const MINI_PATHWAY_THRESHOLD = 40;
export const MINI_PATHWAY_VERSION = 'mini-pathway-v2-report-contract';
const BGE_MODEL_ID = 'Xenova/bge-small-en-v1.5';
const PATHWAY_PROFILE = { score: .70, margin: .08, version: 'bge-pathway-v2' };

export type PathwayHint = { status: 'selected' | 'abstained'; score?: number; margin?: number; reason: string; modelId?: string; profileVersion?: string; failedGates?: string[] };
export type PathwayDecision = { action: 'automatic' | 'offer' | 'none'; reason: string; score: number | null };

/** A message's accepted v1.3 response, as the original reads it: acceptedResponse(m.structuredResponse||m.content). */
export function messageResponse(m: ChatMessage | undefined | null): YuzeeResponseV13 | null {
  return m ? acceptedResponse(m.structuredResponse || m.content) : null;
}

export function messageText(content: unknown): string {
  return typeof content === 'string' ? content : content == null ? '' : JSON.stringify(content);
}

function allowSkillReview(userText: string): boolean {
  return !/\b(stop|pause|cancel|no (?:more |extra )?(?:questions|suggestions|follow.up)|do not suggest|don[’']t suggest|suicid\w*|self.harm|emergency)\b/i.test(userText);
}

/** routing/bgeProfiles taskRanking for the pathway task. */
function taskRanking(candidates: { id: string; score: number }[], allowed: readonly string[], gate: { score: number; margin: number }) {
  const ranking = candidates.filter(c => allowed.includes(c.id) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1)
    .sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.id === c.id) === i);
  const [first, second] = ranking;
  const failedGates = !second ? ['incomplete-ranking'] : [...(first.score < gate.score ? ['min-similarity'] : []), ...(first.score - second.score < gate.margin ? ['top2-margin'] : []), ...(first.id === 'other' ? ['background-wins'] : [])];
  return { ranking, failedGates, selected: failedGates.length === 0, id: first?.id, score: first?.score, margin: second ? first.score - second.score : undefined };
}

export function choosePathwayHint(candidates: { id: string; score: number }[], modelId?: string): PathwayHint {
  if (modelId === BGE_MODEL_ID) {
    const r = taskRanking(candidates, ['pathway', 'other'], PATHWAY_PROFILE);
    return { status: r.selected ? 'selected' : 'abstained', score: r.score, margin: r.margin, reason: r.selected ? 'pathway-relevant' : r.id === 'other' ? 'not-pathway' : 'uncertain', modelId, profileVersion: PATHWAY_PROFILE.version, failedGates: r.failedGates };
  }
  const ranked = candidates.filter(c => ['pathway', 'other'].includes(c.id) && Number.isFinite(c.score) && c.score >= -1 && c.score <= 1)
    .sort((a, b) => b.score - a.score).filter((c, i, a) => a.findIndex(x => x.id === c.id) === i);
  if (ranked.length < 2 || ranked[0].id !== 'pathway') return { status: 'abstained', reason: 'not-pathway' };
  const score = ranked[0].score, margin = score - ranked[1].score;
  return score >= .48 && margin >= .06 ? { status: 'selected', score, margin, reason: 'pathway-relevant' } : { status: 'abstained', reason: 'uncertain' };
}

export function validPathwayHint(h: any): h is PathwayHint {
  const bge = h?.modelId === BGE_MODEL_ID, gate = bge ? PATHWAY_PROFILE : { score: .48, margin: .06 };
  if (h?.modelId && (!bge || h.profileVersion !== PATHWAY_PROFILE.version)) return false;
  return h?.status === 'selected' && Number.isFinite(h.score) && h.score >= gate.score && h.score <= 1 && Number.isFinite(h.margin) && h.margin >= gate.margin && h.margin <= 2;
}

export function pathwayScore(response: any): number | null {
  const c = response?.state?.user_confidence;
  return Number.isInteger(c?.score) && c.score >= 0 && c.score <= 100 && c.evidence_strength !== 'none' && c.band !== 'unknown' ? c.score : null;
}

export function pathwayBoundary(response: any, userText: string): boolean {
  return !allowSkillReview(userText) || /\b(?:no|without|don't|do not)\s+(?:a\s+)?(?:mini\s+)?pathway\b/i.test(userText) ||
    !!response?.state?.safety_override_applied || response?.current_mode === 'S_SERVICE_HANDOFF' ||
    /SAFETY|SECURITY|CRITICAL_CLARIFICATION/.test(response?.response_intent || '') || !!response?.service_trigger?.trigger_now;
}

export function decideMiniPathway(response: unknown, userText: string, hint: PathwayHint, alreadyHelped = false): PathwayDecision {
  const r = acceptedResponse(response), score = pathwayScore(r);
  if (!r || pathwayBoundary(r, userText)) return { action: 'none', reason: 'invalid-or-boundary', score };
  if (!validPathwayHint(hint)) return { action: 'none', reason: 'no-relevant-match', score };
  // Unknown is not low; a relevant optional plan may still help without auto generation.
  return {
    action: score !== null && score < MINI_PATHWAY_THRESHOLD && !alreadyHelped ? 'automatic' : 'offer', score,
    reason: alreadyHelped ? 'already-helped' : score === null ? 'confidence-unknown' : score < MINI_PATHWAY_THRESHOLD ? 'low-decision-confidence' : 'optional-pathway',
  };
}

export function alreadyHelpedInLowEpisode(messages: Pick<ChatMessage, 'id' | 'role' | 'content' | 'structuredResponse'>[], runs: { sourceMessageId: string }[]): boolean {
  let index = -1; messages.forEach((m, i) => { if (runs.some(r => r.sourceMessageId === m.id)) index = i; });
  if (index < 0) return false;
  return !messages.slice(index + 1).some(m => {
    if (m.role !== 'assistant') return false;
    const r: any = acceptedResponse(m.structuredResponse || m.content);
    return (pathwayScore(r) ?? -1) >= MINI_PATHWAY_THRESHOLD || !!r?.state?.user_confidence?.reason_codes?.includes('NEW_TOPIC_RESET');
  });
}

export function pathwayQuery(response: any, userText: string): string {
  // Short relevance query, not the entire report.
  const title = response?.content_blocks?.find((b: any) => b.title)?.title || '';
  return userText.trim().split(/\s+/).length >= 5 || !title ? userText : `${userText}\nCurrent topic: ${String(title).slice(0, 200)}`;
}
