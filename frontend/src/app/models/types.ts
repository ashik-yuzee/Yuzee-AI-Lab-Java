import { WarehousePack } from '../components/warehouse/warehouse.types';

export type ModelId = string;
export type ThinkingLevel = 'minimal' | 'low' | 'medium' | 'high' | 'adaptive';
export type OptimizationMode = 'AUTO' | 'SAVE_TOKENS' | 'FULL_CONTEXT' | 'ADVANCED' | 'VANILLA' | 'MICRO_PROMPT';
export type OptimizationStrategy = 'BASELINE' | 'SLIDING_WINDOW' | 'SUMMARY_RECENT' | 'ADAPTIVE_HYBRID' | 'SEMANTIC_EVIDENCE';
export type PresetMode = 'MAX_QUALITY' | 'BALANCED' | 'MAX_SAVINGS' | 'ADVANCED' | 'VANILLA';
export type ResponseMode = 'quick' | 'standard' | 'explain' | 'explore' | 'detail' | 'decide' | 'vanilla';
export type ChatProgressPhase = 'routing' | 'waiting' | 'thinking' | 'receiving' | 'checking' | 'reviewing' | 'retrying';

// ---------------------------------------------------------------------------
// Yuzee Response Protocol (v1.3 / v1.4) — see:
//   yuzee-ai-token-lab/src/protocol/v1.3/Yuzee_Response_Protocol_v1.3.ts
//   yuzee-ai-token-lab/src/protocol/v1.4/Yuzee_Response_Protocol_v1.4.ts
// v1.4 only adds new content_block `type`s + a `data` payload per block; the
// envelope (interaction/service_trigger/rmo_readiness/state/followups) is the
// same shape across both versions, so one set of types covers both.
// ---------------------------------------------------------------------------

export type YuzeeBlockType =
  | 'heading' | 'text' | 'list' | 'steps' | 'table' | 'comparison' | 'callout' | 'key_value'
  | 'cards' | 'timeline' | 'flow' | 'pathway_map' | 'scorecard' | 'chart' | 'progress' | 'checklist';
export type YuzeeBlockLevel = 'none' | 'h2' | 'h3';
export type YuzeeVariant = 'default' | 'info' | 'success' | 'warning' | 'danger' | 'muted';

export type InteractionKind = 'none' | 'question' | 'handoff';
export type InteractionInputType = 'none' | 'text' | 'single_select' | 'multi_select' | 'ranked_select' | 'fields';

export interface YuzeeItem {
  id: string;
  title: string;
  text: string;
  value: string;
  status: string;
  icon?: string;
  side_label?: string;
  side_text?: string;
}

export interface YuzeeColumn { key: string; label: string; }
export interface YuzeeCell { key: string; value: string; }
export interface YuzeeRow { id: string; criteria?: string; cells: YuzeeCell[]; }

// ---- v1.4 typed block `data` payloads ----

export interface CardFact { label: string; value: string; }
export interface CardItem { id: string; title: string; subtitle: string; description: string; status: string; badge: string; facts: CardFact[]; }
export interface CardsData { cards: CardItem[]; }

export interface TimelineMilestone { id: string; label: string; description: string; time_label: string; status: 'completed' | 'current' | 'upcoming' | 'blocked' | 'paused' | 'unknown'; optional: boolean; }
export interface TimelineData { milestones: TimelineMilestone[]; }

export interface FlowNode { id: string; label: string; description: string; node_type: string; status: string; }
export interface FlowEdge { from: string; to: string; label: string; condition: string; }
export interface FlowData { nodes: FlowNode[]; edges: FlowEdge[]; }

export interface PathwayStep { id: string; label: string; description: string; status: string; }
export interface PathwayLane { id: string; title: string; summary: string; recommended: boolean; steps: PathwayStep[]; }
export interface PathwayMapData { goal: string; lanes: PathwayLane[]; }

export interface ScorecardMetric { id: string; label: string; value: number | string; value_type: 'number' | 'percentage' | 'rating' | 'text'; unit: string; max?: number; status: string; trend: 'up' | 'down' | 'stable' | 'unknown'; description: string; }
export interface ScorecardData { metrics: ScorecardMetric[]; }

export interface ChartSeries { id: string; label: string; values: number[]; unit: string; }
export interface ChartData { chart_type: 'bar' | 'line' | 'donut' | 'funnel'; categories: string[]; series: ChartSeries[]; source_status: 'verified' | 'provided' | 'estimated' | 'to_verify'; }

