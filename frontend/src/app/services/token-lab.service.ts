import { Injectable, signal, computed, effect } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { RoutingService, needsQuery, parseOalaMention, selectedTopic, type NeedHint, type RoutingDecision } from './routing.service';
import { acceptedResponse } from '../utils/response-presentation';
import { calcTurnCost, formatCost } from '../utils/chat-helpers';
import { visibleWorkspaceId } from '../components/objectives/objectives.types';
import {
  Conversation, ChatMessage, SessionStats, ChatProgressPhase, CapabilitiesResponse, MessageTelemetry, OptimizationMode, PresetMode, UserEvent, SendMessageInput, MessageAttachment, ChatStreamProgress, DailyCostWarning, PendingClarification, UserProfileFact, UserContradiction, SharedSettings, QualityFeedbackType
} from '../models/types';

const DEFAULT_MODEL_ID = 'gemini-3.7-flash';
const LS_KEY = 'yuzee-token-lab-v1';
const LS_ACTIVE_CONVERSATION_KEY = 'yuzee-token-lab-active-conversation-v1';

const lsGet = (key: string): string | null => { try { return localStorage.getItem(key); } catch { return null; } };
const lsSet = (key: string, value: string | null): void => {
  try { value === null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch { /* storage optional */ }
};
const lsJson = <T>(key: string, fallback: T): T => { try { return JSON.parse(lsGet(key) || '') as T; } catch { return fallback; } };

const INCOMPLETE_MESSAGE = 'The reply stopped before it was ready. Your answer has been kept. Please try again.';
const NETWORK_MESSAGE = 'The connection is unavailable. Your answer has been kept. Please try again when it is back.';

/** services/ReviewRetry.ts reviewFailureMessage. */
function reviewFailureMessage(code?: string): string {
  if (code === 'PROVIDER' || code === 'RATE_LIMIT') return 'Gemini is temporarily unavailable or busy. Your message is saved. Please try again shortly.';
  if (code === 'TIMEOUT' || code === 'NETWORK') return 'The reply check could not finish because Gemini took too long or the connection failed. Your message is saved. Please try again.';
  return 'I couldn’t prepare a clear response. Please try again. Your answer has been kept.';
}

/** ux/streamProgress.ts parseChatProgress. */
const PROGRESS_PHASES: ChatProgressPhase[] = ['routing', 'waiting', 'thinking', 'receiving', 'checking', 'reviewing', 'retrying'];
function parseChatProgress(value: unknown): ChatStreamProgress | null {
  const v = value as any; const phase = v?.phase ?? (v?.state === 'generating' ? 'receiving' : undefined);
  return typeof phase === 'string' && PROGRESS_PHASES.includes(phase as ChatProgressPhase) ? { phase: phase as ChatProgressPhase } : null;
}

/** services/api.ts StreamCallbacks. */
type StreamCallbacks = {
  onStatus: (progress: ChatStreamProgress) => void;
  onStart: (data: { messageId?: string; appliedThinkingLevel?: string; routing?: RoutingDecision; preflight?: any }) => void;
  onDelta: (text: string) => void;
  onStructured: (data: any) => void;
  onValidation: (data: any) => void;
  onProtocolValidationError: (data: { errors?: string[]; reviewFailureCode?: string }) => void;
  onUsage: (data: any) => void;
  onCompaction: (data: any) => void;
  onDone: () => void;
  onError: (err: Error & { errorCode?: string }) => void;
};

/** Chat-bubble label for a structured answer — same synthesis as the original TokenLabContext.sendMessage. */
export function userEventLabel(input: any): string {
  const inter = input?.userEvent?.interaction;
  if (inter) {
    if (inter.self_input) return input.value || inter.self_input;
    if (inter.selected_option_ids?.length) return input.value || `Selected option: ${inter.selected_option_ids.join(', ')}`;
    if (inter.ranked_option_ids?.length) return input.value || inter.ranked_option_ids.join(' → ');
    if (inter.fields) return input.value || `Submitted details: ${Object.entries(inter.fields).map(([k, v]) => `${k}: ${v}`).join(', ')}`;
    return 'Submitted response';
  }
  return input?.value || input?.message || 'Submitted interaction';
}

export { formatCost } from '../utils/chat-helpers';

/**
 * Angular port of the original TokenLabContext. Components read signals directly
 * (`lab.currentConversation()`, `lab.isStreaming()` …); UI flags are writable signals (`lab.isSidebarOpen.set(true)`).
 */
@Injectable({ providedIn: 'root' })
export class TokenLabService {
  // ---- Conversations ----
  conversations = signal<Conversation[]>([]);
  /** The original's currentConversation state: live telemetry lands here and is synced back to `conversations` when streaming ends. */
  currentConversation = signal<Conversation | null>(null);
  activeConversation = this.currentConversation;
  activeConversationId = computed(() => this.currentConversation()?.id ?? null);
  isLoading = signal(true);

  // ---- Server data ----
  capabilities = signal<CapabilitiesResponse | null>(null);
  sessionStats = signal<SessionStats | null>(null);
  sharedSettings = signal<SharedSettings | null>(null);
  activeTurnTelemetry = signal<MessageTelemetry | null>(null);
  dailyCostWarning = signal<DailyCostWarning>({ level: null, totalCostUsd: 0 });

  // ---- Streaming ----
  isStreaming = signal(false);

  // ---- Settings ----
  activeModelId = signal(DEFAULT_MODEL_ID);
  /** Original name for activeModelId. */
  selectedModel = this.activeModelId;

  // ---- Modals & panels (writable; original setX(v) → x.set(v)) ----
  isTokenInspectorOpen = signal(false);
  isWhiteboardOpen = signal(false);
  whiteboardGenerateTick = signal(0);
  whiteboardHasPathway = signal(false);
  isAdvancedLabOpen = signal(false);
  activeLabTab = signal('context');
  isContextInspectorOpen = signal(false);
  isCareerContextOpen = signal(false);
  isMemoryTimelineOpen = signal(false);
  isBenchmarkOpen = signal(false);
  isAnalyticsOpen = signal(false);
  isSettingsOpen = signal(false);
  isExportOpen = signal(false);
  isSidebarOpen = signal(typeof window !== 'undefined' && window.innerWidth >= 1024);
  isProfileOpen = signal(false);

  // ---- Profile, location, contradictions (localStorage-backed, same keys as the original) ----
  userProfile = signal<UserProfileFact[]>(lsJson('yuzee_user_profile', []));
  userLocation = signal<string>(lsGet('yuzee_user_location') || '');
  userContradictions = signal<UserContradiction[]>(lsJson('yuzee_contradictions', []));
  pendingClarificationQuestions = signal<PendingClarification | null>(null);
  hasDeferredMessage = signal(false);

  // ---- Derived ----
  /** Sum of calcTurnCost over the current conversation's assistant turns (Navbar). */
  currentConversationCost = computed(() => this.conversationCost(this.activeConversation()));
  /** Sum across all conversations (Sidebar "session total"). */
  sessionTotalCost = computed(() => this.conversations().reduce((sum, c) => sum + this.conversationCost(c, false), 0));
  localStorageStats = computed(() => {
    this.conversations();
    const raw = lsGet(LS_KEY);
    return { bytes: raw ? new Blob([raw]).size : 0, conversationCount: lsJson<unknown[]>(LS_KEY, []).length, storageAvailable: typeof localStorage !== 'undefined' };
  });

  private abortController: AbortController | null = null;
  private sendLock = false;
  private pendingOriginalMessage: string | null = null;
  private shownThresholds = new Set<string>();
  private loadDone = false;

  constructor(private http: HttpClient, private auth: AuthService, private routing: RoutingService) {
    // UserProfileModal / LocationPromptModal write these keys whenever they set the value (never on load).
    const persist = (key: string, read: () => string) => {
      let initial = true;
      effect(() => { const value = read(); if (initial) { initial = false; return; } lsSet(key, value); });
    };
    persist('yuzee_user_profile', () => JSON.stringify(this.userProfile()));
    persist('yuzee_user_location', () => this.userLocation());
    persist('yuzee_contradictions', () => JSON.stringify(this.userContradictions()));
    // Persist after every conversation change, but only once initial load is done.
    effect(() => {
      const convs = this.conversations();
      if (this.loadDone) this.lsSaveConversations(convs);
    });
    // Keep the reader's currently selected chat across browser refreshes.
    effect(() => {
      const id = this.activeConversationId();
      if (this.loadDone) lsSet(LS_ACTIVE_CONVERSATION_KEY, id);
    });
    // Sync currentConversation (which has live telemetry) back to conversations when streaming ends.
    effect(() => {
      const streaming = this.isStreaming(), current = this.currentConversation();
      if (streaming || !current) return;
      this.conversations.update(prev => {
        const idx = prev.findIndex(c => c.id === current.id);
        if (idx === -1 || prev[idx] === current) return prev;
        const next = [...prev];
        next[idx] = current;
        return next;
      });
    }, { allowSignalWrites: true });
  }

  private get headers(): HttpHeaders {
    const t = this.auth.token;
    return t ? new HttpHeaders({ Authorization: `Bearer ${t}` }) : new HttpHeaders();
  }
  private get<T>(url: string) { return firstValueFrom(this.http.get<T>(url, { headers: this.headers })); }
  private post<T>(url: string, body: unknown = null) { return firstValueFrom(this.http.post<T>(url, body, { headers: this.headers })); }
  private put<T>(url: string, body: unknown) { return firstValueFrom(this.http.put<T>(url, body, { headers: this.headers })); }

  private lsSaveConversations(convs: Conversation[]): void {
    try { localStorage.setItem(LS_KEY, JSON.stringify(convs)); } catch (e: any) {
      // Drop oldest conversation and retry once
      if (e?.name === 'QuotaExceededError') lsSet(LS_KEY, JSON.stringify(convs.slice(0, Math.max(1, convs.length - 1))));
    }
  }

  // =========================================================================
  // Loading
  // =========================================================================

  /** Load initial data — falls back to localStorage when server has no conversations (e.g. restart). */
  async loadInitialData(): Promise<void> {
    try {
      this.isLoading.set(true);
      const [caps, convs, stats, ss] = await Promise.all([
        this.get<CapabilitiesResponse>('/api/config/capabilities').catch(() => null),
        this.fetchConversations().catch((): Conversation[] => []),
        this.get<SessionStats>('/api/tokens/session-stats').catch(() => null),
        this.get<SharedSettings>('/api/shared-settings').catch(() => null),
      ]);
      if (caps) this.capabilities.set(caps);
      if (stats) this.sessionStats.set(stats);
      if (ss) this.sharedSettings.set(ss);

      // Allow lsSave to run from this point on
      this.loadDone = true;

      if (convs && convs.length > 0) {
        const active = convs.find(c => c.id === lsGet(LS_ACTIVE_CONVERSATION_KEY)) || convs[0];
        this.conversations.set(convs);
        this.lsSaveConversations(convs);
        this.currentConversation.set(active);
        if (active.model) this.activeModelId.set(active.model);
        const t = this.lastTelemetry(active);
        if (t) this.activeTurnTelemetry.set(t);
      } else {
        // Server is empty — restore from localStorage (covers server restarts)
        const saved = lsJson<Conversation[]>(LS_KEY, []);
        if (saved.length > 0) {
          const active = saved.find(c => c.id === lsGet(LS_ACTIVE_CONVERSATION_KEY)) || saved[0];
          // Show conversations immediately; restore to server in the background
          this.conversations.set(saved);
          this.currentConversation.set(active);
          if (active.model) this.activeModelId.set(active.model);
          const t = this.lastTelemetry(active);
          if (t) this.activeTurnTelemetry.set(t);
          // Fire-and-forget — failures are non-fatal; server will get them on next user action
          for (const c of saved) this.post('/api/conversations/restore', c).catch(() => {});
        } else {
          this.conversations.set([]);
          this.currentConversation.set(null);
          this.activeTurnTelemetry.set(null);
        }
      }
    } catch (e) {
      console.error('Initialization error:', e);
    } finally {
      this.isLoading.set(false);
    }
  }

  /** services/api.ts fetchConversations: server stores telemetry as flat fields on old rows; reconstruct the nested telemetry object. */
  private async fetchConversations(): Promise<Conversation[]> {
    const convs = await this.get<any[]>('/api/conversations');
    return convs.map((conv: any) => ({
      ...conv,
      messages: (conv.messages || []).map((m: any) => m.role === 'assistant' && m.usage && !m.telemetry ? {
        ...m,
        telemetry: {
          usage: m.usage,
          contextMetrics: m.contextMetrics ?? null,
          compactionMetrics: m.compactionMetrics ?? null,
          model: m.model ?? '',
          thinkingLevel: m.thinkingLevel ?? 'minimal',
          optimizationStrategy: m.optimizationStrategy ?? 'ADAPTIVE_HYBRID',
          preset: m.preset ?? 'BALANCED',
          responseMode: m.responseMode ?? 'standard',
          recentTurnsCount: m.recentTurnsCount ?? 0,
          hasSummary: m.hasSummary ?? false,
          timestamp: m.createdAt ?? Date.now(),
        },
      } : m),
    }));
  }

  async refreshStats(): Promise<void> {
    try { this.sessionStats.set(await this.get<SessionStats>('/api/tokens/session-stats')); } catch (e) { console.error('Failed to refresh stats:', e); }
  }

  async resetSessionStats(): Promise<void> {
    await this.post('/api/tokens/session-reset');
    await this.refreshStats();
  }

  // =========================================================================
  // Conversation CRUD
  // =========================================================================

  private lastTelemetry(conv: Conversation | null | undefined): MessageTelemetry | null {
    const msgs = conv?.messages ?? [];
    for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].role === 'assistant' && msgs[i].telemetry) return msgs[i].telemetry!;
    return null;
  }

  async selectConversation(id: string): Promise<void> {
    const found = this.conversations().find(c => c.id === id);
    if (!found) return;
    this.currentConversation.set(found);
    if (found.model) this.activeModelId.set(found.model);
    this.activeTurnTelemetry.set(this.lastTelemetry(found));
  }

  async startNewConversation(title?: string): Promise<Conversation> {
    // Don't create a duplicate if the current conversation is already empty
    const current = this.currentConversation();
    if (current && (!current.messages || current.messages.length === 0)) return current;
    const newConv = await this.post<Conversation>('/api/conversations', {
      title: title || 'New Career Exploration', model: current?.model || this.activeModelId(), strategy: 'BASELINE',
      mode: 'VANILLA', thinkingLevel: 'medium', responseMode: 'vanilla', contextBudget: 270000, recentTurnsToKeep: 100,
    });
    this.conversations.update(prev => [newConv, ...prev]);
    this.currentConversation.set(newConv);
    this.activeTurnTelemetry.set(null);
    return newConv;
  }

  async loadDemoConversation(): Promise<Conversation> {
    const demoConv = await this.post<Conversation>('/api/conversations/load-demo');
    this.conversations.update(prev => [demoConv, ...prev.filter(c => c.id !== demoConv.id)]);
    this.currentConversation.set(demoConv);
    const t = this.lastTelemetry(demoConv);
    if (t) this.activeTurnTelemetry.set(t);
    return demoConv;
  }

  async removeConversation(id: string): Promise<void> {
    const currentId = this.currentConversation()?.id;
    try {
      await firstValueFrom(this.http.delete(`/api/conversations/${id}`, { headers: this.headers }));
      const remaining = this.conversations().filter(c => c.id !== id);
      this.conversations.set(remaining);
      if (currentId === id) {
        this.currentConversation.set(remaining[0] ?? null);
        this.activeTurnTelemetry.set(this.lastTelemetry(remaining[0]));
      }
    } catch (e) { console.error('Failed to delete conversation:', e); }
  }

  async updateCurrentConversationSettings(updates: Partial<Conversation>): Promise<void> {
    if (updates.model) this.activeModelId.set(updates.model);
    const current = this.currentConversation();
    if (!current) return;
    const updated = { ...current, ...updates, updatedAt: Date.now() };
    this.currentConversation.set(updated);
    this.conversations.update(prev => prev.map(c => (c.id === updated.id ? updated : c)));
    await this.put(`/api/conversations/${updated.id}`, updates);
  }

  applyOptimizationMode(mode: OptimizationMode): void {
    if (!this.activeConversation()) return;
    const table: Record<OptimizationMode, Partial<Conversation>> = {
      AUTO: { mode: 'AUTO', strategy: 'ADAPTIVE_HYBRID', thinkingLevel: 'adaptive', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'standard' },
      SAVE_TOKENS: { mode: 'SAVE_TOKENS', strategy: 'SUMMARY_RECENT', thinkingLevel: 'low', recentTurnsToKeep: 2, contextBudget: 1000, responseMode: 'quick' },
      FULL_CONTEXT: { mode: 'FULL_CONTEXT', strategy: 'BASELINE', thinkingLevel: 'medium', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'standard' },
      VANILLA: { mode: 'VANILLA', strategy: 'BASELINE', thinkingLevel: 'minimal', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'vanilla' },
      MICRO_PROMPT: { mode: 'MICRO_PROMPT', strategy: 'ADAPTIVE_HYBRID', thinkingLevel: 'adaptive', responseMode: 'standard' },
      ADVANCED: { mode: 'ADVANCED' },
    };
    this.updateCurrentConversationSettings(table[mode] ?? { mode });
  }

  applyPreset(preset: PresetMode): void {
    if (!this.activeConversation()) return;
    const table: Record<PresetMode, Partial<Conversation>> = {
      MAX_QUALITY: { preset, strategy: 'ADAPTIVE_HYBRID', thinkingLevel: 'medium', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'standard' },
      BALANCED: { preset, strategy: 'ADAPTIVE_HYBRID', thinkingLevel: 'adaptive', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'standard' },
      MAX_SAVINGS: { preset, strategy: 'SUMMARY_RECENT', thinkingLevel: 'low', recentTurnsToKeep: 2, contextBudget: 1000, responseMode: 'quick' },
      VANILLA: { preset, strategy: 'BASELINE', thinkingLevel: 'minimal', recentTurnsToKeep: 100, contextBudget: 270000, responseMode: 'vanilla' },
      ADVANCED: { preset },
    };
    this.updateCurrentConversationSettings(table[preset] ?? { preset });
  }

  // =========================================================================
  // Chat turn
  // =========================================================================

  /**
   * Original sendMessage. `input` is a string, a UserEvent, or `{message, userQuestionAnswers, skillChoice?,
   * objectiveResultId?, objectiveResultRevision?, objectiveTransfer?}`. Resolves true when an accepted structured reply arrived.
   */
  async sendMessage(input: SendMessageInput, attachments?: MessageAttachment[]): Promise<boolean> {
    if (this.isStreaming() || this.sendLock) return false;
    this.sendLock = true;
    try {
      let textMessage = '';
      let userEventPayload: any = null;
      let userQuestionAnswers: unknown[] | undefined;
      const obj = typeof input === 'object' ? input as any : undefined;

      if (typeof input === 'string') {
        textMessage = input.trim();
        if (!textMessage && (!attachments || attachments.length === 0)) return false;
      } else if (obj) {
        // Clarification answers payload: { message, userQuestionAnswers }
        if (obj.message !== undefined && obj.userQuestionAnswers !== undefined) {
          textMessage = obj.message;
          userQuestionAnswers = obj.userQuestionAnswers;
        } else {
          userEventPayload = obj;
          textMessage = userEventLabel(obj);
        }
      }

      let activeConv = this.activeConversation();
      if (!activeConv) activeConv = await this.startNewConversation(textMessage ? textMessage.substring(0, 30) : 'Career Exploration');

      // PRE-FLIGHT: check for contradictions BEFORE touching conversation state (fresh messages only).
      if (userQuestionAnswers === undefined) {
        const unresolvedContradictions = this.userContradictions().filter(c => !c.resolved).map(c => ({ fact: c.fact, contradiction: c.contradiction }));
        if (unresolvedContradictions.length > 0 && textMessage.trim().length > 20) {
          try {
            const preCheck = await this.post<{ needsClarification: boolean; questions?: any[]; bridgeMessage?: string }>(
              '/api/pre-check', { userMessage: textMessage, unresolvedContradictions }).catch(() => ({ needsClarification: false } as { needsClarification: boolean; questions?: any[]; bridgeMessage?: string }));
            if (preCheck.needsClarification && preCheck.questions?.length) {
              this.pendingOriginalMessage = textMessage;
              this.hasDeferredMessage.set(true);
              this.pendingClarificationQuestions.set({ questions: preCheck.questions, bridgeMessage: preCheck.bridgeMessage, originalMessage: textMessage });
              return false;
            }
          } catch { /* fail-safe: proceed normally */ }
        }
      }

      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`, role: 'user', content: textMessage,
        userEvent: userEventPayload || undefined, objectiveTransfer: obj?.objectiveTransfer, createdAt: Date.now(),
      };
      const streamingAssistantMsg: ChatMessage = {
        id: `asst-${Date.now()}`, role: 'assistant', content: '', createdAt: Date.now(),
        isStreaming: true, streamProgress: { phase: 'waiting' },
      };
      const priorMessages = activeConv.messages || [];
      const updatedConv = { ...activeConv, messages: [...priorMessages, userMsg, streamingAssistantMsg] };
      this.currentConversation.set(updatedConv);
      this.conversations.update(prev => prev.map(c => (c.id === updatedConv.id ? updatedConv : c)));

      this.isStreaming.set(true);
      performance.mark('yuzee_send_clicked');
      const controller = new AbortController();
      this.abortController = controller;
      const convId = activeConv.id, msgId = streamingAssistantMsg.id;
      /** setCurrentConversation(prev => prev?.id !== id ? prev : {...prev, messages: prev.messages.map(m => m.id === msgId ? {...m, ...p} : m)}). */
      const patch = (p: Partial<ChatMessage>) => this.currentConversation.update(prev => prev?.id !== convId ? prev
        : { ...prev, messages: prev.messages.map(m => (m.id === msgId ? { ...m, ...p } : m)) });
      /** setCurrentConversation(prev => …): patches the last message when it is still the streaming one; skipped once aborted. */
      const patchLast = (p: (last: ChatMessage) => Partial<ChatMessage>) => this.currentConversation.update(prev => {
        if (!prev || prev.id !== convId || controller.signal.aborted) return prev;
        const msgs = [...prev.messages];
        const last = msgs[msgs.length - 1];
        if (last?.id === msgId) msgs[msgs.length - 1] = { ...last, ...p(last) };
        return { ...prev, messages: msgs };
      });

      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const todayStr = new Date().toLocaleDateString('en-GB', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
      const profile = this.userProfile();
      const relevantFacts = (() => {
        if (profile.length === 0) return [];
        const lowerMsg = textMessage.toLowerCase();
        const likesDislikes = profile.filter(f => f.category === 'like' || f.category === 'dislike');
        const generalFacts = profile
          .filter(f => !f.category || f.category === 'general')
          .map(f => ({ f, score: f.text.toLowerCase().split(/\W+/).filter(w => w.length > 3).filter(w => lowerMsg.includes(w)).length }))
          .sort((a, b) => b.score - a.score)
          .slice(0, 6)
          .map(({ f }) => f);
        return [...likesDislikes, ...generalFacts].slice(0, 8).map(f => f.text);
      })();

      // Check needs on every free-text submission; structured Quiz events retain their controller.
      let needsAssessment: NeedHint | undefined;
      if (!userEventPayload) {
        try { needsAssessment = await this.routing.assessMessageNeeds(needsQuery(textMessage, priorMessages as any), { signal: controller.signal }); } catch { /* Server rules and Gemini context remain available. */ }
      }
      if (controller.signal.aborted) return false;
      let microToolSelection: RoutingDecision | undefined;
      let topicSelection: RoutingDecision | undefined;
      const priorAssistant = priorMessages.at(-1);
      const topic = selectedTopic(acceptedResponse(priorAssistant?.structuredResponse || priorAssistant?.content)?.interaction, userEventPayload);
      if (topic) {
        try { topicSelection = await this.routing.routeMessage(topic, { signal: controller.signal, topic: true }); } catch { /* Existing Quiz interaction remains usable. */ }
      }

      const oalaMention = parseOalaMention(textMessage);
      if (oalaMention.active && !userEventPayload) {
        patch({ streamProgress: { phase: 'routing' } });
        try {
          microToolSelection = await this.routing.routeMessage(oalaMention.message, { signal: controller.signal, structuredAnswer: !!userEventPayload, history: priorMessages });
        } catch { /* Continue with the main counsellor when optional routing is unavailable. */ }
        if (controller.signal.aborted) return false;
        patch({ routing: microToolSelection, streamProgress: { phase: 'waiting' } });
      }
      if (controller.signal.aborted) return false;

      const conv = activeConv;
      const isFirstTurn = priorMessages.filter(m => m.role === 'assistant').length === 0;
      let accumulatedContent = '';
      let resolvedThinkingLevel: string | undefined;

      return await new Promise<boolean>(resolve => {
        let accepted = false;
        let failed = false;
        controller.signal.addEventListener('abort', () => resolve(false), { once: true });
        this.streamChatMessage(convId, {
          message: textMessage,
          objectiveResultId: obj?.objectiveResultId,
          objectiveResultRevision: obj?.objectiveResultRevision,
          mode: conv.mode,
          microToolSelection,
          topicSelection,
          skillChoice: !userEventPayload && obj ? obj.skillChoice : undefined,
          needsAssessment,
          userEvent: userEventPayload,
          model: conv.model,
          strategy: conv.strategy,
          preset: conv.preset,
          responseMode: conv.responseMode,
          thinkingLevel: conv.thinkingLevel,
          contextBudget: conv.contextBudget,
          recentTurnsToKeep: conv.recentTurnsToKeep,
          careerContext: conv.careerContext,
          systemPromptMode: conv.systemPromptMode,
          customSystemPrompt: conv.customSystemPrompt,
          temperature: conv.temperature,
          topP: conv.topP,
          maxOutputTokens: conv.maxOutputTokens,
          useMultiTurn: conv.useMultiTurn ?? true,
          useStructuredOutput: conv.useStructuredOutput ?? false,
          userContext: { date: todayStr, timezone: tz, location: this.userLocation() || undefined },
          userProfileFacts: relevantFacts,
          userQuestionAnswers,
          isOptionSelection: !!userEventPayload,
          attachments: attachments && attachments.length > 0 ? attachments : undefined,
        }, {
          onStart: data => {
            if (!controller.signal.aborted) patch({ serverMessageId: data.messageId });
            if (data.preflight && !controller.signal.aborted) patch({ preflight: data.preflight });
            resolvedThinkingLevel = data.appliedThinkingLevel;
            if (data.routing && !controller.signal.aborted) patch({ routing: data.routing });
          },
          onStatus: progress => {
            if (controller.signal.aborted) return;
            this.currentConversation.update(prev => prev?.id !== convId ? prev
              : { ...prev, messages: prev.messages.map(m => (m.id === msgId && m.isStreaming ? { ...m, streamProgress: progress } : m)) });
          },
          onDelta: chunk => {
            accumulatedContent += chunk;
            patchLast(() => ({ content: accumulatedContent }));
          },
          onStructured: structData => {
            accepted = true;
            performance.mark('yuzee_first_structured');
            // Preserve schemaValid/semanticValid/validationErrors set by onValidation.
            patchLast(() => ({ structuredResponse: structData.structuredResponse || structData }));
          },
          onValidation: valData => patchLast(() => ({ schemaValid: valData.schemaValid, semanticValid: valData.semanticValid, validationErrors: valData.errors || [] })),
          onUsage: usagePayload => {
            const telemetry: MessageTelemetry = {
              usage: usagePayload.usage,
              contextMetrics: usagePayload.contextMetrics,
              compactionMetrics: usagePayload.compactionMetrics,
              timeline: usagePayload.timeline,
              model: usagePayload.model || conv.model || DEFAULT_MODEL_ID,
              thinkingLevel: conv.thinkingLevel || 'adaptive',
              appliedThinkingLevel: resolvedThinkingLevel,
              optimizationMode: conv.mode || 'AUTO',
              optimizationStrategy: conv.strategy || 'ADAPTIVE_HYBRID',
              preset: conv.preset || 'BALANCED',
              responseMode: conv.responseMode || 'standard',
              recentTurnsCount: conv.recentTurnsToKeep || 100,
              hasSummary: !!usagePayload.contextMetrics?.summaryTokens,
              timestamp: Date.now(),
            };
            this.activeTurnTelemetry.set(telemetry);
            // isStreaming stays true — onDone clears it so chip only shows when content is ready
            patchLast(() => ({ telemetry }));
            this.refreshStats();
            this.checkDailyCost();
          },
          onCompaction: compaction => this.currentConversation.update(prev => (!prev || prev.id !== convId || controller.signal.aborted) ? prev
            : { ...prev, compactionHistory: [...(prev.compactionHistory || []), compaction] }),
          onDone: () => {
            if (controller.signal.aborted) return;
            performance.mark('yuzee_response_complete');
            try { performance.measure('yuzee_e2e_latency', 'yuzee_send_clicked', 'yuzee_response_complete'); } catch { /* marks may be cleared between calls */ }
            this.isStreaming.set(false);
            resolve(accepted && !failed);
            this.abortController = null;
            patchLast(() => ({ isStreaming: false, ...(!accepted && !failed ? { error: INCOMPLETE_MESSAGE, errorCode: 'INCOMPLETE_RESPONSE' } : {}) }));
            // Auto-rename after first response if title is still generic
            if (isFirstTurn) this.generateTitle(convId);
          },
          onProtocolValidationError: data => {
            failed = true;
            patchLast(last => ({
              content: accumulatedContent || last.content,
              error: reviewFailureMessage(data.reviewFailureCode), errorCode: 'INVALID_RESPONSE',
              schemaValid: false, semanticValid: false, validationErrors: data.errors || [],
            }));
          },
          onError: err => {
            if (controller.signal.aborted) return;
            failed = true;
            resolve(false);
            console.error('Stream failed:', err);
            this.isStreaming.set(false);
            this.abortController = null;
            patchLast(() => ({ content: accumulatedContent || '', error: err.message, errorCode: err.errorCode, isStreaming: false }));
          },
        }, controller.signal);
      });
    } finally {
      this.sendLock = false;
    }
  }

  /** services/api.ts streamChatMessage: named SSE events; Java's unnamed `data:` frames are mapped onto the same callbacks. */
  private streamChatMessage(conversationId: string, payload: Record<string, unknown>, callbacks: StreamCallbacks, signal: AbortSignal): void {
    const controller = new AbortController();
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', () => controller.abort(), { once: true });
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.auth.token) headers['Authorization'] = `Bearer ${this.auth.token}`;

    (async () => {
      let doneCalled = false;
      const triggerDone = () => { if (!doneCalled) { doneCalled = true; callbacks.onDone(); } };
      try {
        const fetchWithRetry = async (): Promise<Response> => {
          const delays = [500, 1000];
          for (let attempt = 0; ; attempt++) {
            try {
              return await fetch(`/api/conversations/${conversationId}/messages`, {
                method: 'POST', headers,
                body: JSON.stringify({ ...payload, visibleWorkspaceId: visibleWorkspaceId(conversationId) }),
                signal: controller.signal,
              });
            } catch (err: any) {
              const isTransient = err.name !== 'AbortError' && /Failed to fetch|NetworkError|net::ERR_/i.test(err.message ?? '');
              if (!isTransient || attempt >= delays.length) throw err;
              await new Promise(res => setTimeout(res, delays[attempt]));
            }
          }
        };
        const response = await fetchWithRetry();
        if (!response.ok || !response.body) {
          await response.json().catch(() => ({}));
          const message = response.status === 400 ? 'This question may have changed. Review the latest question or send your answer as a message.' : response.status === 429 ? 'Please wait a moment, then try again.' : 'Your reply could not be completed. Please try again.';
          throw Object.assign(new Error(message), { errorCode: String(response.status) });
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        let currentEvent = ''; // must persist across chunk boundaries
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (controller.signal.aborted) return;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          for (const line of lines) {
            if (line === '') currentEvent = ''; // SSE spec: empty line dispatches the event and resets the type
            else if (line.startsWith('event: ')) currentEvent = line.substring(7).trim();
            else if (line.startsWith('data: ')) {
              const dataStr = line.substring(6).trim();
              if (!dataStr) continue;
              try {
                const data = JSON.parse(dataStr);
                if (controller.signal.aborted) return;
                if (currentEvent === 'status') { const progress = parseChatProgress(data); if (progress) callbacks.onStatus(progress); }
                else if (currentEvent === 'start') callbacks.onStart(data);
                else if (currentEvent === 'delta') callbacks.onDelta(typeof data === 'string' ? data : data.delta || '');
                else if (currentEvent === 'structured') callbacks.onStructured(data);
                else if (currentEvent === 'validation') callbacks.onValidation(data);
                else if (currentEvent === 'protocol_validation_error') callbacks.onProtocolValidationError(data);
                else if (currentEvent === 'usage') callbacks.onUsage(data);
                else if (currentEvent === 'compaction') callbacks.onCompaction(data);
                else if (currentEvent === 'done') triggerDone();
                else if (currentEvent === 'error') callbacks.onError(Object.assign(new Error(data.error || 'Streaming error'), data.errorCode ? { errorCode: data.errorCode } : {}));
              } catch {
                if (currentEvent === 'delta') callbacks.onDelta(dataStr); // If text is direct string
              }
            }
          }
        }
        if (!controller.signal.aborted) triggerDone();
      } catch (err: any) {
        if (err.name !== 'AbortError') {
          const networkFailure = /failed to fetch|networkerror|net::|load failed/i.test(err.message || '');
          callbacks.onError(networkFailure ? Object.assign(new Error(NETWORK_MESSAGE), { errorCode: 'NETWORK_ERROR' }) : err);
        }
      }
    })();
  }

  private async generateTitle(convId: string): Promise<void> {
    try {
      const { title } = await this.post<{ title: string }>(`/api/conversations/${convId}/generate-title`);
      this.currentConversation.update(prev => (prev && prev.id === convId ? { ...prev, title } : prev));
      this.conversations.update(prev => prev.map(c => (c.id === convId ? { ...c, title } : c)));
    } catch { /* optional */ }
  }

  /** Daily threshold warnings: $1, $5, $10 once each per session; $15+ on every turn above $15. */
  private async checkDailyCost(): Promise<void> {
    let cost = 0;
    try { cost = (await this.get<{ totalCostUsd?: number }>('/api/tokens/daily-cost')).totalCostUsd ?? 0; } catch { cost = 0; }
    for (const [limit, label] of [[1, '$1'], [5, '$5'], [10, '$10']] as const) {
      if (cost >= limit && !this.shownThresholds.has(label)) {
        this.shownThresholds.add(label);
        this.dailyCostWarning.set({ level: label, totalCostUsd: cost });
        return;
      }
    }
    if (cost >= 15) this.dailyCostWarning.set({ level: '$15+', totalCostUsd: cost });
  }

  dismissCostWarning(): void { this.dailyCostWarning.set({ level: null, totalCostUsd: 0 }); }

  stopStreaming(): void {
    if (!this.abortController) return;
    this.abortController.abort();
    this.abortController = null;
    this.isStreaming.set(false);
    // Clear the per-message streaming flag so the spinner doesn't stay stuck
    this.currentConversation.update(prev => {
      if (!prev) return prev;
      const msgs = [...prev.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === 'assistant' && last.isStreaming) {
        msgs[msgs.length - 1] = { ...last, isStreaming: false, streamStopped: !last.structuredResponse, content: last.structuredResponse ? last.content : '' };
        return { ...prev, messages: msgs };
      }
      return prev;
    });
  }

  /** ChatArea's retryLastMessage: resends the last user turn (its structured event when it had one). */
  retryLastMessage(): void {
    const msgs = this.activeConversation()?.messages ?? [];
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'user') { this.sendMessage(msgs[i].userEvent || msgs[i].content); break; }
    }
  }

  proceedWithDeferredMessage(): void {
    const orig = this.pendingOriginalMessage;
    this.clearDeferredMessage();
    // userQuestionAnswers: [] bypasses the contradiction pre-check on this retry.
    if (orig) this.sendMessage({ message: orig, userQuestionAnswers: [] });
  }

  clearDeferredMessage(): void {
    this.pendingOriginalMessage = null;
    this.hasDeferredMessage.set(false);
    this.pendingClarificationQuestions.set(null);
  }

  // =========================================================================
  // Misc actions
  // =========================================================================

  async submitFeedback(messageId: string, type: QualityFeedbackType, comment?: string): Promise<void> {
    const conv = this.currentConversation();
    if (!conv) return;
    await this.post(`/api/conversations/${conv.id}/feedback?messageId=${encodeURIComponent(messageId)}`, { type, comment, timestamp: Date.now() });
    this.currentConversation.update(prev => !prev ? prev
      : { ...prev, messages: prev.messages.map(m => (m.id === messageId ? { ...m, feedback: { type, comment, timestamp: Date.now() } } : m)) });
  }

  async resetMemory(): Promise<void> {
    const conv = this.currentConversation();
    if (!conv) return;
    const res = await this.post<Conversation>(`/api/conversations/${conv.id}/reset-memory`);
    this.currentConversation.set(res);
    this.conversations.update(prev => prev.map(c => (c.id === res.id ? res : c)));
  }

  inspectTurnTelemetry(telemetry: MessageTelemetry | null | undefined): void {
    this.activeTurnTelemetry.set(telemetry ?? null);
    this.isTokenInspectorOpen.set(true);
  }

  triggerWhiteboardGenerate(): void { this.whiteboardGenerateTick.update(t => t + 1); }

  async updateSharedSettings(patch: Partial<SharedSettings>): Promise<void> {
    this.sharedSettings.set(await this.put<SharedSettings>('/api/shared-settings', patch));
  }

  async resetSharedPrompt(): Promise<void> {
    this.sharedSettings.set(await this.post<SharedSettings>('/api/shared-settings/reset-prompt'));
  }

  clearLocalData(): void {
    lsSet(LS_KEY, null);
    lsSet(LS_ACTIVE_CONVERSATION_KEY, null);
  }

  setUserLocation(location: string): void { this.userLocation.set(location); }

  // ---- Cost helpers (Navbar convTotalCost / Sidebar sessionTotalCost, data/models.ts calcTurnCost) ----
  formatCost(usd: number): string { return formatCost(usd); }

  private conversationCost(conv: Conversation | null, fallbackToConvModel = true): number {
    return (conv?.messages ?? []).reduce((sum, m) => {
      if (m.role !== 'assistant' || !m.telemetry?.usage) return sum;
      const model = m.telemetry.model || (fallbackToConvModel ? conv?.model : '') || '';
      if (!model) return sum;
      return sum + (calcTurnCost(model, m.telemetry.usage) ?? 0);
    }, 0);
  }
}
