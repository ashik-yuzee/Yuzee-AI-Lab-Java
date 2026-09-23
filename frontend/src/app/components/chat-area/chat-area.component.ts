import { Component, ElementRef, OnDestroy, SecurityContext, ViewChild, computed, effect, signal, untracked } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { TokenLabService } from '../../services/token-lab.service';
import { ObjectivesService } from '../../services/objectives.service';
import { ChatMessage, MessageTelemetry, UserEvent, YuzeeResponseV13 } from '../../models/types';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { ChatProgressComponent } from '../chat-progress/chat-progress.component';
import { ComposerComponent, ComposerSendFn } from '../composer/composer.component';
import { ChatTurnDividerComponent } from '../chat-turn-divider/chat-turn-divider.component';
import { ResearchDetailsComponent } from '../research-details/research-details.component';
import { SkillSuggestionsComponent } from '../skill-suggestions/skill-suggestions.component';
import { ObjectiveHistoryComponent } from '../objectives/objective-history.component';
import { ObjectiveSuggestionsComponent } from '../objectives/objective-suggestions.component';
import { IconComponent } from '../shared/icon/icon.component';
import { acceptedResponse, readableMarkdown, responseToReadableText } from '../../utils/response-presentation';
import { markdownToHtml } from '../protocol-renderer/protocol-presentation';
import {
  SkillChoice, activitySearchContext, allowSkillReview, calcTurnCost, canReviewResponseSkills, formatCost, modelChipKind,
  modelShortName, outdatedHelpClaim, researchOffer, shouldDeferChatQuestion, suggestionText, workspaceQuestionOwner
} from '../../utils/chat-helpers';

/** Per-message derivations that ChatArea.tsx computes inline during render. */
interface AssistantView {
  structured: YuzeeResponseV13 | null;
  isCounsellorGate: boolean;
  outdatedClaim: boolean;
  markdownHtml: SafeHtml | '';
  offer: any;
}

interface TurnGroup { turnNum: number; userMsg?: ChatMessage; assistantMsgs: ChatMessage[] }

interface ErrorConfig { icon: string; tone: 'amber' | 'red' | 'slate'; title: string; detail: string }

const STARTER_PROMPTS = [
  { title: 'Cybersecurity Pathway', prompt: 'Build a realistic pathway from IT support into a junior cybersecurity analyst.' },
  { title: 'Degree vs Apprenticeship', prompt: 'Compare university degree vs degree apprenticeship for software engineering.' },
  { title: 'Skill Gap Analysis', prompt: 'What skills and certifications am I missing for a cloud security architect role?' },
  { title: 'Career Switcher Plan', prompt: 'Help me transition from digital marketing into product management with no coding background.' },
  { title: '6-Month Study Plan', prompt: 'Build a structured 6-month study plan for AWS Solutions Architect certification.' },
];

const HELP_UPDATE_PROMPT = 'Please correct the previous HECS-HELP repayment explanation using current official guidance. State which financial year the evidence supports and identify any future-year details that still need checking.';

/** Port of ChatArea.tsx. The host is display:contents so #chat-viewport sits directly in App.tsx's <main>. */
@Component({
  selector: 'chat-area',
  standalone: true,
  imports: [
    ProtocolRendererComponent, ChatProgressComponent, ComposerComponent, ChatTurnDividerComponent,
    ResearchDetailsComponent, SkillSuggestionsComponent, ObjectiveHistoryComponent, ObjectiveSuggestionsComponent,
    IconComponent,
  ],
  templateUrl: './chat-area.component.html',
  styleUrl: './chat-area.component.scss'
})
export class ChatAreaComponent implements OnDestroy {
  @ViewChild('messagesEnd') private messagesEnd?: ElementRef<HTMLDivElement>;

  readonly starterPrompts = STARTER_PROMPTS;
  readonly copiedId = signal<string | null>(null);
  readonly speakingId = signal<string | null>(null);
  readonly rawJsonIds = signal<ReadonlySet<string>>(new Set());
  private copyTimer: ReturnType<typeof setTimeout> | null = null;
  private lastCounsellorGateId: string | null = null;
  private readonly views = new WeakMap<ChatMessage, AssistantView>();
  private readonly acceptedCache = new WeakMap<ChatMessage, YuzeeResponseV13 | null>();

