/**
 * Port of yuzee-ai-token-lab/src/research/types.ts (DetailRequest/DetailResult/etc.) and the
 * researchOffer()-shaped slice of src/routing/turnNeeds.ts's TurnNeeds.
 *
 * Field names match the Java backend's Jackson getters exactly (camelCase), see:
 *   backend/src/main/java/com/yuzee/tokenlab/model/{DetailRequest,DetailAnswer,DetailResult,
 *   Evidence,DetailSource,TurnNeeds}.java
 */

export type DetailStatus = 'answered' | 'partial' | 'needs_clarification' | 'no_evidence';
export type FactKind = 'source_backed' | 'inference' | 'benchmark';
export type NextQuestionKind = 'ask_user' | 'suggested_question';

export interface DetailRequest {
  parentMessageId: string;
  target: string;
  question: string;
  studyYear: string;
  location: string;
  refresh?: boolean;
}

export interface DetailFact {
  text: string;
  kind: FactKind;
  evidenceIds: string[];
}

export interface DetailNextQuestion {
  kind: NextQuestionKind;
  text: string;
}

export interface Evidence {
  id: string;
  text: string;
  sourceIds: string[];
}

export interface DetailSource {
  id: string;
  title: string;
  url: string;
}

export interface DetailUsage {
  inputTokens: number;
  outputTokens: number;
  searchQueries: number;
  calls: number;
}

export interface DetailAnswer {
  status: DetailStatus;
  summary: string;
  facts: DetailFact[];
  gaps: string[];
  nextQuestions: DetailNextQuestion[];
}

export interface DetailResult extends DetailAnswer {
  policyVersion: string;
  id: string;
  conversationId: string;
  request: DetailRequest;
  retrievedAt: string;
  sources: DetailSource[];
  evidence: Evidence[];
  /** Rendered in a sandboxed, script-free iframe by ResearchAnswerCardComponent, as in the original. */
  searchSuggestionsHtml: string;
  usage: DetailUsage;
}

/** One SSE event from POST /api/conversations/{id}/details (see ChatController.generateDetails). */
export interface DetailStreamEvent {
  phase?: string;
  done?: boolean;
  details?: DetailResult;
  error?: string;
}

/**
 * The minimal slice of the server's TurnNeeds classification (TurnNeeds.java) this panel needs
 * to decide whether to render and how to pre-fill its scope form. Not yet wired onto assistant
 * messages — the integration engineer binds this once TurnNeedsService.assessTurnNeeds()/
 * researchOffer() are attached to the chat turn response.
 */
export interface ResearchOfferScope {
  target: string;
  studyYear: string;
  location: string;
}

export interface ResearchOfferResearch {
  title: string;
  description: string;
}

export interface ResearchOffer {
  question: string;
  scope: ResearchOfferScope;
  research?: ResearchOfferResearch;
}
