import {
  Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output,
  SimpleChanges, computed, effect, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DragDropModule, CdkDragEnd, CdkDragMove } from '@angular/cdk/drag-drop';
import { Subject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { ApiService } from '../../services/api.service';

type Point = { x: number; y: number };

/**
 * Wire shape for POST /api/pathway/generate's nodes — matches the backend's PathwayNode.java
 * exactly ({id, label, subtitle?, description?, prerequisites?, type}). `status` is added and
 * owned entirely client-side (per PathwayNode.java's own doc comment: the server never models
 * progress status, only the old React app's local click-to-cycle state did).
 */
interface PathwayNode {
  id: string;
  label: string;
  subtitle?: string;
  description?: string;
  prerequisites?: string[];
  type: string;
  status: string;
}
/** Matches PathwayEdge.java exactly: just a directed id pair, no label/condition. */
interface PathwayEdge { from: string; to: string; }

interface Suggestion { type: string; label: string; subtitle?: string; reason?: string; }
interface PathwayStats { calls?: number; inputTokens?: number; outputTokens?: number; }

interface TypeMeta { type: string; label: string; color: string; glyph: string; }

/** Full set the AI is prompted to emit (PathwayWhiteboardService.generate's prompt), plus the
 *  client-only "step" fallback used when a generated node's type is missing. Used for badges on
 *  every node regardless of source; only a curated subset is offered in the drag palette below. */
const TYPE_META: TypeMeta[] = [
  { type: 'goal', label: 'Goal', color: '#4f2fa8', glyph: '◎' },
  { type: 'phase', label: 'Phase', color: '#78716c', glyph: '≡' },
  { type: 'step', label: 'Step', color: '#64748b', glyph: '▸' },
  { type: 'course', label: 'Course', color: '#2563eb', glyph: '📘' },
  { type: 'skill', label: 'Skill', color: '#7c3aed', glyph: '✦' },
  { type: 'project', label: 'Project', color: '#059669', glyph: '⚙' },
  { type: 'resume', label: 'Resume', color: '#0284c7', glyph: '✉' },
  { type: 'apply', label: 'Apply', color: '#d97706', glyph: '➤' },
  { type: 'milestone', label: 'Milestone', color: '#d97706', glyph: '◆' },
];
/** Types a learner can manually drag/click onto the canvas (mirrors the old app's PALETTE, which
 *  excluded "goal" since the AI always creates exactly one). */
const PALETTE_TYPES = ['step', 'course', 'skill', 'project', 'resume', 'apply', 'phase', 'milestone'];

const STATUS_CYCLE: Record<string, string> = {
  upcoming: 'current', current: 'completed', completed: 'blocked', blocked: 'upcoming',
};
const STATUS_LABEL: Record<string, string> = {
  upcoming: 'Upcoming', current: 'In Progress', completed: 'Completed', blocked: 'Blocked',
};
const STATUS_COLOR: Record<string, string> = {
  upcoming: '#94a3b8', current: '#2563eb', completed: '#16a34a', blocked: '#dc2626',
};

/** The old app's four named presets (PathwayWhiteboard.tsx's PATHWAY_STYLES) — the backend's
 *  generate() takes the same style ids and already has copy for each baked into its prompt. */
const STYLES = [
  { id: 'structured', label: 'Structured' },
  { id: 'project-driven', label: 'Project-Driven' },
  { id: 'fast-track', label: 'Fast Track' },
  { id: 'self-paced', label: 'Self-Paced' },
];

const NODE_W = 216;
const NODE_H = 96;
const MIN_WIDTH = 380;
const MAX_WIDTH = 900;
const DEFAULT_WIDTH = 520;
const WIDTH_KEY = 'yz_pathway_whiteboard_width';

/**
 * Standalone visual career-pathway editor — Angular port of the old React app's
 * PathwayWhiteboard.tsx (1198 lines): a resizable side panel with a node palette, a freeform
 * drag-and-drop canvas, socket-to-socket connections rendered as SVG bezier curves, per-node
 * status cycling, a debounced node detail/edit panel, AI generate/recommend/explain actions
 * against the real PathwayWhiteboardService backend, Web Speech API read-aloud, and
 * localStorage persistence keyed by conversation id.
 *
 * Deliberate simplifications vs. the 1198-line original (see individual methods for detail):
 *  - Socket connections are click-output-then-click-input instead of a raw mousedown/mousemove
 *    drag-to-connect gesture with a live preview line (explicitly sanctioned by the porting brief
 *    as a "meaningfully simpler" interaction).
 *  - Node repositioning uses Angular CDK drag-drop (cdkDrag + cdkDragHandle) instead of the old
 *    app's hand-rolled native HTML5 drag/drop + mousedown tracking.
 *  - Generation is loading-state-then-reveal-all, not the old app's typewriter intro +
 *    per-node streaming animation.
 *  - No pre-generation "interview" (the old app asked 4 quick multiple-choice questions before
 *    generating) — goal + style picker go straight to POST /api/pathway/generate.
 *
 * Selector: app-pathway-whiteboard
 * Inputs:   conversationId?: string, open: boolean
 * Outputs:  closed: EventEmitter<void>
 */
@Component({
  selector: 'app-pathway-whiteboard',
  standalone: true,
  imports: [CommonModule, FormsModule, DragDropModule],
  templateUrl: './pathway-whiteboard.component.html',
  styleUrl: './pathway-whiteboard.component.scss',
})
export class PathwayWhiteboardComponent implements OnInit, OnChanges, OnDestroy {
  @Input() conversationId: string | null = null;
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  readonly typeMeta = TYPE_META;
  readonly paletteTypes = PALETTE_TYPES;
  readonly styles = STYLES;
  readonly statusLabel = STATUS_LABEL;
  readonly statusColor = STATUS_COLOR;
  readonly ttsAvailable = typeof window !== 'undefined' && 'speechSynthesis' in window;

  // ── Graph state ──────────────────────────────────────────────────────────
  nodes = signal<PathwayNode[]>([]);
  edges = signal<PathwayEdge[]>([]);
  positions = signal<Record<string, Point>>({});
  private activeDrag = signal<{ id: string; dx: number; dy: number } | null>(null);

  /** Positions merged with the in-flight drag delta, for live edge redraw while dragging. */
  livePositions = computed<Record<string, Point>>(() => {
    const base = this.positions();
    const drag = this.activeDrag();
    if (!drag) return base;
    const p = base[drag.id];
    if (!p) return base;
    return { ...base, [drag.id]: { x: p.x + drag.dx, y: p.y + drag.dy } };
  });

  edgePaths = computed(() => {
    const pos = this.livePositions();
    return this.edges().map(e => ({ ...e, d: this.bezierPath(pos[e.from], pos[e.to]) }));
  });

  selectedNodeId = signal<string | null>(null);
  selectedNode = computed(() => this.nodes().find(n => n.id === this.selectedNodeId()) ?? null);
  connectingFrom = signal<string | null>(null);

  // ── Generate / recommend / explain ──────────────────────────────────────
  goalText = '';
  style = 'structured';
  generating = signal(false);
  error = signal<string | null>(null);
  recommendations = signal<Suggestion[]>([]);
  recommendLoading = signal(false);
  explainQuestion = '';
  explainAnswer = signal<string | null>(null);
  explainLoading = signal(false);
  stats = signal<PathwayStats | null>(null);

  // ── Detail panel edit state (debounced auto-save) ───────────────────────
  editLabel = '';
  editDesc = '';
  private editSave$ = new Subject<void>();

  // ── Resizable panel ──────────────────────────────────────────────────────
  panelWidth = signal(DEFAULT_WIDTH);
  private resizeStartX = 0;
  private resizeStartWidth = 0;
  private readonly onResizeMove = (ev: MouseEvent) => {
    const next = this.resizeStartWidth + (this.resizeStartX - ev.clientX);
    this.panelWidth.set(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, next)));
  };
  private readonly onResizeEnd = () => {
    document.removeEventListener('mousemove', this.onResizeMove);
    document.removeEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = '';
    document.body.style.cursor = '';
    try { localStorage.setItem(WIDTH_KEY, String(this.panelWidth())); } catch { /* ponytail: storage may be unavailable (private mode) — width just won't persist */ }
  };

  private readonly onKeydown = (ev: KeyboardEvent) => {
    if (!this.open) return;
    const tag = (document.activeElement as HTMLElement)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    if ((ev.key === 'Delete' || ev.key === 'Backspace') && this.selectedNodeId()) this.deleteSelected();
    if (ev.key === 'Escape') { this.selectedNodeId.set(null); this.connectingFrom.set(null); }
  };

  private hydrated = false;

  constructor(private api: ApiService) {
    try {
      const savedWidth = Number(localStorage.getItem(WIDTH_KEY));
      if (savedWidth) this.panelWidth.set(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, savedWidth)));
    } catch { /* ignore */ }

    // Persist the graph to localStorage on every change, once initial hydration has happened.
    effect(() => {
      const snapshot = { nodes: this.nodes(), edges: this.edges(), positions: this.positions() };
      if (!this.hydrated) return;
      try { localStorage.setItem(this.storageKey(), JSON.stringify(snapshot)); } catch { /* ponytail: best-effort persistence only */ }
    });

    this.editSave$.pipe(debounceTime(600)).subscribe(() => this.commitEdit());
  }

  ngOnInit(): void {
    this.loadFromStorage();
    window.addEventListener('keydown', this.onKeydown);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['conversationId'] && !changes['conversationId'].firstChange) {
      this.selectedNodeId.set(null);
      this.loadFromStorage();
    }
    if (changes['open'] && this.open) {
      this.loadStats();
    }
  }

  ngOnDestroy(): void {
    window.removeEventListener('keydown', this.onKeydown);
    document.removeEventListener('mousemove', this.onResizeMove);
    document.removeEventListener('mouseup', this.onResizeEnd);
    if (this.ttsAvailable) window.speechSynthesis.cancel();
  }

  close(): void {
    this.closed.emit();
  }

  // ── localStorage ─────────────────────────────────────────────────────────

  private storageKey(): string {
    return `yz_pathway_whiteboard_${this.conversationId ?? 'default'}`;
  }

  private loadFromStorage(): void {
    this.hydrated = false;
    try {
      const raw = localStorage.getItem(this.storageKey());
      const parsed = raw ? JSON.parse(raw) : null;
      this.nodes.set(parsed?.nodes ?? []);
      this.edges.set(parsed?.edges ?? []);
      this.positions.set(parsed?.positions ?? {});
    } catch {
      this.nodes.set([]); this.edges.set([]); this.positions.set({});
    }
    this.hydrated = true;
  }

  // ── Node/edge editing ────────────────────────────────────────────────────

  trackNode(node: PathwayNode): string { return node.id; }
  trackEdge(edge: PathwayEdge): string { return edge.from + '::' + edge.to; }
  trackSuggestion(s: Suggestion, index: number): string { return s.label + '::' + index; }

  typeMetaOf(type: string): TypeMeta {
    return this.typeMeta.find(t => t.type === type) ?? this.typeMeta.find(t => t.type === 'step')!;
  }

  private nextPosition(): Point {
    const count = this.nodes().length;
    return { x: 40 + (count % 4) * 48, y: 40 + Math.floor(count / 4) * 140 + (count % 4) * 20 };
  }

  addNode(nodeType: string, label?: string, subtitle = ''): void {
    const id = `n-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const meta = this.typeMetaOf(nodeType);
    const node: PathwayNode = { id, label: label || meta.label, subtitle, type: nodeType, status: 'upcoming' };
    this.nodes.update(list => [...list, node]);
    this.positions.update(p => ({ ...p, [id]: this.nextPosition() }));
    this.selectedNodeId.set(id);
  }

  /** Palette icon dropped onto the canvas via CDK drag — places the new node at the drop point. */
  onPaletteDropped(nodeType: string, event: CdkDragEnd, canvasEl: HTMLElement): void {
    const rect = canvasEl.getBoundingClientRect();
    const point = event.dropPoint;
    const withinCanvas = point.x >= rect.left && point.x <= rect.right && point.y >= rect.top && point.y <= rect.bottom;
    event.source.reset();
    if (!withinCanvas) return;
    const id = `n-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const meta = this.typeMetaOf(nodeType);
    const node: PathwayNode = { id, label: meta.label, type: nodeType, status: 'upcoming' };
    this.nodes.update(list => [...list, node]);
    this.positions.update(p => ({
      ...p,
      [id]: { x: Math.max(0, point.x - rect.left - NODE_W / 2), y: Math.max(0, point.y - rect.top - NODE_H / 2) },
    }));
    this.selectedNodeId.set(id);
  }

  onNodeDragMoved(id: string, event: CdkDragMove): void {
    this.activeDrag.set({ id, dx: event.distance.x, dy: event.distance.y });
  }

  onNodeDragEnded(id: string, event: CdkDragEnd): void {
    const p = this.positions()[id] ?? { x: 0, y: 0 };
    const next = { x: p.x + event.distance.x, y: p.y + event.distance.y };
    this.positions.update(m => ({ ...m, [id]: next }));
    this.activeDrag.set(null);
    event.source.reset();
  }

  selectNode(id: string, ev: Event): void {
    ev.stopPropagation();
    this.selectedNodeId.set(this.selectedNodeId() === id ? null : id);
  }

  onCanvasClick(): void {
    this.selectedNodeId.set(null);
    this.connectingFrom.set(null);
  }

  cycleStatus(node: PathwayNode, ev: Event): void {
    ev.stopPropagation();
    const next = STATUS_CYCLE[node.status] ?? 'upcoming';
    this.nodes.update(list => list.map(n => n.id === node.id ? { ...n, status: next } : n));
  }

  deleteSelected(): void {
    const id = this.selectedNodeId();
    if (!id) return;
    this.nodes.update(list => list.filter(n => n.id !== id));
    this.edges.update(list => list.filter(e => e.from !== id && e.to !== id));
    this.positions.update(p => { const { [id]: _drop, ...rest } = p; return rest; });
    this.selectedNodeId.set(null);
  }

  // ── Connector sockets (click output, then click input to connect) ──────
  // Simplified vs. the old app's raw mousedown/mousemove drag-to-connect with a live SVG
  // preview line — see class doc comment.

  onSocketOutClick(id: string, ev: Event): void {
    ev.stopPropagation();
    this.connectingFrom.set(this.connectingFrom() === id ? null : id);
  }

  onSocketInClick(id: string, ev: Event): void {
    ev.stopPropagation();
    const from = this.connectingFrom();
    if (!from || from === id) { this.connectingFrom.set(null); return; }
    const exists = this.edges().some(e => e.from === from && e.to === id);
    if (!exists) this.edges.update(list => [...list, { from, to: id }]);
    this.connectingFrom.set(null);
  }

  removeEdge(edge: PathwayEdge, ev: Event): void {
    ev.stopPropagation();
    this.edges.update(list => list.filter(e => !(e.from === edge.from && e.to === edge.to)));
  }

  private bezierPath(a?: Point, b?: Point): string {
    if (!a || !b) return '';
    const x1 = a.x + NODE_W / 2, y1 = a.y + NODE_H;
    const x2 = b.x + NODE_W / 2, y2 = b.y;
    const midY = (y1 + y2) / 2;
    return `M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`;
  }

  // ── Detail panel (debounced auto-save) ──────────────────────────────────

  onSelectNodeForEdit(): void {
    const n = this.selectedNode();
    this.editLabel = n?.label ?? '';
    this.editDesc = n?.description ?? '';
    this.explainQuestion = '';
    this.explainAnswer.set(null);
  }

  onEditChange(): void {
    this.editSave$.next();
  }

  private commitEdit(): void {
    const id = this.selectedNodeId();
    if (!id) return;
    this.nodes.update(list => list.map(n => n.id !== id ? n : {
      ...n,
      label: this.editLabel.trim() || n.label,
      description: this.editDesc,
    }));
  }

  // ── Generate — POST /api/pathway/generate ───────────────────────────────
  // Request:  { goal: string, style?: string, modelId?: string }
  // Response: { nodes: [{id,label,subtitle?,description?,prerequisites?,type}], edges: [{from,to}] }
  // Errors:   400 { error: string } (e.g. blank goal, or Gemini returned no usable nodes).

  generate(): void {
    const goal = this.goalText.trim();
    if (!goal || this.generating()) return;
    this.generating.set(true);
    this.error.set(null);
    this.api.post<{ nodes: PathwayNode[]; edges: PathwayEdge[] }>('/pathway/generate', {
      goal, style: this.style,
    }).subscribe({
      next: (res) => {
        const nodes = (res?.nodes ?? []).map(n => ({ ...n, status: 'upcoming' }));
        if (!nodes.length) {
          this.error.set('No pathway generated. Describe your goal more specifically.');
          this.generating.set(false);
          return;
        }
        const pos: Record<string, Point> = {};
        nodes.forEach((n, i) => { pos[n.id] = { x: 60, y: 32 + i * 140 }; });
        this.nodes.set(nodes);
        this.edges.set(res?.edges ?? []);
        this.positions.set(pos);
        this.selectedNodeId.set(null);
        this.generating.set(false);
        this.loadStats();
      },
      error: (err) => {
        this.generating.set(false);
        this.error.set(err?.error?.error || 'Failed to generate a pathway. Please try again.');
      },
    });
  }

  // ── Recommend — POST /api/pathway/recommend ─────────────────────────────
  // Request:  { nodes: [{id,label,type}], context?: string }
  // Response: { suggestions: [{type,label,subtitle,reason}] } — always 200, [] on failure.

  recommend(): void {
    if (this.recommendLoading()) return;
    this.recommendLoading.set(true);
    this.error.set(null);
    const payload = {
      nodes: this.nodes().map(n => ({ id: n.id, label: n.label, type: n.type })),
      context: this.goalText,
    };
    this.api.post<{ suggestions: Suggestion[] }>('/pathway/recommend', payload).subscribe({
      next: (res) => {
        const suggestions = res?.suggestions ?? [];
        this.recommendations.set(suggestions);
        if (!suggestions.length) this.error.set('No suggestions right now — try adding a goal first.');
        this.recommendLoading.set(false);
      },
      error: () => { this.recommendLoading.set(false); this.error.set('Could not fetch recommendations.'); },
    });
  }

  addRecommendation(s: Suggestion): void {
    this.addNode(s.type || 'step', s.label, s.subtitle ?? '');
    this.recommendations.update(list => list.filter(x => x !== s));
  }

  dismissRecommendation(s: Suggestion): void {
    this.recommendations.update(list => list.filter(x => x !== s));
  }

  // ── Explain — POST /api/pathway/explain ─────────────────────────────────
  // Request:  { node: { label, subtitle?, goalContext? }, question: string }
  // Response: { answer: string } — always 200, fail-soft apology string on error.

  askExplain(): void {
    const node = this.selectedNode();
    const question = this.explainQuestion.trim();
    if (!node || !question || this.explainLoading()) return;
    this.explainLoading.set(true);
    this.explainAnswer.set(null);
    this.api.post<{ answer: string }>('/pathway/explain', {
      node: { label: node.label, subtitle: node.subtitle, goalContext: this.goalText },
      question,
    }).subscribe({
      next: (res) => { this.explainAnswer.set(res?.answer ?? 'No answer available.'); this.explainLoading.set(false); },
      error: () => { this.explainAnswer.set('Could not get an explanation.'); this.explainLoading.set(false); },
    });
  }

  // ── Stats — GET /api/pathway/stats ──────────────────────────────────────
  // Response: { calls: number, inputTokens: number, outputTokens: number }

  private loadStats(): void {
    this.api.get<PathwayStats>('/pathway/stats').subscribe({
      next: (s) => this.stats.set(s),
      error: () => { /* stats are a nice-to-have, ignore failures */ },
    });
  }

  // ── Text-to-speech ───────────────────────────────────────────────────────

  readNode(node: PathwayNode, ev: Event): void {
    ev.stopPropagation();
    if (!this.ttsAvailable) return;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(`${node.label}. ${node.description ?? ''}`));
  }

  readAll(): void {
    if (!this.ttsAvailable || !this.nodes().length) return;
    const text = this.nodes().map(n => `${n.label}. ${n.description ?? ''}`).join(' Next, ');
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(text));
  }

  // ── Resizable panel ──────────────────────────────────────────────────────

  onResizeStart(ev: MouseEvent): void {
    ev.preventDefault();
    this.resizeStartX = ev.clientX;
    this.resizeStartWidth = this.panelWidth();
    document.addEventListener('mousemove', this.onResizeMove);
    document.addEventListener('mouseup', this.onResizeEnd);
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
  }

  clearGraph(): void {
    this.nodes.set([]);
    this.edges.set([]);
    this.positions.set({});
    this.selectedNodeId.set(null);
    this.error.set(null);
    this.recommendations.set([]);
  }
}