  readonly messages = computed(() => this.lab.activeConversation()?.messages ?? []);

  readonly lastAssistant = computed(() => [...this.messages()].reverse().find(m =>
    m.role === 'assistant' && !m.isStreaming && !m.error && m.schemaValid !== false && m.semanticValid !== false && this.accepted(m)));
  readonly lastStructured = computed(() => { const last = this.lastAssistant(); return last && !last.error ? this.accepted(last) : null; });
  readonly completedRoute = computed<any>(() => this.lastAssistant()?.routing || this.lastAssistant()?.telemetry?.routing);
  readonly latestMessage = computed(() => this.messages().at(-1));
  readonly latestUser = computed(() => [...this.messages()].reverse().find(m => m.role === 'user'));
  readonly userTexts = computed(() => this.messages().filter(m => m.role === 'user').map(m => m.content));
  readonly questionCoordination = computed(() =>
    workspaceQuestionOwner(this.objectives.sessions(), this.objectives.enabled() && this.objectives.open() ? this.objectives.selectedId() : null));
  readonly hasSuggestions = computed(() => !!(!this.lab.isStreaming() && this.latestMessage() === this.lastAssistant() &&
    !this.lastAssistant()?.streamStopped && canReviewResponseSkills(this.lastStructured(), this.latestUser()?.content || '')));
  readonly reviewText = computed(() => this.hasSuggestions() ? suggestionText(this.lastStructured(), this.userTexts().slice(-3)) : '');
  readonly objectiveReviewText = computed(() => this.hasSuggestions() ? activitySearchContext(this.lastStructured(), this.userTexts().slice(-4)) : '');
  readonly completedToolId = computed(() => this.completedRoute()?.status === 'selected' ? this.completedRoute().toolId : undefined);

  /** Reuse only details actually submitted by the person in this conversation. */
  readonly knownFields = computed(() => this.messages().reduce<Record<string, string>>((known, msg) => {
    if (msg.role !== 'user') return known;
    const event = msg.userEvent as any;
    const values = event?.userEvent?.interaction?.fields || event?.interaction?.fields || event?.fields;
    if (values && typeof values === 'object') for (const [key, value] of Object.entries(values)) if (typeof value === 'string') known[key] = value;
    return known;
  }, {}));

  /** Group flat messages into turns: { turnNum, userMsg?, assistantMsgs[] } */
  readonly turnGroups = computed<TurnGroup[]>(() => {
    const groups: TurnGroup[] = [];
    let turnCount = 0;
    this.messages().forEach(msg => {
      if (msg.role === 'user') {
        turnCount++;
        groups.push({ turnNum: turnCount, userMsg: msg, assistantMsgs: [] });
      } else if (groups.length > 0) {
        groups[groups.length - 1].assistantMsgs.push(msg);
      } else {
        groups.push({ turnNum: 0, assistantMsgs: [msg] });
      }
    });
    return groups;
  });

  readonly allowSkillReview = allowSkillReview;

  constructor(readonly lab: TokenLabService, readonly objectives: ObjectivesService, private sanitizer: DomSanitizer) {
    // Auto-scroll on new messages, stream start/end and when suggestions appear.
    effect(() => {
      this.messages().length; this.lab.isStreaming(); this.hasSuggestions();
      setTimeout(() => this.messagesEnd?.nativeElement.scrollIntoView({ behavior: 'smooth' }));
    });
    // Counsellor gate: a raw clarification-questions payload opens the questions popup once.
    effect(() => {
      const msgs = this.messages();
      if (msgs.length === 0 || this.lab.isStreaming()) return;
      const last = msgs[msgs.length - 1];
      if (last.role !== 'assistant' || last.isStreaming || !last.content) return;
      if (last.id === this.lastCounsellorGateId) return;
      const trimmed = last.content.trim();
      if (!trimmed.startsWith('{')) return;
      try {
        const parsed = JSON.parse(trimmed);
        if (parsed.question_controller?.ask_questions === true && Array.isArray(parsed.clarification_questions) && parsed.clarification_questions.length > 0) {
          this.lastCounsellorGateId = last.id;
          untracked(() => this.lab.pendingClarificationQuestions.set({ questions: parsed.clarification_questions, bridgeMessage: parsed.frontend?.bridge_message }));
        }
      } catch { /* not counsellor gate */ }
    });
  }

