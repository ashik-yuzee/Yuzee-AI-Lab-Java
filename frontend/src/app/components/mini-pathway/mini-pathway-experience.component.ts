import {
  Component, Directive, ElementRef, EventEmitter, HostListener, Input, OnChanges, OnDestroy, Output,
  Injector, SimpleChanges, ViewChild, afterNextRender, computed, effect, inject, signal, untracked
} from '@angular/core';
import { AuthService } from '../../services/auth.service';
import { RoutingService } from '../../services/routing.service';
import { TokenLabService } from '../../services/token-lab.service';
import { YuzeeContentBlock } from '../../models/types';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { IconComponent } from '../shared/icon/icon.component';
import { PathwayHistorySelectorComponent } from './pathway-history-selector.component';
import { PathwayColourGuideComponent } from './pathway-learning-cues.component';
import { MiniPathwayStreamingComponent } from './mini-pathway-streaming.component';
import { DrawerResizeController } from './drawer-resize';
import { generateMiniPathway, listMiniPathways } from './client';
import { MiniPathwayRun, completedPathways, miniPathwayPanelResponse, selectSavedPathway } from './history';
import {
  MINI_PATHWAY_VERSION, PathwayDecision, PathwayHint, alreadyHelpedInLowEpisode,
  decideMiniPathway, messageResponse, pathwayBoundary, pathwayQuery, validPathwayHint,
} from './policy';
import { acceptedResponse } from '../../utils/response-presentation';

/** createPortal equivalent: moves the host element into `target` (a DOM node owned by another component). */
@Directive({ selector: '[mpPortal]', standalone: true })
export class MiniPathwayPortalDirective implements OnChanges, OnDestroy {
  @Input('mpPortal') target: HTMLElement | null = null;
  constructor(private el: ElementRef<HTMLElement>) {}
  ngOnChanges(): void { if (this.target && this.el.nativeElement.parentElement !== this.target) this.target.appendChild(this.el.nativeElement); }
  ngOnDestroy(): void { this.el.nativeElement.remove(); }
}

const selectionKey = (conversationId: string) => `yuzee-pathway-selection:${conversationId}`;

/**
 * 1:1 port of MiniPathwayExperience.tsx (+ usePathwayHistory, useDrawerResize). Always mounted:
 * it decides after each completed answer whether to offer (or automatically prepare) a mini
 * pathway, portals the offer card into #mini-pathway-offer (chat) and the saved-pathways control
 * into #saved-pathways-control (navbar), and owns the resizable/expandable side panel.
 */
@Component({
  selector: 'app-mini-pathway-experience',
  standalone: true,
  imports: [
    ProtocolRendererComponent, IconComponent, PathwayHistorySelectorComponent, PathwayColourGuideComponent,
    MiniPathwayStreamingComponent, MiniPathwayPortalDirective,
  ],
  templateUrl: './mini-pathway-experience.component.html',
  styleUrl: './mini-pathway-experience.component.scss'
})
export class MiniPathwayExperienceComponent implements OnChanges, OnDestroy {
  @Input() conversationId: string | null = null;
  /** Host request to show the panel (e.g. a tools menu); the panel otherwise opens itself. */
  /** Emitted when the panel reveals itself; the original also closed the sidebar, whiteboard and token inspector here. */
  @Output() opened = new EventEmitter<void>();

  @ViewChild('opener') opener?: ElementRef<HTMLButtonElement>;

  readonly version = MINI_PATHWAY_VERSION;
  readonly drawer = new DrawerResizeController();

  private convIdSig = signal<string | null>(null);
  conv = computed(() => {
    const id = this.convIdSig();
    return (id ? this.lab.conversations().find(c => c.id === id) : null) ?? this.lab.activeConversation();
  });
  /** Primitive-valued so effects re-run only when the id changes, like the original's [conv?.id] deps. */
  convId = computed(() => this.conv()?.id);
  last = computed(() => this.conv()?.messages?.at(-1) ?? null);
  sourceId = computed(() => this.last()?.serverMessageId || this.last()?.id || '');
  isStreaming = computed(() => this.lab.isStreaming());
  private key = '';

