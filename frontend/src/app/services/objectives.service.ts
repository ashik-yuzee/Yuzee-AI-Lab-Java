import { Injectable, computed, effect, signal, untracked } from '@angular/core';
import { TokenLabService } from './token-lab.service';
import {
  ObjectiveAnswer, ObjectiveCatalogueItem, ObjectiveSession,
  answerReceipt, setVisibleWorkspace, workspaceResultMessage,
} from '../components/objectives/objectives.types';
import type { ExplorationChoice } from '../components/warehouse/warehouse.types';
import catalogueJson from '../components/objectives/catalogue.json';

const draftKey = (conv: string, id: string) => `yuzee-workspace-draft:${conv}:${id}`;
function restoreDrafts(conv: string, items: ObjectiveSession[]): ObjectiveSession[] {
  return items.map(s => {
    const key = draftKey(conv, s.id);
    try {
      const draft = JSON.parse(sessionStorage.getItem(key) || 'null');
      if (!draft) return s;
      if (s.pendingAnswer || s.revision > draft.revision || s.state !== 'ACTIVE') { sessionStorage.removeItem(key); return s; }
      return { ...s, pendingAnswer: { ...draft.receipt, localOnly: true } };
    } catch { return s; }
  });
}

/**
 * Port of the original's `objectives/ObjectiveContext.tsx` (`useObjectives()`), as signals.
 *
 * Wire format is the original's (server.ts `/objectives/:operation`), with `revision` checks.
 */
@Injectable({ providedIn: 'root' })
export class ObjectivesService {
  enabled = signal(this.readEnabled());
  open = signal(false);
  sessions = signal<ObjectiveSession[]>([]);
  selectedId = signal('');
  selected = computed(() => this.sessions().find(s => s.id === this.selectedId()));
  busy = signal(false);
  error = signal('');
  catalogueOpen = signal(false);
  transferring = signal('');
  transferErrors = signal<Record<string, string>>({});
  // routing.ts objectiveCatalogue: bundled with the client, as in the original (no request).
  catalogue = signal<ObjectiveCatalogueItem[]>(catalogueJson.objectives as ObjectiveCatalogueItem[]);
  conversationId = computed(() => this.lab.activeConversationId() ?? undefined);

  private active: AbortController | null = null;
  private transferLock = false;
  private conversation: string | undefined;

  constructor(private lab: TokenLabService) {
    // useEffect([conv?.id, enabled]): reset and reload saved activities for the conversation.
    effect(onCleanup => {
      const conv = this.conversationId(), enabled = this.enabled();
      this.conversation = conv;
      untracked(() => {
        this.sessions.set([]); this.selectedId.set(''); this.open.set(false); this.error.set(''); this.busy.set(false);
        this.transferErrors.set({}); this.active?.abort(); this.active = null;
      });
      if (!conv || !enabled) return;
      const abort = new AbortController();
      this.api(conv, '', undefined, abort.signal).then(data => {
        if (!abort.signal.aborted) { this.sessions.set(restoreDrafts(conv, data)); this.selectedId.set(data[0]?.id || ''); }
      }).catch(e => { if (!abort.signal.aborted) this.error.set(e.message); });
      onCleanup(() => { abort.abort(); this.active?.abort(); });
    }, { allowSignalWrites: true });
    // useEffect([enabled, open, selected?.id, conv?.id]): the visible workspace is sent with chat messages.
    effect(onCleanup => {
      const conv = this.conversationId(), id = this.selected()?.id;
      setVisibleWorkspace(this.enabled() && this.open() && id && conv ? { conversationId: conv, sessionId: id } : null);
      onCleanup(() => setVisibleWorkspace(null));
    });
    // useEffect([isWhiteboardOpen, isTokenInspectorOpen]): another side panel closes the workspace.
    effect(() => { if (this.lab.isWhiteboardOpen() || this.lab.isTokenInspectorOpen()) untracked(() => this.open.set(false)); }, { allowSignalWrites: true });
  }

  private readEnabled(): boolean {
    return localStorage.getItem('yuzee-objective-preview') === 'true';
  }

