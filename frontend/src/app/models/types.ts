import { CompactionInfo } from './token-lab-inspector.types';
import { ResearchOffer } from '../components/research-details/research-details.types';

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
  type: 'ranked_submission' | 'fields_submission' | 'text_answer' | 'action_clicked';
  interaction_id?: string;
  action_id?: string;
  value: string;
  userEvent?: { interaction: InteractionAnswerPayload };
  timestamp: number;
}

/** Response shape assumed for the not-yet-implemented action-execute backend endpoint. */
export interface ActionExecuteResponse {
  executed: boolean;
  message: string;
}

export interface TokenUsage {
  promptTokens: number;
  outputTokens: number;
  totalTokens?: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: unknown;
  timestamp?: string;
  parsedResponse?: YuzeeResponseV13;
  tokenUsage?: TokenUsage;
  compaction?: CompactionInfo;
  researchOffer?: ResearchOffer;
  streamStopped?: boolean;
  validationFailed?: boolean;
  streaming?: boolean;
  streamBuffer?: string;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: ChatMessage[];
  modelId?: string;
  optimizationMode?: OptimizationMode;
  responseMode?: ResponseMode;
  strategy?: OptimizationStrategy;
  careerContext?: unknown;
  profileFacts?: unknown[];
  summaryText?: string;
  miniPathways?: unknown[];
  objectives?: unknown[];
}

export interface SessionStats {
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
  turns: number;
  estimatedCostUsd: number;
}

export interface CapabilitiesResponse {
  models: { id: string; label: string; default?: boolean }[];
  features: Record<string, boolean>;
}

export interface AuthState {
  authenticated: boolean;
  token?: string;
  username?: string;
}
