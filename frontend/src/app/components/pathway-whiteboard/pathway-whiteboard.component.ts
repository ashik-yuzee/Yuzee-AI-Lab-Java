import {
  Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, OnDestroy, Output,
  SimpleChanges, ViewChild, computed, effect, signal, untracked
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { TokenLabService } from '../../services/token-lab.service';
import { IconComponent } from '../shared/icon/icon.component';
import { calcTurnCost, formatCost } from '../../utils/chat-helpers';

/**
 * 1:1 port of the original PathwayWhiteboard.tsx: a resizable right-hand panel with a node palette,
 * a pre-generation interview, streamed pathway generation, drag/drop placement and reordering,
 * socket drag-to-reorder, status cycling, a node detail/edit/ask panel and read-aloud (TTS).
 */

const PATHWAY_MODEL = 'gemini-3.7-flash';

type NodeType =
  | 'goal' | 'phase' | 'course' | 'skill' | 'project'
  | 'resume' | 'apply' | 'milestone' | 'step'
  | 'current' | 'complete' | 'blocked' | 'option';
type PathwayStyleId = 'structured' | 'project-driven' | 'fast-track' | 'self-paced';

interface PWNode {
  id: string; label: string; subtitle?: string; description?: string;
  prerequisites?: string[]; type: NodeType;
}

interface Question { id: string; label: string; options: { value: string; icon?: string }[]; }
const ALL_QUESTIONS: Question[] = [
  {
    id: 'Experience level',
    label: "What's your current experience in this area?",
    options: [
      { value: 'Complete beginner', icon: '🌱' },
      { value: 'Some knowledge',    icon: '📖' },
      { value: 'Career changer',    icon: '🔄' },
      { value: 'Already in field',  icon: '💼' },
    ],
  },
  {
    id: 'Weekly commitment',
    label: 'How much time can you dedicate each week?',
    options: [
      { value: '1-5 hrs/wk',   icon: '☕' },
      { value: '5-10 hrs/wk',  icon: '⏳' },
      { value: '10-20 hrs/wk', icon: '🔥' },
      { value: '20+ hrs/wk',   icon: '🚀' },
    ],
  },
  {
    id: 'Main priority',
    label: 'What matters most to you right now?',
    options: [
      { value: 'Get hired fast',         icon: '⚡' },
      { value: 'Build solid foundation', icon: '🏗️' },
      { value: 'Keep costs low',         icon: '💰' },
      { value: 'Stay flexible',          icon: '🌊' },
    ],
  },
  {
    id: 'Learning style',
    label: 'How do you learn best?',
    options: [
      { value: 'Video courses',     icon: '🎬' },
      { value: 'Hands-on projects', icon: '🛠️' },
      { value: 'Reading docs',      icon: '📄' },
      { value: 'Mix of everything', icon: '🎯' },
    ],
  },
];

/** Detect answers from existing chat messages so we skip already-answered questions. */
export function detectAnswersFromChat(messages: { role: string; content: string }[]): Record<string, string> {
  const txt = messages.filter(m => m.role === 'user').map(m => m.content).join(' ');
  const d: Record<string, string> = {};
  if (/beginner|no experience|starting out|brand new|never (worked|studied|coded)/i.test(txt))
    d['Experience level'] = 'Complete beginner';
  else if (/career change|switchi|coming from|transition|different field/i.test(txt))
    d['Experience level'] = 'Career changer';
  else if (/some (experience|knowledge)|familiar|basic|worked a bit/i.test(txt))
    d['Experience level'] = 'Some knowledge';
  if (/full.?time|all day|20\+|40 hour|full time/i.test(txt))
    d['Weekly commitment'] = '20+ hrs/wk';
  else if (/part.?time|evenings|weekends|10.{0,3}20/i.test(txt))
    d['Weekly commitment'] = '10-20 hrs/wk';
  if (/get (?:a )?job|hire|land (?:a )?role|salary|employment/i.test(txt))
    d['Main priority'] = 'Get hired fast';
  else if (/free|budget|cheap|no money|low cost/i.test(txt))
    d['Main priority'] = 'Keep costs low';
  if (/video|youtube|udemy|coursera|watch/i.test(txt))
    d['Learning style'] = 'Video courses';
  else if (/project|build|practice|hands.?on/i.test(txt))
    d['Learning style'] = 'Hands-on projects';
  return d;
}

interface NodeMeta { badge: string; icon: string; shape: 'hero' | 'phase' | 'milestone' | 'card'; statusColor: string; }
const NODE_META: Record<NodeType, NodeMeta> = {
  goal:      { badge: 'Goal',        icon: 'Target',       shape: 'hero',      statusColor: '#6d28d9' },
  phase:     { badge: 'Phase',       icon: 'Layers',       shape: 'phase',     statusColor: '#78716c' },
  milestone: { badge: 'Checkpoint',  icon: 'Trophy',       shape: 'milestone', statusColor: '#d97706' },
  course:    { badge: 'Course',      icon: 'BookOpen',     shape: 'card',      statusColor: '#2563eb' },
  skill:     { badge: 'Skill',       icon: 'Lightbulb',    shape: 'card',      statusColor: '#7c3aed' },
  project:   { badge: 'Project',     icon: 'Code2',        shape: 'card',      statusColor: '#059669' },
  resume:    { badge: 'Resume',      icon: 'FileText',     shape: 'card',      statusColor: '#0284c7' },
  apply:     { badge: 'Apply',       icon: 'Send',         shape: 'card',      statusColor: '#d97706' },
  step:      { badge: 'Step',        icon: 'ChevronRight', shape: 'card',      statusColor: '#64748b' },
  current:   { badge: 'In Progress', icon: 'Zap',          shape: 'card',      statusColor: '#2563eb' },
  complete:  { badge: 'Complete',    icon: 'CheckCircle',  shape: 'card',      statusColor: '#16a34a' },
  blocked:   { badge: 'Blocked',     icon: 'AlertCircle',  shape: 'card',      statusColor: '#dc2626' },
  option:    { badge: 'Option',      icon: 'Star',         shape: 'card',      statusColor: '#71717a' },
};

const STATUS_CYCLE: Partial<Record<NodeType, NodeType>> = {
  step: 'current', current: 'complete', complete: 'step',
  course: 'current', skill: 'current', project: 'current', resume: 'current', apply: 'current', option: 'current',
};
const STATUS_LABELS: Partial<Record<NodeType, string>> = {
  step: 'To do', current: 'Doing', complete: 'Done',
  course: 'To do', skill: 'To do', project: 'To do', resume: 'To do', apply: 'To do',
};

const FLOW_TYPE_MAP: Record<string, NodeType> = {
  goal: 'goal', phase: 'phase', course: 'course', skill: 'skill', project: 'project',
  resume: 'resume', apply: 'apply', milestone: 'milestone', step: 'step',
  decision: 'option', ok: 'complete', warn: 'blocked',
};

const TYPE_LABELS: Record<NodeType, string> = {
  goal: 'Goal', phase: 'Phase', course: 'Course', skill: 'Skill', project: 'Project',
  resume: 'Resume', apply: 'Apply', milestone: 'Milestone', step: 'Step',
  current: 'In Progress', complete: 'Complete', blocked: 'Blocked', option: 'Option',
};

const PALETTE: { type: NodeType; icon: string }[] = [
  { type: 'step',      icon: 'ChevronRight' },
  { type: 'course',    icon: 'BookOpen' },
  { type: 'skill',     icon: 'Lightbulb' },
  { type: 'project',   icon: 'Code2' },
  { type: 'resume',    icon: 'FileText' },
  { type: 'apply',     icon: 'Send' },
  { type: 'phase',     icon: 'Layers' },
  { type: 'milestone', icon: 'Trophy' },
];

interface PathwayStyle {
  id: PathwayStyleId; label: string; description: string;
  popularity: number; duration: string; color: string; icon: string;
}
const PATHWAY_STYLES: PathwayStyle[] = [
  { id: 'structured',     label: 'Structured',     description: 'Courses → certs → projects',   popularity: 62, duration: '5-6 mo',  color: '#2563eb', icon: 'BookOpen' },
  { id: 'project-driven', label: 'Project-Driven', description: 'Build from day 1',             popularity: 23, duration: '4-5 mo',  color: '#059669', icon: 'Code2' },
  { id: 'fast-track',     label: 'Fast Track',     description: 'Free resources, max speed',    popularity: 10, duration: '2-3 mo',  color: '#d97706', icon: 'Flame' },
  { id: 'self-paced',     label: 'Self-Paced',     description: 'Flexible, sustainable rhythm', popularity: 5,  duration: '6-12 mo', color: '#7c3aed', icon: 'Feather' },
];

const GEN_STEPS = [
  'Analysing your goal…',
  'Designing phase structure…',
  'Selecting courses & resources…',
  'Building portfolio projects…',
  'Finalising your pathway…',
];

const LS_KEY = 'yuzee_pathway_v3';
const LS_WIDTH_KEY = 'yuzee_whiteboard_width';
const DEFAULT_WIDTH = 480;
const MIN_WIDTH = 380;
const MAX_WIDTH = 860;
const STREAM_DELAY_MS = 260;

function messageText(content: unknown): string {
  return typeof content === 'string' ? content : content == null ? '' : JSON.stringify(content);
}

@Component({
  selector: 'app-pathway-whiteboard',
  standalone: true,
  imports: [NgTemplateOutlet, FormsModule, IconComponent],
  templateUrl: './pathway-whiteboard.component.html',
  styleUrl: './pathway-whiteboard.component.scss',
})
export class PathwayWhiteboardComponent implements OnChanges, OnDestroy {
  @Input() conversationId: string | null = null;
  @Input() set open(v: boolean) { this.isOpen.set(!!v); }
  /** TokenLabContext.whiteboardGenerateTick: each increment (after 0) starts a generation. */
  @Input() generateTick = 0;
  @Output() closed = new EventEmitter<void>();
  /** TokenLabContext.setWhiteboardHasPathway — lets the navbar reflect whether a pathway exists. */
  @Output() hasPathwayChange = new EventEmitter<boolean>();

  @ViewChild('panel') panelRef?: ElementRef<HTMLElement>;
  @ViewChild('scroll') scrollRef?: ElementRef<HTMLElement>;

  readonly nodeMeta = NODE_META;
  readonly typeLabels = TYPE_LABELS;
  readonly statusLabels = STATUS_LABELS;
  readonly statusCycle = STATUS_CYCLE;
  readonly palette = PALETTE;
  readonly pathwayStyles = PATHWAY_STYLES;
  readonly genSteps = GEN_STEPS;

  isOpen = signal(false);
  private convIdSig = signal<string | null>(null);
  conversation = computed(() => {
    const id = this.convIdSig();
    return this.lab.conversations().find(c => c.id === id) ?? this.lab.activeConversation();
  });
  /** The original question effect's deps: currentConversation?.id and currentConversation?.messages?.length. */
  private convKey = computed(() => { const c = this.conversation(); return `${c?.id}|${c?.messages?.length}`; });

  panelWidth = signal(DEFAULT_WIDTH);

  // Pathway
  nodes = signal<PWNode[]>([]);
  selected = signal<string | null>(null);

  // Pre-gen
  selectedStyle = signal<PathwayStyleId | null>(null);
  answers = signal<Record<string, string>>({});
  currentQ = signal(0);
  isTyping = signal(false);
  typedLabel = signal('');
  showOpts = signal(false);
  activeQuestions = signal<Question[]>([]);
  private animTimer: ReturnType<typeof setTimeout> | null = null;
  private animTick: ReturnType<typeof setInterval> | null = null;

  // Generation
  wbTokens = signal<{ calls: number; inputTokens: number; outputTokens: number } | null>(null);
  fetching = signal(false);
  streaming = signal(false);
  genError = signal<string | null>(null);
  genProgress = signal(0);
  loadingStep = signal(0);
  streamCount = signal(0);

  // Detail panel
  editLabel = '';
  editDesc = '';
  nodeQuestion = '';
  nodeAnswer = signal<string | null>(null);
  nodeAnswering = signal(false);
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  // TTS
  ttsEnabled = signal(false);
  speaking = signal(false);

  // Socket drag
  connecting = signal<{ fromId: string; x: number; y: number } | null>(null);
  mousePos = signal({ x: 0, y: 0 });
  hoveredInput = signal<string | null>(null);

  // Palette drag
  private paletteDragType: NodeType | null = null;
  dropTargetIdx = signal<number | null>(null);

  // Reorder drag
  private reorderDragId: string | null = null;
  reorderOverId = signal<string | null>(null);

  private streamId = 0;
  private resizeStart: { startX: number; startW: number } | null = null;

  // Derived
  hasPathway = computed(() => this.nodes().length > 0);
  isBusy = computed(() => this.fetching() || this.streaming());
  showPreGen = computed(() => !this.hasPathway() && !this.isBusy());
  selectedNode = computed(() => {
    const id = this.selected();
    return id ? this.nodes().find(n => n.id === id) ?? null : null;
  });
  hasChat = computed(() => (this.conversation()?.messages?.length ?? 0) > 0);
  allAnswered = computed(() => this.currentQ() >= this.activeQuestions().length);
  answeredCount = computed(() => Object.values(this.answers()).filter(Boolean).length);
  phaseNumbers = computed(() => {
    const map = new Map<string, number>(); let c = 0;
    this.nodes().forEach(n => { if (n.type === 'phase') { c++; map.set(n.id, c); } });
    return map;
  });
  actionNodeCount = computed(() => this.nodes().filter(n => !['goal', 'phase', 'milestone'].includes(n.type)).length);
  lastFourNodes = computed(() => this.nodes().slice(-4));
  wbCost = computed(() => {
    const t = this.wbTokens();
    return t ? calcTurnCost(PATHWAY_MODEL, { inputTokens: t.inputTokens, outputTokens: t.outputTokens }) : null;
  });
  selectedStyleMeta = computed(() => PATHWAY_STYLES.find(s => s.id === this.selectedStyle()) ?? null);
  connectPath = computed(() => {
    const c = this.connecting(), m = this.mousePos();
    if (!c) return '';
    const midY = (c.y + m.y) / 2;
    return `M ${c.x} ${c.y} C ${c.x} ${midY}, ${m.x} ${midY}, ${m.x} ${m.y}`;
  });

  constructor(private api: ApiService, private lab: TokenLabService) {
    try {
      const v = localStorage.getItem(LS_WIDTH_KEY);
      this.panelWidth.set(v ? Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, +v)) : DEFAULT_WIDTH);
    } catch { this.panelWidth.set(DEFAULT_WIDTH); }
    try { localStorage.setItem(LS_WIDTH_KEY, String(this.panelWidth())); } catch {}

    // On open: load the saved pathway; build active questions from chat context (skipped when a
    // pathway was already in memory, as in the original), then start the first question animation.
    let wasOpen = false;
    effect(() => {
      const open = this.isOpen();
      this.convKey();
      untracked(() => {
        const conv = this.conversation();
        const justOpened = open && !wasOpen;
        wasOpen = open;
        this.stopAnim(); // the previous run's cleanup
        if (!open) return;
        if (justOpened) this.lab.isSidebarOpen.set(false);
        const hadNodes = this.nodes().length > 0;
        if (justOpened) this.loadSaved();
        if (hadNodes) return;
        const msgs = (conv?.messages ?? []).map(m => ({ role: m.role, content: messageText(m.content) }));
        const detected = detectAnswersFromChat(msgs);
        this.answers.update(prev => ({ ...detected, ...prev }));
        const missing = ALL_QUESTIONS.filter(q => !detected[q.id]);
        this.activeQuestions.set(missing);
        this.currentQ.set(0);
        this.typedLabel.set('');
        this.showOpts.set(false);
        if (missing.length > 0) this.startTypingAnim(missing[0].label);
        else this.isTyping.set(false);
      });
    }, { allowSignalWrites: true });

    // Sync to the host so the navbar can update its label.
    effect(() => { const has = this.hasPathway(); untracked(() => this.hasPathwayChange.emit(has)); });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['conversationId']) this.convIdSig.set(this.conversationId);
    if (changes['generateTick'] && !changes['generateTick'].firstChange && this.generateTick !== 0) void this.handleGenerate();
  }

  ngOnDestroy(): void {
    this.stopAnim();
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.onResizeEnd();
    this.stopConnecting();
    window.speechSynthesis?.cancel();
  }

  // ── Persistence ────────────────────────────────────────────────────────────
  private loadSaved(): void {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (!raw) return;
      const d = JSON.parse(raw) as { nodes: PWNode[]; style?: PathwayStyleId; answers?: Record<string, string> };
      if (Array.isArray(d.nodes) && d.nodes.length) {
        this.nodes.set(d.nodes);
        if (d.style) this.selectedStyle.set(d.style);
        if (d.answers) this.answers.set(d.answers);
      }
    } catch {}
  }

  private persist(n: PWNode[], s?: PathwayStyleId | null, a?: Record<string, string>): void {
    try { localStorage.setItem(LS_KEY, JSON.stringify({ nodes: n, style: s ?? undefined, answers: a ?? {} })); } catch {}
  }

  private persistNodes(n: PWNode[]): void { this.persist(n, this.selectedStyle(), this.answers()); }

  // ── Typing animation ───────────────────────────────────────────────────────
  private startTypingAnim(label: string): void {
    this.stopAnim();
    this.typedLabel.set(''); this.showOpts.set(false); this.isTyping.set(true);
    this.animTimer = setTimeout(() => {
      this.animTimer = null;
      this.isTyping.set(false);
      let i = 0;
      this.animTick = setInterval(() => {
        i++;
        this.typedLabel.set(label.slice(0, i));
        if (i >= label.length) {
          clearInterval(this.animTick!); this.animTick = null;
          this.showOpts.set(true);
        }
      }, 26);
    }, 650);
  }

  private stopAnim(): void {
    if (this.animTimer) { clearTimeout(this.animTimer); this.animTimer = null; }
    if (this.animTick) { clearInterval(this.animTick); this.animTick = null; }
  }

  chooseOption(q: Question, value: string): void {
    this.answers.update(prev => ({ ...prev, [q.id]: value }));
    setTimeout(() => {
      this.currentQ.update(n => n + 1);
      // Animate subsequent questions when the user answers one.
      const qs = this.activeQuestions(), cq = this.currentQ();
      if (cq < qs.length && this.nodes().length === 0 && !this.fetching() && !this.streaming()) this.startTypingAnim(qs[cq].label);
      else this.stopAnim();
      setTimeout(() => this.scrollToBottom(), 50);
    }, 300);
  }

  optionIcon(q: Question): string | undefined {
    return q.options.find(o => o.value === this.answers()[q.id])?.icon;
  }

  // ── Selection / detail panel ───────────────────────────────────────────────
  setSelected(id: string | null): void {
    if (this.saveTimer) { clearTimeout(this.saveTimer); this.saveTimer = null; }
    this.selected.set(id);
    if (!id) return;
    const n = this.nodes().find(x => x.id === id);
    if (!n) return;
    this.editLabel = n.label;
    this.editDesc = n.description ?? '';
    this.nodeQuestion = '';
    this.nodeAnswer.set(null);
    this.speakSelected();
    this.onEditChange(); // the original's debounced auto-save effect also runs when `selected` changes
  }

  toggleSelected(id: string): void { this.setSelected(this.selected() === id ? null : id); }

  /** Auto-save edits with a 700ms debounce. */
  onEditChange(): void {
    const id = this.selected();
    if (!id) return;
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      const label = this.editLabel, desc = this.editDesc;
      const u = this.nodes().map(n => n.id !== id ? n : {
        ...n,
        label: label.trim() || n.label,
        description: desc.trim() || undefined,
      });
      this.nodes.set(u); this.persistNodes(u);
    }, 700);
  }

  // ── TTS ────────────────────────────────────────────────────────────────────
  private speak(text: string): void {
    const synth = typeof window !== 'undefined' ? window.speechSynthesis : null;
    if (!this.ttsEnabled() || !synth) return;
    synth.cancel();
    const utter = new SpeechSynthesisUtterance(text);
    const doIt = () => {
      const voices = synth.getVoices();
      const v = voices.find(x => x.name.includes('Google') && x.lang === 'en-US')
             || voices.find(x => !x.localService && x.lang.startsWith('en'))
             || voices.find(x => x.lang.startsWith('en'));
      if (v) utter.voice = v;
      utter.rate = 0.93; utter.pitch = 1.05;
      utter.onstart = () => this.speaking.set(true);
      utter.onend = () => this.speaking.set(false);
      utter.onerror = () => this.speaking.set(false);
      synth.speak(utter);
    };
    if (synth.getVoices().length === 0) synth.addEventListener('voiceschanged', doIt, { once: true });
    else doIt();
  }

  private stopTts(): void { window.speechSynthesis?.cancel(); this.speaking.set(false); }

  private speakSelected(): void {
    if (!this.ttsEnabled() || !this.selected()) return;
    const n = this.nodes().find(x => x.id === this.selected());
    if (n) this.speak(`${n.label}${n.description ? '. ' + n.description : ''}`);
  }

  toggleTts(): void {
    this.ttsEnabled.update(v => !v);
    this.stopTts();
    this.speakSelected();
  }

  // ── Keyboard ───────────────────────────────────────────────────────────────
  @HostListener('window:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if (!this.isOpen()) return;
    const tag = (document.activeElement as HTMLElement)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    if ((e.key === 'Delete' || e.key === 'Backspace') && this.selected()) this.deleteSelected();
    if (e.key === 'Escape') this.setSelected(null);
  }

  // ── Panel resize ───────────────────────────────────────────────────────────
  private onResizeMove = (e: MouseEvent) => {
    if (!this.resizeStart) return;
    const w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, this.resizeStart.startW + this.resizeStart.startX - e.clientX));
    this.panelWidth.set(w);
    try { localStorage.setItem(LS_WIDTH_KEY, String(w)); } catch {}
  };
  private onResizeEnd = () => {
    this.resizeStart = null;
    document.removeEventListener('mousemove', this.onResizeMove);
    document.removeEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
  };
  onResizeStart(e: MouseEvent): void {
    e.preventDefault();
    this.resizeStart = { startX: e.clientX, startW: this.panelWidth() };
    document.addEventListener('mousemove', this.onResizeMove);
    document.addEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
  }

  // ── Socket drag (output → input reorder) ───────────────────────────────────
  private onConnectMove = (e: MouseEvent) => {
    if (!this.panelRef) return;
    const r = this.panelRef.nativeElement.getBoundingClientRect();
    this.mousePos.set({ x: e.clientX - r.left, y: e.clientY - r.top });
  };
  // Window listener: runs after the target socket's own mouseup (bubbling order), as in the original.
  private onConnectUp = () => this.stopConnecting();
  private stopConnecting(): void {
    this.connecting.set(null);
    window.removeEventListener('mousemove', this.onConnectMove);
    window.removeEventListener('mouseup', this.onConnectUp);
  }

  startConnect(nodeId: string, e: MouseEvent): void {
    e.preventDefault(); e.stopPropagation();
    if (!this.panelRef) return;
    const r = this.panelRef.nativeElement.getBoundingClientRect();
    const cr = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const p = { x: cr.left + cr.width / 2 - r.left, y: cr.top + cr.height / 2 - r.top };
    this.connecting.set({ fromId: nodeId, ...p });
    this.mousePos.set(p);
    window.addEventListener('mousemove', this.onConnectMove);
    window.addEventListener('mouseup', this.onConnectUp);
  }

  finishConnect(targetId: string): void {
    const c = this.connecting();
    if (!c || c.fromId === targetId) { this.stopConnecting(); return; }
    const a = [...this.nodes()];
    const fi = a.findIndex(n => n.id === c.fromId);
    const ti = a.findIndex(n => n.id === targetId);
    if (fi !== -1 && ti !== -1) {
      const [moved] = a.splice(fi, 1);
      a.splice(ti > fi ? ti - 1 : ti, 0, moved);
      this.nodes.set(a); this.persistNodes(a);
    }
    this.stopConnecting();
  }

  onSocketEnter(nodeId: string, side: 'top' | 'bottom'): void {
    if (side === 'top' && this.connecting()) this.hoveredInput.set(nodeId);
  }

  onSocketUp(nodeId: string, side: 'top' | 'bottom'): void {
    if (side === 'top' && this.connecting()) this.finishConnect(nodeId);
  }

  // ── Palette drag + click ───────────────────────────────────────────────────
  onPaletteDragStart(e: DragEvent, type: NodeType): void {
    e.dataTransfer?.setData('nodeType', type);
    this.paletteDragType = type;
  }
  onPaletteDragEnd(): void { this.paletteDragType = null; this.dropTargetIdx.set(null); }

  addNodeAtEnd(type: NodeType): void {
    const id = `n-${Date.now()}`;
    const u = [...this.nodes(), { id, label: TYPE_LABELS[type], type }];
    this.nodes.set(u); this.persistNodes(u);
    this.setSelected(id);
    setTimeout(() => this.scrollToBottom(), 50);
  }

  onDropZoneDragOver(e: DragEvent, idx: number): void { e.preventDefault(); this.dropTargetIdx.set(idx); }

  onDropZoneDrop(e: DragEvent, idx: number): void {
    e.preventDefault();
    const type = (e.dataTransfer?.getData('nodeType') || this.paletteDragType) as NodeType;
    if (!type) return;
    const id = `n-${Date.now()}`;
    const u = [...this.nodes()]; u.splice(idx, 0, { id, label: TYPE_LABELS[type], type });
    this.nodes.set(u); this.persistNodes(u);
    this.setSelected(id); this.paletteDragType = null; this.dropTargetIdx.set(null);
  }

  // ── Reorder via drag handle ────────────────────────────────────────────────
  onReorderDragStart(id: string): void { this.reorderDragId = id; }
  onReorderDragOver(e: DragEvent, id: string): void { e.preventDefault(); this.reorderOverId.set(id); }
  onReorderDrop(e: DragEvent, targetId: string): void {
    e.preventDefault(); this.reorderOverId.set(null);
    const srcId = this.reorderDragId; this.reorderDragId = null;
    if (!srcId || srcId === targetId) return;
    const a = [...this.nodes()];
    const fi = a.findIndex(n => n.id === srcId);
    const ti = a.findIndex(n => n.id === targetId);
    if (fi === -1 || ti === -1) return;
    const [m] = a.splice(fi, 1); a.splice(ti, 0, m);
    this.nodes.set(a); this.persistNodes(a);
  }

  // ── Node ops ───────────────────────────────────────────────────────────────
  cycleStatus(id: string, e: Event): void {
    e.stopPropagation();
    const u = this.nodes().map(n => n.id !== id ? n : { ...n, type: STATUS_CYCLE[n.type] ?? n.type });
    this.nodes.set(u); this.persistNodes(u);
  }

  deleteSelected(): void {
    const id = this.selected();
    if (!id) return;
    const u = this.nodes().filter(n => n.id !== id);
    this.nodes.set(u); this.persistNodes(u);
    this.setSelected(null);
  }

  clearPathway(): void {
    this.streamId++; this.nodes.set([]); this.setSelected(null);
    this.persist([], null, this.answers()); this.genError.set(null); this.streaming.set(false); this.fetching.set(false);
  }

  close(): void { this.closed.emit(); }

  meta(node: PWNode): NodeMeta { return NODE_META[node.type]; }
  typeOf(node: PWNode): NodeType { return node.type; }

  tagsOf(node: PWNode): string[] {
    return node.subtitle?.split('·').map(t => t.trim()).filter(Boolean) ?? [];
  }

  statusDotColor(type: NodeType): string {
    return type === 'current' ? '#2563eb' : type === 'complete' ? '#16a34a' : type === 'blocked' ? '#dc2626' : '#d1d5db';
  }

  readonly formatCost = formatCost;

  toLocale(n: number): string { return n.toLocaleString(); }

  private scrollToBottom(): void {
    const el = this.scrollRef?.nativeElement;
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }

  // ── Node question ──────────────────────────────────────────────────────────
  async askNodeQuestion(): Promise<void> {
    const id = this.selected();
    if (!this.nodeQuestion.trim() || !id) return;
    const n = this.nodes().find(x => x.id === id);
    if (!n) return;
    this.nodeAnswering.set(true); this.nodeAnswer.set(null);
    const firstUser = this.conversation()?.messages?.find(m => m.role === 'user');
    const ctx = messageText(firstUser?.content).slice(0, 300);
    const payload = { nodeLabel: n.label, nodeSubtitle: n.subtitle ?? '', question: this.nodeQuestion.trim(), goalContext: ctx };
    let answer: string | null;
    try {
      const res = await firstValueFrom(this.api.post<{ answer: string }>('/pathway/explain', payload));
      answer = res?.answer ?? null;
    } catch { answer = 'Could not get an explanation.'; }
    this.nodeAnswer.set(answer); this.nodeAnswering.set(false);
  }

  onAskKeyDown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void this.askNodeQuestion(); }
  }

  // ── Generation ─────────────────────────────────────────────────────────────
  async handleGenerate(): Promise<void> {
    const conv = this.conversation();
    if (!conv) return;
    const msgs = (conv.messages ?? []).map(m => ({ role: m.role, content: messageText(m.content) }));
    if (!msgs.length) return;

    const myId = ++this.streamId;
    this.fetching.set(true); this.streaming.set(false); this.genError.set(null);
    this.genProgress.set(0); this.streamCount.set(0); this.loadingStep.set(0);
    this.nodes.set([]); this.setSelected(null);

    const stepTimer = setInterval(() => this.loadingStep.update(p => Math.min(p + 1, GEN_STEPS.length - 1)), 1900);
    const style = this.selectedStyle(), answers = this.answers();

    try {
      const result = await this.generatePathway(msgs, style ?? 'structured', answers);
      clearInterval(stepTimer);
      if (this.streamId !== myId) return;
      if (!result.nodes?.length) { this.genError.set('No pathway generated. Describe your goal more specifically.'); this.fetching.set(false); return; }

      const seen = new Set<string>();
      const typed: PWNode[] = result.nodes.map((n: any, idx: number) => {
        let id = String(n.id ?? idx); if (seen.has(id)) id = `${id}_${idx}`; seen.add(id);
        return { id, label: n.label, subtitle: n.subtitle ?? undefined, description: n.description ?? undefined, type: (FLOW_TYPE_MAP[n.node_type] ?? 'step') as NodeType };
      });

      this.fetching.set(false); this.streaming.set(true);

      for (let i = 0; i < typed.length; i++) {
        if (this.streamId !== myId) return;
        this.genProgress.set(Math.round((i / typed.length) * 100));
        await new Promise(r => setTimeout(r, STREAM_DELAY_MS));
        if (this.streamId !== myId) return;
        const accumulated = typed.slice(0, i + 1);
        this.nodes.set(accumulated);
        if (i % 4 === 0 || i === typed.length - 1) this.persist(accumulated, style, answers);
        this.streamCount.set(i + 1);
        requestAnimationFrame(() => this.scrollToBottom());
      }

      if (this.streamId !== myId) return;
      this.persist(typed, style, answers);
      this.genProgress.set(100); this.streaming.set(false);
      firstValueFrom(this.api.get<{ calls: number; inputTokens: number; outputTokens: number }>('/pathway/stats'))
        .then(s => this.wbTokens.set(s ?? { calls: 0, inputTokens: 0, outputTokens: 0 }))
        .catch(() => this.wbTokens.set({ calls: 0, inputTokens: 0, outputTokens: 0 }));
      if (this.ttsEnabled()) this.speak('Your pathway is ready.');
    } catch (err: any) {
      clearInterval(stepTimer);
      if (this.streamId === myId) { this.genError.set(err?.message ?? 'Generation failed'); this.fetching.set(false); this.streaming.set(false); }
    }
  }

  /**
   * services/api.ts generatePathway(): posts {messages, style, answers}; the server builds the
   * prompt from the last 16 turns + learner profile.
   */
  private async generatePathway(messages: { role: string; content: string }[], style: string, answers: Record<string, string>): Promise<{ nodes: any[]; edges: any[] }> {
    try {
      return await firstValueFrom(this.api.post<{ nodes: any[]; edges: any[] }>('/pathway/generate', { messages, style, answers }));
    } catch (e: any) {
      if (e?.status === 0) throw new Error('Failed to fetch'); // fetch() network failure message, as in the original
      throw new Error(e?.error?.error || `Server error ${e?.status ?? ''}`.trim());
    }
  }
}