  target = signal<HTMLElement | null>(null);
  historyTarget = signal<HTMLElement | null>(null);
  decision = signal<PathwayDecision | null>(null);
  hint = signal<PathwayHint | null>(null);
  panelOpen = signal(false);
  expanded = signal(false);
  loading = signal(false);
  stage = signal('Preparing your mini pathway');
  error = signal('');
  draftBlocks = signal<YuzeeContentBlock[]>([]);
  private active: AbortController | null = null;

  // usePathwayHistory
  private historyState = signal<{ owner: string; runs: MiniPathwayRun[]; selectedId: string | null }>({ owner: '', runs: [], selectedId: null });
  historyError = signal('');
  private historyRetry = signal(0);
  runs = computed(() => { const s = this.historyState(); return s.owner === this.conv()?.id ? s.runs : []; });
  run = computed(() => selectSavedPathway(this.runs(), this.historyState().selectedId));
  private runId = computed(() => this.run()?.id);

  stale = computed(() => !!this.run() && this.run()!.sourceMessageId !== this.sourceId());
  available = computed(() => !!this.decision() && this.decision()!.action !== 'none');
  currentSaved = computed(() => [...this.runs()].reverse().find(r => r.sourceMessageId === this.sourceId()));
  source = computed(() => { const l = this.last(); return acceptedResponse(l?.structuredResponse || l?.content || ''); });
  runResponse = computed(() => { const r = this.run()?.response; return r ? miniPathwayPanelResponse(r) : null; });

  private readonly injector = inject(Injector);

