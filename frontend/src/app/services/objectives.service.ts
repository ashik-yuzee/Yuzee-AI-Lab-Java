import { Injectable, computed, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { AuthService } from './auth.service';
import {
  ObjectiveAnswer,
  ObjectiveCatalogueItem,
  ObjectiveHandoffResult,
  ObjectiveSession,
  ObjectivesCatalogueResponse,
} from '../components/objectives/objectives.types';

/**
 * Angular port of the old React app's `src/objectives/ObjectiveContext.tsx` (the `useObjectives()`
 * state/API hook), rewritten with plain signals instead of React state/context — mirrors this
 * app's `TokenLabService` in style (HttpClient + manual `Authorization` header via `AuthService`,
 * `signal`/`computed` for state).
 *
 * Backend (already implemented, see `ChatController.java`):
 *   GET  /api/conversations/:id/objectives                -> ObjectiveSession[]
 *   POST /api/conversations/:id/objectives/start           body {toolId}
 *   POST /api/conversations/:id/objectives/answer          body {sessionId, answer:{component_id,value,unsure?} | {retry:true} | {cancel:true}}
 *   POST /api/conversations/:id/objectives/correct         body {sessionId, correction:{text} | {retry:true}}
 *   POST /api/conversations/:id/objectives/dismiss         body {sessionId} -> {ok:true} (cancels the session)
 *   POST /api/conversations/:id/objectives/handoff         body {sessionId} -> ObjectiveHandoffResult
 *   GET  /api/objectives/catalogue                         -> {version, experimental, objectives}
 *
 * <p><b>Deliberate simplifications vs. the old React engine</b> (see also
 * `ObjectiveService.java`'s own class javadoc, which documents the matching backend-side cuts):
 * <ul>
 *   <li>No client-side embedding/vector "objective suggestion" pipeline (old app's
 *       `MicroToolRouter`/`routing.ts` fusion + a `select` server operation). The Java
 *       `ChatController` has no `select`/`dismiss-suggestion` endpoint at all — only
 *       start/answer/correct/dismiss/handoff exist. `ObjectiveSuggestionsComponent` in this port
 *       does a small client-side keyword match against the loaded catalogue instead (see its own
 *       doc comment).</li>
 *   <li>No `course-focus` operation — the task brief mentions one, but `ChatController`'s switch
 *       statement only implements start/answer/correct/dismiss/handoff, and course selection only
 *       ever mattered for warehouse/course-catalogue data, which this backend port never
 *       populates (`ObjectiveService.java` class javadoc). Nothing here calls it.</li>
 *   <li>No optimistic-concurrency `revision` guard on requests — `ObjectiveService.java`'s
 *       `advance()`/`correct()` don't take one (see its javadoc), so none is sent. `revision` is
 *       still read from each returned session for display only.</li>
 *   <li>No sessionStorage answer-draft restore (old app's `restoreDrafts`/`draftKey`) — this app's
 *       `advance()`/`correct() `already persist `pendingAnswer`/`pendingCorrection` on the session
 *       itself before the risky model call, so a page reload's GET already reflects that state
 *       when the backend successfully persisted it; a purely client-side draft cache would only
 *       help the rarer case where the backend's own persistence didn't happen (its javadoc notes
 *       revision checks were simplified, not this path) and isn't worth the extra local storage
 *       plumbing for this preview.</li>
 *   <li>`stop()` unsubscribes the in-flight HTTP request (a real network abort, since Angular's
 *       HttpClient tears down the underlying XHR/fetch on unsubscribe) but the backend has already
 *       been asked to run a (possibly slow, retried) Gemini call — stopping only hides the spinner
 *       client-side, exactly like the old app's `AbortController`-based stop().</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class ObjectivesService {
  sessions = signal<ObjectiveSession[]>([]);
  selectedId = signal<string>('');
  selected = computed<ObjectiveSession | null>(() => this.sessions().find(s => s.id === this.selectedId()) ?? null);

  busy = signal(false);
  error = signal<string>('');

  catalogue = signal<ObjectiveCatalogueItem[]>([]);
  catalogueVersion = signal<string>('');
  private catalogueLoaded = false;
  private catalogueLoading: Promise<void> | null = null;

  private conversationId: string | null = null;
  private activeSub: Subscription | null = null;

  constructor(private http: HttpClient, private auth: AuthService) {}

  private get headers(): HttpHeaders {
    const t = this.auth.token;
    return t ? new HttpHeaders({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }) : new HttpHeaders();
  }

  // -------------------------------------------------------------------
  // Catalogue (loaded once, shared across conversations)
  // -------------------------------------------------------------------

  async loadCatalogue(): Promise<void> {
    if (this.catalogueLoaded) return;
    if (this.catalogueLoading) return this.catalogueLoading;
    this.catalogueLoading = (async () => {
      try {
        const res = await new Promise<ObjectivesCatalogueResponse>((resolve, reject) => {
          this.http.get<ObjectivesCatalogueResponse>('/api/objectives/catalogue', { headers: this.headers })
            .subscribe({ next: resolve, error: reject });
        });
        this.catalogue.set(res?.objectives ?? []);
        this.catalogueVersion.set(res?.version ?? '');
        this.catalogueLoaded = true;
      } catch {
        // Catalogue is browse-only data; the workspace still functions for existing sessions without it.
      } finally {
        this.catalogueLoading = null;
      }
    })();
    return this.catalogueLoading;
  }

  // -------------------------------------------------------------------
  // Sessions for the active conversation
  // -------------------------------------------------------------------

  async loadSessions(conversationId: string): Promise<void> {
    this.conversationId = conversationId;
    try {
      const sessions = await new Promise<ObjectiveSession[]>((resolve, reject) => {
        this.http.get<ObjectiveSession[]>(`/api/conversations/${conversationId}/objectives`, { headers: this.headers })
          .subscribe({ next: resolve, error: reject });
      });
      this.sessions.set(sessions ?? []);
      if (!this.selectedId() && sessions?.length) this.selectedId.set(sessions[0].id);
    } catch {
      this.sessions.set([]);
    }
  }

  /** Called when switching conversations — clears session state (catalogue stays loaded). */
  reset(): void {
    this.activeSub?.unsubscribe();
    this.activeSub = null;
    this.conversationId = null;
    this.sessions.set([]);
    this.selectedId.set('');
    this.busy.set(false);
    this.error.set('');
  }

  select(id: string): void {
    this.selectedId.set(id);
  }

  private put(session: ObjectiveSession): void {
    this.sessions.update(list => [session, ...list.filter(s => s.id !== session.id)]);
  }

  private run<T>(operation: string, body: any): Promise<T | null> {
    const convId = this.conversationId;
    if (!convId || this.busy()) return Promise.resolve(null);
    this.busy.set(true);
    this.error.set('');
    return new Promise<T | null>(resolve => {
      this.activeSub = this.http
        .post<T>(`/api/conversations/${convId}/objectives/${operation}`, body, { headers: this.headers })
        .subscribe({
          next: result => {
            this.busy.set(false);
            this.activeSub = null;
            resolve(result);
          },
          error: (e: any) => {
            this.busy.set(false);
            this.activeSub = null;
            this.error.set(e?.error?.error || 'Please try again.');
            resolve(null);
          },
        });
    });
  }

  /** Cancels the in-flight request client-side. The backend call already in progress is not aborted. */
  stop(): void {
    this.activeSub?.unsubscribe();
    this.activeSub = null;
    this.busy.set(false);
    this.error.set('Stopped. Submitted answers stay saved; you can retry when ready.');
  }

  dismissError(): void {
    this.error.set('');
  }

  // -------------------------------------------------------------------
  // Operations
  // -------------------------------------------------------------------

  async start(toolId: string): Promise<void> {
    const session = await this.run<ObjectiveSession>('start', { toolId });
    if (session) {
      this.put(session);
      this.selectedId.set(session.id);
    }
  }

  async answer(answer: ObjectiveAnswer): Promise<void> {
    const s = this.selected();
    if (!s) return;
    const session = await this.run<ObjectiveSession>('answer', { sessionId: s.id, answer });
    if (session) this.put(session);
    else await this.resync();
  }

  /** Re-submits the session's already-saved answer (ObjectiveSession#pendingAnswer). */
  async retry(): Promise<void> {
    const s = this.selected();
    if (!s?.pendingAnswer) return;
    const session = await this.run<ObjectiveSession>('answer', { sessionId: s.id, answer: { retry: true } });
    if (session) this.put(session);
    else await this.resync();
  }

  async correct(text: string): Promise<void> {
    const s = this.selected();
    if (!s) return;
    const session = await this.run<ObjectiveSession>('correct', { sessionId: s.id, correction: { text } });
    if (session) this.put(session);
    else await this.resync();
  }

  /** Re-submits the session's already-saved correction (ObjectiveSession#pendingCorrection). */
  async retryCorrection(): Promise<void> {
    const s = this.selected();
    if (!s?.pendingCorrection) return;
    const session = await this.run<ObjectiveSession>('correct', { sessionId: s.id, correction: { retry: true } });
    if (session) this.put(session);
    else await this.resync();
  }

  /** Ends/closes the session (ChatController's "dismiss" op — cancels, does not delete). */
  async cancel(sessionId?: string): Promise<void> {
    const id = sessionId ?? this.selected()?.id;
    if (!id) return;
    await this.run<{ ok: boolean }>('dismiss', { sessionId: id });
    await this.resync();
  }

  /** Port of service.ts's handoff() — the compact result payload for handing back to chat. Caller
   *  (ObjectiveWorkspaceComponent) emits it via its `handoffReady` output for another engineer to
   *  wire into the actual chat send flow (this port has no chat-message API to call here — see
   *  this class's own doc comment). */
  async handoff(): Promise<ObjectiveHandoffResult | null> {
    const s = this.selected();
    if (!s) return null;
    return this.run<ObjectiveHandoffResult>('handoff', { sessionId: s.id });
  }

  private async resync(): Promise<void> {
    if (this.conversationId) await this.loadSessions(this.conversationId);
  }
}