  ngOnDestroy(): void {
    if (this.copyTimer) clearTimeout(this.copyTimer);
  }

  // ---- message derivations ----

  /** acceptedResponse() runs full protocol validation; messages are immutable, so cache per object. */
  private accepted(msg: ChatMessage): YuzeeResponseV13 | null {
    if (!this.acceptedCache.has(msg)) this.acceptedCache.set(msg, acceptedResponse(msg.structuredResponse || msg.content));
    return this.acceptedCache.get(msg)!;
  }

  private parseStructuredResponse(msg: ChatMessage): YuzeeResponseV13 | null {
    if (msg.streamStopped || (msg.isStreaming && !msg.structuredResponse)) return null;
    if (msg.error || msg.schemaValid === false || msg.semanticValid === false || msg.telemetry?.validation?.protocolAccepted === false) return null;
    return this.accepted(msg);
  }

  view(msg: ChatMessage): AssistantView {
    const cached = this.views.get(msg);
    if (cached) return cached;
    const structured = this.parseStructuredResponse(msg);
    const isCounsellorGate = !structured && !msg.isStreaming && !!msg.content?.trim().startsWith('{') && (() => {
      try { return JSON.parse(msg.content.trim()).question_controller?.ask_questions === true; } catch { return false; }
    })();
    const showMarkdown = !structured && !isCounsellorGate && !msg.error && !msg.streamStopped && !msg.isStreaming;
    const view: AssistantView = {
      structured,
      isCounsellorGate,
      outdatedClaim: !!structured && outdatedHelpClaim(responseToReadableText(structured)),
      offer: researchOffer(msg.preflight || msg.telemetry?.preflight),
      // <Markdown remarkPlugins={[remarkGfm]}>: sanitised first; only fixed task/footnote/alignment markup is added after.
      markdownHtml: showMarkdown ? this.sanitizer.bypassSecurityTrustHtml(markdownToHtml(msg.telemetry?.validation?.protocolAccepted === false
        ? 'This response could not be displayed. Please try again.'
        : readableMarkdown(responseToReadableText(msg.content) || 'Preparing your answer…'),
        h => this.sanitizer.sanitize(SecurityContext.HTML, h) ?? '', { plainTables: true })) : '',
    };
    this.views.set(msg, view);
    return view;
  }

  isLastAssistantMsg(msgId: string): boolean { return this.lastAssistant()?.id === msgId; }

  deferQuestion(msg: ChatMessage, structured: YuzeeResponseV13): boolean {
    return this.isLastAssistantMsg(msg.id) && shouldDeferChatQuestion(structured, this.questionCoordination());
  }

  userDisplayContent(msg: ChatMessage): string {
    const content = msg.content?.startsWith('[QUESTION_ANSWERS:') ? msg.content.replace(/^\[QUESTION_ANSWERS:.*?\]\n/, '') : msg.content;
    return content?.replace(/ Research reference: [a-f0-9-]{36}$/, '') ?? '';
  }

  hasObjectiveHistory(msg: ChatMessage): boolean {
    const id = msg.serverMessageId || msg.id;
    return this.objectives.enabled() && this.objectives.sessions().some(s => s.sourceMessageId === id);
  }

  // ---- telemetry footer ----

