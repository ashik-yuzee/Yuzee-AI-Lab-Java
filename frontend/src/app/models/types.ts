export type ModelId = string;
export type ThinkingLevel = 'minimal' | 'low' | 'medium' | 'high' | 'adaptive';
export type OptimizationMode = 'AUTO' | 'SAVE_TOKENS' | 'FULL_CONTEXT' | 'ADVANCED' | 'VANILLA' | 'MICRO_PROMPT';
export type OptimizationStrategy = 'BASELINE' | 'SLIDING_WINDOW' | 'SUMMARY_RECENT' | 'ADAPTIVE_HYBRID' | 'SEMANTIC_EVIDENCE';
export type PresetMode = 'MAX_QUALITY' | 'BALANCED' | 'MAX_SAVINGS' | 'ADVANCED' | 'VANILLA';
export type ResponseMode = 'quick' | 'standard' | 'explain' | 'explore' | 'detail' | 'decide' | 'vanilla';
export type ChatProgressPhase = 'routing' | 'waiting' | 'thinking' | 'receiving' | 'checking' | 'reviewing' | 'retrying';

export interface YuzeeContentBlock {
  type: string;
  text?: string;
  title?: string;
  items?: YuzeeItem[];
  // allow extra keys but keep named props strongly typed
  [key: string]: unknown;
}

export interface YuzeeItem {
  label?: string;
  text?: string;
  value?: unknown;
  [key: string]: unknown;
}

export interface YuzeeInteraction {
  kind: 'none' | 'question' | 'form' | 'action';
  question?: string;
  options?: YuzeeOption[];
  fields?: YuzeeField[];
}

export interface YuzeeOption {
  label: string;
  value: string;
  description?: string;
}

export interface YuzeeField {
  name: string;
  label: string;
  type: string;
  required?: boolean;
}

export interface YuzeeState {
  safety_override_applied: boolean;
  [key: string]: unknown;
}

export interface YuzeeResponseV13 {
  current_mode: string;
  response_intent: string;
  content_blocks: YuzeeContentBlock[];
  interaction: YuzeeInteraction;
  state: YuzeeState;
  service_trigger?: { trigger_now: boolean; [key: string]: unknown };
  [key: string]: unknown;
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