export interface ProgressStage { id: string; label: string; status: 'completed' | 'current' | 'upcoming' | 'blocked' | 'paused' | 'failed' | 'unknown'; description: string; }
export interface ProgressData { stages: ProgressStage[]; }

export interface YuzeeContentBlock {
  id: string;
  type: YuzeeBlockType | string;
  level?: YuzeeBlockLevel;
  variant?: YuzeeVariant;
  title: string;
  text: string;
  items?: YuzeeItem[];
  columns?: YuzeeColumn[];
  rows?: YuzeeRow[];
  /** v1.4 typed payload for cards/timeline/flow/pathway_map/scorecard/chart/progress; empty/absent for legacy block types. */
  data?: Partial<CardsData & TimelineData & FlowData & PathwayMapData & ScorecardData & ChartData & ProgressData> & Record<string, unknown>;
}

export interface YuzeeOption {
  id: string;
  label: string;
  description?: string;
  value: string;
}

export interface YuzeeField {
  id: string; // 'goal' | 'location' | 'residency' per protocol, kept as string for forward-compat
  label: string;
  input_type: 'text' | 'australian_location' | 'single_select';
  required: boolean;
  options: YuzeeOption[];
}

export interface RecommendedAction { id: string; label: string; message: string; }

export interface YuzeeInteraction {
  kind: InteractionKind;
  input_type: InteractionInputType;
  question_id: string;
  question: string;
  options: YuzeeOption[];
  allow_other_input: boolean;
  other_input_label: string;
  fields: YuzeeField[];
  recommended_actions: RecommendedAction[];
}

export interface ServiceAction {
  id: string;
  title: string;
  description: string;
  action_id: string;
  rmo_type?: string;
  requires_confirmation: boolean;
}

export interface YuzeeService {
  service_intent_detected: boolean;
  primary_requested_service: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  reason: string;
  trigger_now: boolean;
  needs_more_clarity: boolean;
  actions: ServiceAction[];
}

export interface YuzeeRmoReadiness {
  readiness: 'READY' | 'PARTIAL' | 'NOT_READY';
  ready_to_generate: boolean;
  missing_inputs: string[];
  verification_required: boolean | string[];
}

export interface YuzeeState {
  active_response_mode?: string;
  effective_response_mode?: string;
  mode_source?: 'tag' | 'sticky' | 'default';
  safety_override_applied: boolean;
  user_confidence?: { score: number; band: string; evidence_strength: string; trend: string; reason_codes: string[] };
  progress?: { explained: boolean; failed_attempts: number; loop_count_same_issue: number; security_breach_count: number; active_security_penalty: string };
  [key: string]: unknown;
}

export interface YuzeeFollowups {
  enabled: boolean;
  cancel_on_user_message: boolean;
  topic_lock: boolean;
  topic_key: string;
  triggers: Array<{ delay_seconds: number; message: string }>;
}

