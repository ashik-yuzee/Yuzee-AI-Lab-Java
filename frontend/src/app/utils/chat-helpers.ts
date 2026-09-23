/**
 * Small pure helpers ChatArea.tsx / SkillSuggestions.tsx import from the original app, ported
 * verbatim: data/models.ts (pricing subset), services/HelpEvidence.ts#outdatedHelpClaim,
 * orchestration/questionOwner.ts, objectives/activityContext.ts, routing/turnNeeds.ts#researchOffer and
 * routing/skillSuggestions.ts.
 */

// ---- data/models.ts (only the fields ChatArea reads) ----
interface ModelPrice { id: string; name: string; inputPricePerMToken?: number; outputPricePerMToken?: number; cachedReadPricePerMToken?: number; }
export const GEMINI_MODELS: ModelPrice[] = [
  { id: 'gemini-3.7-flash', name: 'Gemini 3.7 Flash', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.8-flash', name: 'Gemini 3.8 Flash', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.6-flash', name: 'Gemini 3.6 Flash', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.5-flash', name: 'Gemini 3.5 Flash', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.5-flash-lite', name: 'Gemini 3.5 Flash-Lite', inputPricePerMToken: 0.075, outputPricePerMToken: 0.30, cachedReadPricePerMToken: 0.019 },
  { id: 'gemini-3.1-flash-lite', name: 'Gemini 3.1 Flash-Lite', inputPricePerMToken: 0.075, outputPricePerMToken: 0.30, cachedReadPricePerMToken: 0.019 },
  { id: 'gemini-2.5-flash', name: 'Gemini 2.5 Flash', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-2.5-flash-lite', name: 'Gemini 2.5 Flash-Lite', inputPricePerMToken: 0.038, outputPricePerMToken: 0.15, cachedReadPricePerMToken: 0.010 },
  { id: 'gemini-2.0-flash', name: 'Gemini 2.0 Flash' },
  { id: 'gemini-2.0-flash-lite', name: 'Gemini 2.0 Flash-Lite' },
];

export function calcTurnCost(modelId: string, usage: { uncachedInputTokens?: number | null; inputTokens: number; outputTokens: number; thinkingTokens?: number | null; cachedTokens?: number | null }): number | null {
  const model = GEMINI_MODELS.find(m => m.id === modelId);
  if (!model?.inputPricePerMToken || !model?.outputPricePerMToken) return null;
  const uncachedInput = usage.uncachedInputTokens ?? (usage.inputTokens - (usage.cachedTokens ?? 0));
  const outputTotal = usage.outputTokens + (usage.thinkingTokens ?? 0);
  const cached = usage.cachedTokens ?? 0;
  return (Math.max(0, uncachedInput) / 1_000_000) * model.inputPricePerMToken +
    (outputTotal / 1_000_000) * model.outputPricePerMToken +
    (cached / 1_000_000) * (model.cachedReadPricePerMToken ?? 0);
}

export function formatCost(usd: number): string {
  if (usd < 0.00001) return '~<$0.00001';
  if (usd < 0.01) return `~$${usd.toFixed(5)}`;
  return `~$${usd.toFixed(4)}`;
}

export function modelShortName(modelId: string): string {
  const found = GEMINI_MODELS.find(m => m.id === modelId);
  return found ? found.name.replace('Gemini ', '') : modelId;
}

/** 'lite' | 'legacy' | 'current' — selects the chip colour class (ChatArea.tsx#modelChipStyle). */
export function modelChipKind(modelId: string): 'lite' | 'legacy' | 'current' {
  if (modelId.includes('lite')) return 'lite';
  if (modelId.includes('2.5') || modelId.includes('2.0')) return 'legacy';
  return 'current';
}

// ---- services/HelpEvidence.ts ----
export function concernsHelp(text: string) { return /\b(?:HECS|HELP (?:loan|debt|repayment)|student loan repayment)\b/i.test(text); }
export function outdatedHelpClaim(text: string) {
  if (!concernsHelp(text)) return false;
  const pattern = /\b1\s*%\s*(?:to|–|-)\s*10\s*%|(?:start|begin)s?\s+at\s+1\s*%|lowest threshold tier/gi;
  return [...text.matchAll(pattern)].some(m => !/(?:outdated|no longer|not current|old system|previous system)/i.test(text.slice(Math.max(0, m.index! - 100), m.index! + m[0].length + 50)));
}

// ---- orchestration/questionOwner.ts ----
export function workspaceQuestionOwner(sessions: any[], visibleSessionId?: string | null) {
  const session = sessions.find(s => s.id === visibleSessionId && s.state === 'ACTIVE');
  const question = session?.plan?.ui?.find((c: any) => c.required && c.component !== 'action_handoff');
  if (!session || !question || session.pendingAnswer || session.pendingCorrection) return { question_owner: 'CHAT' as const };
  return { question_owner: 'WORKSPACE' as const, session_id: session.id, objective: session.label, question_id: question.id, question: question.prompt };
}

export function shouldDeferChatQuestion(response: any, coordination: ReturnType<typeof workspaceQuestionOwner>) {
  return coordination.question_owner === 'WORKSPACE' && response?.interaction?.kind === 'question' &&
    !response?.state?.safety_override_applied && !/SAFETY|SECURITY|CRITICAL_CLARIFICATION/.test(response?.response_intent || '') && !response?.service_trigger?.trigger_now;
}

// ---- objectives/activityContext.ts ----
const ctxText = (v: unknown, max = 300) => typeof v === 'string' ? v.trim().slice(0, max) : '';
const ctxList = (v: unknown) => Array.isArray(v) ? v.slice(0, 4).map(x => ctxText(x, 180)).filter(Boolean) : [];
function readActivityContext(response: any, userMessages: string[]) {
  const c = response?.state?.activity_context; if (!c || typeof c !== 'object' || Array.isArray(c)) return null;
  const facts = ctxList(c.confirmed_facts).filter(q => userMessages.some(m => m.includes(q)));
  return { current_goal: ctxText(c.current_goal), confirmed_facts: facts, possible_need: ctxText(c.possible_need), missing_information: ctxList(c.missing_information), relevant_question: ctxText(c.relevant_question), user_constraints: ctxList(c.user_constraints) };
}
export function activitySearchContext(response: any, userMessages: string[]): string {
  const c = readActivityContext(response, userMessages);
  if (!c) return userMessages.slice(-3, -1).join('\n').slice(-2400); // No assistant prose in fallback.
  const referenceContext = (userMessages.at(-1) || '').length < 80 ? userMessages.slice(-3, -1).join('\n').slice(-1200) : '';
  return [c.current_goal, c.confirmed_facts.join('. '), referenceContext].filter(Boolean).join('\n').slice(0, 2400);
}

// ---- routing/turnNeeds.ts ----
export function researchOffer(plan: any): any {
  return plan?.version === 'turn-needs-v1' && plan.action === 'research' && plan.research && plan.scope?.target ? plan : undefined;
}

// ---- routing/skillSuggestions.ts ----
export type SkillOffer = { toolId: string; label: string; description: string; score: number };
export type SkillReview = { status: 'ready' | 'abstained'; offers: SkillOffer[]; reason: string };
export type SkillChoice = { toolId: string; sourceMessageId: string };
export const SKILL_LABELS: Record<string, string> = { COURSE_011: 'Understand my study costs', COURSE_012: 'Explore study and placement commitments', CORE_010: 'Check the supporting evidence', CAREER_004: 'Plan my next career step' };
/** policy.ts: these catalogue entries describe internal control operations, not user-facing answers. */
export const INTERNAL_ONLY_TOOLS = new Set(['CORE_001', 'CORE_002']);

/** `eligibleTools` is the router's bundled catalogue minus INTERNAL_ONLY_TOOLS (RoutingService loads it before 'ready'). */
export function skillMessage(toolId: string, eligibleTools: { id: string; name: string }[]): string {
  const t = eligibleTools.find(t => t.id === toolId);
  return t ? `Help me explore ${t.name.toLowerCase()} in more detail, using our conversation so far. Explain what matters for my situation and what still needs checking.` : '';
}

export function allowSkillReview(userText: string): boolean {
  return !/\b(stop|pause|cancel|no (?:more |extra )?(?:questions|suggestions|follow.up)|do not suggest|don[’']t suggest|suicid\w*|self.harm|emergency)\b/i.test(userText);
}

export function canReviewResponseSkills(response: any, userText: string): boolean {
  return !!response && allowSkillReview(userText) &&
    ['none', 'question'].includes(response.interaction?.kind) &&
    response.current_mode !== 'S_SERVICE_HANDOFF' && !response.service_trigger?.trigger_now &&
    !response.state?.safety_override_applied &&
    !/SAFETY|SECURITY|CRITICAL_CLARIFICATION/.test(response.response_intent || '') &&
    (response.content_blocks || []).some((b: any) => b.text?.trim() || b.items?.length || b.rows?.length);
}

export function suggestionText(response: any, userMessages: string[]): string {
  const topic = response.content_blocks?.find((b: any) => b.title)?.title || '';
  const sections = userMessages.length ? [`Conversation context. ${userMessages.join('. ')}. Current topic: ${topic}`] : [];
  for (const b of response.content_blocks || []) {
    const heading = b.title || topic;
    const items = (b.items || []).map((i: any) => [i.title, i.text, i.value].filter(Boolean).join('. '));
    if (b.text) sections.push([heading, b.text].filter(Boolean).join('. '));
    for (const item of items) sections.push([heading, item].filter(Boolean).join('. '));
    if (!b.text && !items.length && b.type !== 'heading') sections.push(JSON.stringify(b));
  }
  for (const a of response.interaction?.recommended_actions || []) sections.push(`${a.label}. ${a.message}`);
  return sections.join('\n\n');
}