  showTelemetry(msg: ChatMessage): boolean {
    return !!msg.telemetry && !msg.isStreaming && !!(msg.structuredResponse || msg.content) && !msg.telemetry?.usage?.isMock;
  }
  modelShortName = modelShortName;
  modelChipKind = modelChipKind;
  latencyMs(t: MessageTelemetry): number | undefined { return t.timeline?.totalLatencyMs ?? t.usage.latencyMs; }
  compactTokens(total: number): string { return total > 1000 ? `${(total / 1000).toFixed(1)}k` : String(total); }
  turnCost(t: MessageTelemetry): number | null { return t.usage ? calcTurnCost(t.model || '', t.usage) : null; }
  formatCost = formatCost;
  num(n: number | null | undefined): string { return (n ?? 0).toLocaleString(); }
  uncachedInput(t: MessageTelemetry): number { return t.usage.uncachedInputTokens ?? t.usage.inputTokens - (t.usage.cachedTokens ?? 0); }

  toggleRawJson(id: string): void {
    this.rawJsonIds.update(prev => { const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next; });
  }

  // ---- errors ----

  errorConfig(errorCode?: string, errorMsg?: string): ErrorConfig {
    const configs: Record<string, ErrorConfig> = {
      RATE_LIMIT: { icon: 'Clock', tone: 'amber', title: 'Rate limit reached', detail: errorMsg || 'Gemini rate limit reached. Wait a moment and try again.' },
      QUOTA_EXHAUSTED: { icon: 'AlertTriangle', tone: 'red', title: 'Daily quota reached', detail: errorMsg || 'The free-tier Gemini quota for today has been used up.' },
      AUTH_ERROR: { icon: 'ShieldAlert', tone: 'red', title: 'The assistant cannot connect', detail: 'The connection needs attention. Your answer has been kept.' },
      FUNCTION_TIMEOUT: { icon: 'Clock', tone: 'amber', title: 'The reply took too long', detail: 'Your answer has been kept. Please try again.' },
      PROVIDER_ERROR: { icon: 'Wifi', tone: 'slate', title: 'We couldn’t complete that reply', detail: errorMsg || 'Your answer has been kept. Please try again.' },
    };
    return configs[errorCode || 'PROVIDER_ERROR'] || configs['PROVIDER_ERROR'];
  }

  // ---- actions ----

  readonly sendText = (text: string) => this.lab.sendMessage(text);
  readonly composerSend: ComposerSendFn = (text, attachments) => this.lab.sendMessage(text, attachments);
  readonly handleInteractionEvent = (event: UserEvent) => this.lab.sendMessage(event.userEvent ? event : event.value || '');
  readonly chooseSkill = (message: string, skillChoice: SkillChoice) => this.lab.sendMessage({ message, userQuestionAnswers: [], skillChoice });

  askHelpUpdate(): void { void this.lab.sendMessage(HELP_UPDATE_PROMPT); }

  retryLastMessage(): void { this.lab.retryLastMessage(); }

  copyMessage(id: string, text: string): void {
    navigator.clipboard.writeText(responseToReadableText(text));
    this.copiedId.set(id);
    if (this.copyTimer) clearTimeout(this.copyTimer);
    this.copyTimer = setTimeout(() => this.copiedId.set(null), 2000);
  }

  speakMessage(id: string, value: unknown): void {
    const synth = window.speechSynthesis;
    if (!synth) return;
    if (this.speakingId() === id) { synth.cancel(); this.speakingId.set(null); return; }
    synth.cancel();
    const plain = responseToReadableText(value).replace(/[#*`_~[\]()>]/g, '').replace(/\n+/g, ' ').trim();
    const utter = new SpeechSynthesisUtterance(plain.slice(0, 3000));
    const setVoice = () => {
      const v = synth.getVoices().find(v => v.name.includes('Google') && v.lang === 'en-US')
        || synth.getVoices().find(v => !v.localService && v.lang.startsWith('en'))
        || synth.getVoices().find(v => v.lang.startsWith('en'));
      if (v) utter.voice = v;
      utter.rate = 0.93; utter.pitch = 1.05;
      utter.onstart = () => this.speakingId.set(id);
      utter.onend = utter.onerror = () => this.speakingId.set(null);
      synth.speak(utter);
    };
    synth.getVoices().length === 0 ? synth.addEventListener('voiceschanged', setVoice, { once: true }) : setVoice();
  }
}