export interface YuzeeResponseV13 {
  schema_version?: '1.3' | '1.4' | string;
  current_mode: string;
  response_intent: string;
  content_blocks: YuzeeContentBlock[];
  interaction: YuzeeInteraction;
  service_trigger?: YuzeeService;
  rmo_readiness?: YuzeeRmoReadiness;
  state: YuzeeState;
  followups?: YuzeeFollowups;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// User interaction submission — the shape ChatRequest.userEvent expects.
// Mirrors ProtocolInteraction.tsx's onInteract payload; see
// ProtocolValidator.extractInteraction() on the Java side for what is read.
// ---------------------------------------------------------------------------

export interface InteractionAnswerPayload {
  question_id: string;
  selected_option_ids?: string[];
  ranked_option_ids?: string[];
  fields?: Record<string, string>;
  self_input?: string;
}

export interface UserEvent {
  type?: 'ranked_submission' | 'fields_submission' | 'text_answer' | 'action_clicked'
    | 'option_selected' | 'fields_submitted' | 'followup_clicked';
  interaction_id?: string;
  action_id?: string;
  value: string;
  message?: string;
  userEvent?: { interaction?: InteractionAnswerPayload; ui?: { selected_mode?: string } };
  interaction?: InteractionAnswerPayload;
  timestamp?: number;
}

/** The original's {message, userQuestionAnswers, skillChoice} sendMessage payload (clarification answers / skill picks). */
export interface SendMessagePayload {
  message: string;
  userQuestionAnswers: unknown[];
  skillChoice?: unknown;
  objectiveResultId?: string;
  objectiveResultRevision?: number;
  objectiveTransfer?: ObjectiveTransfer;
}
export type SendMessageInput = string | UserEvent | SendMessagePayload;
export interface MessageAttachment { mimeType: string; data: string; }
export interface ObjectiveTransfer { sessionId: string; label: string; revision: number; }

/** Response shape assumed for the not-yet-implemented action-execute backend endpoint. */
export interface ActionExecuteResponse {
  executed: boolean;
  message: string;
}

export type QualityFeedbackType = 'good' | 'context_missing' | 'too_verbose' | 'too_short' | 'incorrect' | 'other';
export interface ChatStreamProgress { phase: ChatProgressPhase; }

/** Per-turn usage in the original's TokenUsageMetrics shape (telemetry.usage). */
export interface TokenUsageMetrics {
  currentUserTokens: number | null;
  inputTokens: number;
  outputTokens: number;
  thinkingTokens: number | null;
  cachedTokens: number | null;
  toolTokens: number | null;
  totalTokens: number;
  uncachedInputTokens?: number | null;
  cacheHitPercentage?: number | null;
  latencyMs?: number;
  timeToFirstTokenMs?: number | null;
  finishReason?: string | null;
  isMock?: boolean;
  /** Java backend only: server-computed turn cost (USD). */
  estimatedCostUsd?: number;
}

export interface ContextSectionDetail { name: string; description: string; tokens: number; preview: string; }
export interface ExcludedSectionDetail { name: string; reason: string; tokens: number; preview: string; }
export interface ContextBreakdown {
  systemInstructionTokens: number;
  careerContextTokens: number;
  summaryTokens: number;
  recentTurnsTokens: number;
  currentMessageTokens: number;
  totalAssembledTokens: number;
  removedTokens: number;
  includedSections: ContextSectionDetail[];
  excludedSections: ExcludedSectionDetail[];
}

/** Union of the original's compaction metrics and the Java backend's CompactionMetrics.java. */
export interface CompactionMetrics {
  compactionEventId?: string;
  sourceTurnsRange?: string;
  sourceTokens?: number;
  summaryTokens?: number;
  tokensRemoved?: number;
  compactionInputTokens?: number;
  compactionOutputTokens?: number;
  compactionTotalCost?: number;
  estimatedNetSavingsPerTurn?: number;
  estimatedBreakEvenTurns?: number;
  timestamp?: number;
  turnsKept?: number;
  turnsDropped?: number;
  tokensUsed?: number;
  tokenBudget?: number;
  strategy?: string;
}

export interface TelemetryTimeline {
  aiRequestId?: string;
  requestReceivedAt?: number;
  preProviderLatencyMs?: number;
  conversationLoadMs?: number;
  userEventValidationMs?: number;
  memoryAssemblyMs?: number;
  requestAssemblyMs?: number;
  providerTtftMs?: number | null;
  providerGenMs?: number | null;
  validationDurationMs?: number;
  totalLatencyMs?: number;
}

export interface ProtocolValidationSummary {
  jsonParsed?: boolean;
  schemaValid: boolean;
  semanticValid: boolean;
  protocolAccepted: boolean;
  errors: string[];
  warnings?: string[];
}

/** The original's MessageTelemetry (message.telemetry / activeTurnTelemetry). */
export interface MessageTelemetry {
  usage: TokenUsageMetrics;
  contextMetrics: ContextBreakdown | null;
  compactionMetrics?: CompactionMetrics | null;
  timeline?: TelemetryTimeline;
  model: string;
  thinkingLevel?: ThinkingLevel;
  appliedThinkingLevel?: string;
  optimizationMode?: OptimizationMode;
  optimizationStrategy?: OptimizationStrategy;
  preset?: PresetMode;
  responseMode?: ResponseMode;
  recentTurnsCount?: number;
  hasSummary?: boolean;
  validation?: ProtocolValidationSummary;
  preflight?: unknown;
  routing?: unknown;
  timestamp: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  /** Plain text: the user's words (or a structured answer's label) / the assistant's raw protocol JSON. */
  content: string;
  /** Epoch ms. */
  createdAt: number;
  structuredResponse?: YuzeeResponseV13;
  schemaValid?: boolean;
  semanticValid?: boolean;
  validationErrors?: string[];
  protocolVersion?: string;
  telemetry?: MessageTelemetry;
  feedback?: { type: QualityFeedbackType; comment?: string; timestamp: number };
  isStreaming?: boolean;
  streamProgress?: ChatStreamProgress;
  streamStopped?: boolean;
  routing?: unknown;
  error?: string;
  errorCode?: string;
  serverMessageId?: string;
  preflight?: unknown;
  userEvent?: UserEvent;
  objectiveTransfer?: ObjectiveTransfer;
  warehouseData?: WarehousePack;
}

export interface CareerContextCapsule {
  facts?: string; goals?: string; constraints?: string; decisions?: string; openThreads?: string;
  goal?: string; currentStage?: string; targetRole?: string; education?: string; keySkills?: string;
  location?: string; timeline?: string; preferences?: string; openQuestions?: string;
  [key: string]: unknown;
}

export interface Conversation {
  id: string;
  title: string;
  /** Epoch ms. */
  createdAt: number;
  updatedAt: number;
  messages: ChatMessage[];
  model?: ModelId;
  mode?: OptimizationMode;
  responseMode?: ResponseMode;
  strategy?: OptimizationStrategy;
  preset?: PresetMode;
  thinkingLevel?: ThinkingLevel;
  contextBudget?: number;
  recentTurnsToKeep?: number;
  careerContext?: unknown;
  summary?: string;
  summaryVersion?: number;
  systemPromptMode?: 'default' | 'compact' | 'custom';
  customSystemPrompt?: string;
  useInteractionsApi?: boolean;
  useFlashLiteUtility?: boolean;
  useStructuredOutput?: boolean;
  activeInteraction?: unknown;
  securityBreachCount?: number;
  activeSecurityPenalty?: string;
  compactionHistory?: CompactionMetrics[];
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  useMultiTurn?: boolean;
}

export interface SessionStats {
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
  turns: number;
  estimatedCostUsd: number;
  // The original's SessionCumulativeStats names (Java /api/tokens/session-stats returns both).
  userFacingChatCalls?: number;
  totalModelInputTokens?: number;
  totalUncachedInputTokens?: number;
  totalModelOutputTokens?: number;
  totalThinkingTokens?: number;
  totalCachedTokens?: number;
  totalUserFacingTokens?: number;
  compactionCalls?: number;
  compactionTotalTokens?: number;
  trueTotalConsumption?: number;
  baselineEstimatedTokens?: number;
  tokensSaved?: number;
  netSavingsPercentage?: number;
  cacheHitRatio?: number;
  averageTokensPerTurn?: number;
  averageOutputPerTurn?: number;
  averageThinkingPerTurn?: number;
}
export type SessionCumulativeStats = SessionStats;

/** One entry of capabilities.modelsList (Java ModelInfo / the original's ModelCapabilityInfo). */
export interface ModelCapabilityInfo {
  id: string;
  name: string;
  family?: string;
  categoryGroup?: string;
  status?: string;
  available?: boolean;
  selectable?: boolean;
  freeTierEligible?: boolean;
  supportsThinking?: boolean;
  thinkingMechanism?: string;
  supportedThinkingLevels?: string[];
  defaultThinkingLevel?: string;
  supportsCaching?: boolean;
  isRecommended?: boolean;
  isDefault?: boolean;
  replacementModel?: string;
  badge?: string;
  shortDescription?: string;
  longDescription?: string;
  inputPricePerMToken?: number;
  outputPricePerMToken?: number;
  cachedReadPricePerMToken?: number;
}

export interface CapabilitiesResponse {
  models: { id: string; label: string; default?: boolean; recommended?: boolean; badge?: string }[];
  features: Record<string, boolean>;
  configured?: boolean;
  availableModels?: string[];
  modelsList?: ModelCapabilityInfo[];
  defaultModel?: string;
  supportsThinking?: boolean;
  supportsCachedTokens?: boolean;
  supportsInteractionsApi?: boolean;
  supportsExplicitCache?: boolean;
  geminiApiKeyPresent?: boolean;
  runtime?: string;
}

export interface SharedSettings {
  systemPromptMode?: 'default' | 'custom';
  customSystemPrompt?: string;
  contextBudget?: number;
  recentTurnsToKeep?: number;
  strategy?: string;
  updatedAt?: number;
  defaultPromptHash?: string;
  defaultPromptBytes?: number;
  [key: string]: unknown;
}

export interface UserProfileFact { id: string; text: string; category?: string; addedAt: number; }
export interface UserContradiction { id: string; fact: string; contradiction: string; detectedAt: number; resolved?: boolean; }
export interface PendingClarification { questions: any[]; bridgeMessage?: string; originalMessage?: string; }
export type DailyCostLevel = '$1' | '$5' | '$10' | '$15+' | null;
export interface DailyCostWarning { level: DailyCostLevel; totalCostUsd: number; }

export interface AuthState {
  authenticated: boolean;
  token?: string;
  username?: string;
}