  /** ObjectiveContext.tsx objectiveApi(). */
  async api(conversationId: string, operation: string, body?: any, signal?: AbortSignal): Promise<any> {
    const response = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}/objectives${operation ? '/' + operation : ''}`, {
      method: body ? 'POST' : 'GET',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('yuzee_auth') || ''}` },
      body: body ? JSON.stringify(body) : undefined, signal,
    });
    const data = await response.json();
    if (!response.ok) throw Error(data.error || 'The workspace could not be loaded.');
    return data;
  }

  private put(session: ObjectiveSession): void {
    this.sessions.update(prev => [session, ...prev.filter(s => s.id !== session.id)]);
  }

  setEnabled(value: boolean): void {
    localStorage.setItem('yuzee-objective-preview', String(value));
    this.enabled.set(value);
    if (!value) { this.active?.abort(); this.busy.set(false); this.open.set(false); }
  }

  /** ObjectiveProvider unmounts with the lab (Renderer page): the state the conversation effect does not reset starts over. */
  resetForUnmount(): void {
    this.active?.abort();
    this.active = null;
    this.transferLock = false;
    this.enabled.set(this.readEnabled());
    this.catalogueOpen.set(false);
    this.transferring.set('');
  }

  setOpen(value: boolean): void { this.open.set(value); }
  setSelectedId(id: string): void { this.selectedId.set(id); }
  setCatalogueOpen(value: boolean): void { this.catalogueOpen.set(value); }
  setError(value: string): void { this.error.set(value); }

  reveal(): void {
    this.lab.isSidebarOpen.set(false); this.lab.isWhiteboardOpen.set(false); this.lab.isTokenInspectorOpen.set(false);
    this.open.set(true);
  }

  showSession(id: string): void { this.selectedId.set(id); this.catalogueOpen.set(false); this.reveal(); }
  browse(): void { this.catalogueOpen.set(true); this.reveal(); }

  private async run(operation: string, body: any): Promise<void> {
    const id = this.conversation;
    if (!id || this.active) return;
    const controller = new AbortController(); this.active = controller;
    this.busy.set(true); this.error.set(''); this.reveal();
    const selected = this.selected();
    if (operation === 'answer' && body.answer && selected) {
      const receipt = answerReceipt(selected.plan, body.answer);
      sessionStorage.setItem(draftKey(id, selected.id), JSON.stringify({ revision: selected.revision, receipt }));
      this.put({ ...selected, pendingAnswer: { ...receipt, localOnly: true } });
    }
    try {
      const session = await this.api(id, operation, body, controller.signal);
      if (!controller.signal.aborted && this.conversation === id) { this.put(session); this.selectedId.set(session.id); this.catalogueOpen.set(false); }
    } catch (e) {
      if (!controller.signal.aborted && this.conversation === id) this.error.set(e instanceof Error ? e.message : 'Please try again.');
    } finally {
      if (this.active === controller) { this.active = null; this.busy.set(false); }
      if (this.conversation === id) { try { const saved = await this.api(id, ''); if (this.conversation === id) this.sessions.set(restoreDrafts(id, saved)); } catch { /* keep current list */ } }
    }
  }

  start(objectiveId: string, goal?: string, selection?: any): Promise<void> {
    return this.run('start', { objectiveId, goal, ...(selection ? { activation: 'automatic', selectionId: selection.selectionId, sourceMessageId: selection.sourceMessageId } : {}) });
  }

  answer(value: ObjectiveAnswer): Promise<void> | void {
    const s = this.selected();
    return s && this.run('answer', { sessionId: s.id, revision: s.revision, answer: value });
  }

  retry(): Promise<void> | void {
    const s = this.selected();
    return s && this.run('answer', { sessionId: s.id, revision: s.revision, ...(s.pendingAnswer?.localOnly ? { answer: s.pendingAnswer.input } : { retry: true }) });
  }

  correct(text: string): Promise<void> | void {
    const s = this.selected();
    return s && this.run('correct', { sessionId: s.id, revision: s.revision, correction: text });
  }

  saveExploration(explorationChoices: ExplorationChoice): Promise<void> | void {
    const s = this.selected();
    return s && this.run('correct', { sessionId: s.id, revision: s.revision, explorationChoices });
  }

  focusCourses(courseIds: string[]): Promise<void> | void {
    const s = this.selected();
    return s && this.run('course-focus', { sessionId: s.id, revision: s.revision, courseIds });
  }

  retryCorrection(): Promise<void> | void {
    const s = this.selected();
    return s && this.run('correct', { sessionId: s.id, revision: s.revision, retry: true });
  }

  cancel(): Promise<void> | void {
    const s = this.selected();
    return s && this.run('answer', { sessionId: s.id, revision: s.revision, cancel: true });
  }

  stop(): void {
    this.active?.abort(); this.active = null; this.busy.set(false);
    this.error.set('Stopped. Submitted answers stay saved; you can retry when ready.');
  }

  /** ObjectiveContext.tsx useResult(). Not rendered by the original UI; kept for parity. */
  async useResult(session: ObjectiveSession | undefined = this.selected()): Promise<void> {
    if (!session || session.handoffAt || this.lab.isStreaming() || this.busy() || this.transferLock || this.conversation !== session.conversationId) return;
    const id = session.conversationId; this.transferLock = true;
    this.transferring.set(session.id); this.transferErrors.update(prev => ({ ...prev, [session.id]: '' }));
    try {
      const accepted = await this.lab.sendMessage({ message: workspaceResultMessage(session), userQuestionAnswers: [], objectiveResultId: session.id, objectiveResultRevision: session.revision, objectiveTransfer: { sessionId: session.id, label: session.label, revision: session.revision } });
      if (this.conversation === id) {
        if (accepted) { const saved = await this.api(id, ''); if (this.conversation === id) this.sessions.set(restoreDrafts(id, saved)); }
        else this.transferErrors.update(prev => ({ ...prev, [session.id]: 'Oala could not finish the reply. Your result is saved.' }));
      }
    } catch {
      if (this.conversation === id) this.transferErrors.update(prev => ({ ...prev, [session.id]: 'Your result is saved. Retry sending it to Oala.' }));
    } finally { this.transferLock = false; this.transferring.set(''); }
  }
}