  constructor(private lab: TokenLabService, private routing: RoutingService, private auth: AuthService) {
    // The original looks its portal targets up in effects, i.e. after the commit that rendered the chat and
    // navbar mount points; afterNextRender is that point here.
    const lookUp = (id: string, into: typeof this.target) => afterNextRender(() => into.set(document.getElementById(id)), { injector: this.injector });

    // Conversation change: close the panel and reset.
    effect(onCleanup => {
      void this.convId();
      untracked(() => { this.panelOpen.set(false); this.expanded.set(false); this.error.set(''); lookUp('saved-pathways-control', this.historyTarget); this.drawer.cancel(); });
      onCleanup(() => this.active?.abort());
    }, { allowSignalWrites: true });

    // usePathwayHistory: load saved runs for the conversation.
    effect(onCleanup => {
      const conversationId = this.convId(); void this.historyRetry();
      const controller = new AbortController();
      onCleanup(() => controller.abort());
      untracked(() => {
        this.historyError.set('');
        if (!conversationId) return;
        this.historyState.set({ owner: conversationId, runs: [], selectedId: null });
        listMiniPathways(conversationId, this.auth.token, controller.signal).then(saved => {
          if (controller.signal.aborted || this.conv()?.id !== conversationId) return;
          const runs = completedPathways(saved, conversationId);
          let preferred: string | null = null; try { preferred = localStorage.getItem(selectionKey(conversationId)); } catch {}
          const selected = selectSavedPathway(runs, preferred);
          // A newly completed run or explicit selection wins over this initial read.
          this.historyState.update(previous => previous.owner === conversationId && previous.runs.length
            ? { ...previous, runs: completedPathways([...runs, ...previous.runs], conversationId) }
            : { owner: conversationId, runs, selectedId: selected?.id || null });
          if (runs.length) this.reveal();
        }).catch(() => { if (!controller.signal.aborted && this.conv()?.id === conversationId) this.historyError.set('Saved pathways could not be loaded.'); });
      });
    }, { allowSignalWrites: true });

    // Scroll the report to the top when the selected pathway changes.
    effect(() => { void this.runId(); queueMicrotask(() => document.getElementById('mini-pathway-body')?.scrollTo({ top: 0 })); });

    // Decide whether to offer (or automatically prepare) a pathway for the latest answer.
    effect(onCleanup => {
      void this.convId(); const sourceId = this.sourceId(), isStreaming = this.isStreaming();
      const conv = untracked(() => this.conv());
      const controller = new AbortController(); let alive = true;
      onCleanup(() => { alive = false; controller.abort(); });
      untracked(() => {
        this.key = `${conv?.id}:${sourceId}`;
        lookUp('mini-pathway-offer', this.target);
        this.decision.set(null); this.hint.set(null); this.error.set('');
        this.active?.abort(); this.loading.set(false); this.draftBlocks.set([]);
        const last = this.last();
        const response = last?.role === 'assistant' && !last.error && !last.streamStopped && last.schemaValid !== false && last.semanticValid !== false ? messageResponse(last) : null;
        const userText = [...(conv?.messages || [])].reverse().find(m => m.role === 'user')?.content || '';
        if (!conv || isStreaming || last?.isStreaming || !response || pathwayBoundary(response, userText)) return;
        const requestKey = this.key;
        (async () => {
          // Read previous attempts before deciding: failure or refresh must not cause an automatic loop.
          const saved = await listMiniPathways(conv.id, this.auth.token, controller.signal);
          if (!alive) return;
          const current = [...saved].reverse().find(r => r.sourceMessageId === sourceId);
          if (current?.status === 'complete') { if (validPathwayHint(current.hint)) this.hint.set(current.hint); this.decision.set({ action: 'offer', reason: 'saved', score: null }); return; }
          if (current?.status === 'error' && validPathwayHint(current.hint)) {
            // A retry for the exact same source already has a saved relevance decision.
            this.hint.set(current.hint); this.decision.set(decideMiniPathway(response, userText, current.hint, true));
            this.error.set(current.error || 'The previous pathway stopped. You can try again.'); return;
          }
          if (!await this.waitForRouter(controller.signal) || !alive) return;
          let selection = await this.reviewMiniPathway(pathwayQuery(response, userText), controller.signal);
          if (selection.reason === 'busy' && alive) { await new Promise(r => setTimeout(r, 400)); selection = await this.reviewMiniPathway(pathwayQuery(response, userText), controller.signal); }
          if (!alive || this.key !== requestKey) return;
          const messages = conv.messages.map(m => ({ ...m, id: m.serverMessageId || m.id }));
          const plan = decideMiniPathway(response, userText, selection, alreadyHelpedInLowEpisode(messages, saved));
          this.hint.set(selection); this.decision.set(plan);
          if (current?.status === 'error') this.error.set(current.error || 'The previous pathway stopped. You can try again.');
          if (plan.action === 'automatic') await this.generate('automatic', selection, controller.signal);
        })().catch(e => { if (alive && !controller.signal.aborted) this.error.set(e instanceof Error ? e.message : 'Mini pathway is unavailable.'); });
      });
    }, { allowSignalWrites: true });

    // useDrawerResize: any drag is reset when open/expanded change.
    effect(() => { void this.panelOpen(); void this.expanded(); untracked(() => this.drawer.cancel()); }, { allowSignalWrites: true });

    // While open: keep the sidebar closed on narrow screens.
    effect(onCleanup => {
      if (!this.panelOpen()) return;
      const resize = () => { if (window.innerWidth < 1200) this.lab.isSidebarOpen.set(false); };
      untracked(resize);
      window.addEventListener('resize', resize);
      onCleanup(() => window.removeEventListener('resize', resize));
    }, { allowSignalWrites: true });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['conversationId']) this.convIdSig.set(this.conversationId);
  }

  ngOnDestroy(): void {
    this.active?.abort();
    this.drawer.destroy();
  }

  @HostListener('window:keydown', ['$event'])
  onKeydown(e: KeyboardEvent): void {
    if (!this.panelOpen() || e.key !== 'Escape') return;
    if (this.expanded()) this.expanded.set(false); else this.close();
  }

  /** MiniPathwayExperience.tsx waitForRouter(): onRouterStatus + startWarmup, 125s timeout. */
  private waitForRouter(signal: AbortSignal): Promise<boolean> {
    return new Promise(resolve => {
      let unsubscribe = () => {};
      const done = (ready: boolean) => { clearTimeout(timer); unsubscribe(); signal.removeEventListener('abort', cancel); resolve(ready); };
      const cancel = () => done(false), timer = setTimeout(cancel, 125000);
      signal.addEventListener('abort', cancel, { once: true });
      unsubscribe = this.routing.onRouterStatus(status => { if (status === 'ready') queueMicrotask(() => done(true)); if (status === 'unavailable') queueMicrotask(() => done(false)); });
      if (signal.aborted) cancel(); else this.routing.startWarmup();
    });
  }

  /** MicroToolRouter.reviewMiniPathway (gated by choosePathwayHint with the selected router model). */
  private reviewMiniPathway(text: string, signal: AbortSignal): Promise<PathwayHint> {
    return this.routing.reviewMiniPathway(text, { signal });
  }

  /** setSidebarOpen(false); setWhiteboardOpen(false); setTokenInspectorOpen(false); setOpen(true). */
  reveal(): void {
    this.opened.emit();
    this.panelOpen.set(true);
  }

  close(): void {
    this.active?.abort(); this.draftBlocks.set([]); this.loading.set(false);
    this.panelOpen.set(false); this.expanded.set(false);
    this.opener?.nativeElement.focus();
  }

  async generate(mode: 'automatic' | 'manual', selection: PathwayHint, parentSignal?: AbortSignal): Promise<void> {
    const conv = this.conv();
    if (!conv) return;
    const requestKey = this.key, controller = new AbortController(); this.active?.abort(); this.active = controller;
    const abort = () => controller.abort(); parentSignal?.addEventListener('abort', abort, { once: true });
    if (parentSignal?.aborted) controller.abort();
    this.error.set(''); this.loading.set(true); this.draftBlocks.set([]); this.stage.set('Preparing your mini pathway'); this.reveal();
    const sourceId = this.sourceId();
    try {
      const current = () => !controller.signal.aborted && this.key === requestKey;
      const result = await generateMiniPathway(conv.id, this.auth.token,
        { sourceMessageId: sourceId, mode, hint: selection, location: this.lab.userLocation() ?? '' }, controller.signal,
        stage => { if (current()) this.stage.set(stage); },
        event => { if (current()) this.draftBlocks.update(previous => event.type === 'reset' ? [] : previous.some(b => b.id === event.block.id) ? previous : [...previous, event.block]); });
      if (!controller.signal.aborted && this.key === requestKey) { this.remember(result); this.decision.update(d => d ? { ...d, action: 'offer' } : d); }
    } catch (e) {
      if (!controller.signal.aborted && this.key === requestKey) this.error.set(e instanceof Error ? e.message : 'The mini pathway could not be prepared.');
    } finally {
      parentSignal?.removeEventListener('abort', abort);
      if (this.active === controller) { this.active = null; this.draftBlocks.set([]); this.loading.set(false); }
    }
  }

  // usePathwayHistory select/remember/retry
  private persistSelection(id: string): void {
    const cid = this.conv()?.id;
    if (cid) try { localStorage.setItem(selectionKey(cid), id); } catch { /* Selection still works for this visit. */ }
  }
  private remember(run: MiniPathwayRun): void {
    const cid = this.conv()?.id;
    if (!cid || run.conversationId !== cid) return;
    this.persistSelection(run.id);
    this.historyState.update(previous => ({ owner: cid, runs: completedPathways([...(previous.owner === cid ? previous.runs : []), run], cid), selectedId: run.id }));
  }
  chooseSaved(id: string): void {
    if (this.runs().some(r => r.id === id)) { this.persistSelection(id); this.historyState.update(p => ({ ...p, selectedId: id })); }
    this.error.set('');
  }
  retryHistory(): void { this.historyRetry.update(n => n + 1); }

  onOfferClick(): void {
    const saved = this.currentSaved(), hint = this.hint();
    if (saved) { this.chooseSaved(saved.id); this.reveal(); }
    else if (hint) void this.generate('manual', hint);
  }

  onSavedControlClick(): void { this.error.set(''); this.reveal(); }

  stopStreaming(): void {
    this.active?.abort(); this.draftBlocks.set([]); this.loading.set(false);
    this.error.set('Stopped. You can try again when you are ready.');
  }

  retry(): void { const h = this.hint(); if (h) void this.generate('manual', h); }
}
