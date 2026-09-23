import { Component, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { IonApp, IonContent } from '@ionic/angular/standalone';
import { AuthService } from './services/auth.service';
import { TokenLabService } from './services/token-lab.service';
import { SendMessagePayload } from './models/types';
import { ObjectivesService } from './services/objectives.service';
import { NavbarComponent } from './components/navbar/navbar.component';
import { SidebarComponent } from './components/sidebar/sidebar.component';
import { ChatAreaComponent } from './components/chat-area/chat-area.component';
import { ObjectiveToolbarComponent } from './components/objectives/objective-suggestions.component';
import { ObjectiveWorkspaceComponent } from './components/objectives/objective-workspace.component';
import { TokenInspectorComponent } from './components/modals/token-inspector/token-inspector.component';
import { PathwayWhiteboardComponent } from './components/pathway-whiteboard/pathway-whiteboard.component';
import { MiniPathwayExperienceComponent } from './components/mini-pathway/mini-pathway-experience.component';
import { AdvancedLabModalComponent } from './components/modals/advanced-lab/advanced-lab-modal.component';
import { UserProfileModalComponent } from './components/modals/user-profile/user-profile-modal.component';
import { ContextInspectorModalComponent } from './components/modals/context-inspector/context-inspector-modal.component';
import { CareerContextModalComponent } from './components/modals/career-context/career-context-modal.component';
import { MemoryTimelineModalComponent } from './components/modals/memory-timeline/memory-timeline-modal.component';
import { BenchmarkModalComponent } from './components/modals/benchmark/benchmark-modal.component';
import { AnalyticsDashboardModalComponent } from './components/modals/analytics-dashboard/analytics-dashboard-modal.component';
import { ExportModalComponent } from './components/modals/export/export-modal.component';
import { SettingsModalComponent } from './components/modals/settings/settings-modal.component';
import { ClarificationQuestionsModalComponent } from './components/modals/clarification-questions/clarification-questions-modal.component';
import { LocationPromptModalComponent } from './components/modals/location-prompt/location-prompt-modal.component';
import { RendererPageComponent } from './components/renderer-page/renderer-page.component';

/** Port of App.tsx: auth gate + login page, the authenticated shell layout, and the Renderer page. */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    IonApp, IonContent,
    NavbarComponent, SidebarComponent, ChatAreaComponent, ObjectiveToolbarComponent, ObjectiveWorkspaceComponent,
    TokenInspectorComponent, PathwayWhiteboardComponent, MiniPathwayExperienceComponent,
    AdvancedLabModalComponent, UserProfileModalComponent, ContextInspectorModalComponent, CareerContextModalComponent,
    MemoryTimelineModalComponent, BenchmarkModalComponent, AnalyticsDashboardModalComponent, ExportModalComponent,
    SettingsModalComponent, ClarificationQuestionsModalComponent, LocationPromptModalComponent, RendererPageComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  /** null = still checking the stored token (the original's AppLoadingScreen). */
  authed = signal<boolean | null>(null);
  page = signal<'chat' | 'renderer'>('chat');

  // LoginPage state
  u = signal('');
  p = signal('');
  err = signal('');
  loading = signal(false);

  constructor(private auth: AuthService, public lab: TokenLabService, public objectives: ObjectivesService) {}

  ngOnInit(): void {
    const DEFAULT_ACCENT = '#7244c6';
    let saved: string | null = null;
    try { saved = localStorage.getItem('oala-accent'); } catch { /* storage optional */ }
    if (saved && saved !== '#8952ee') {
      document.documentElement.style.setProperty('--accent', saved);
    } else {
      try { localStorage.setItem('oala-accent', DEFAULT_ACCENT); } catch { /* storage optional */ }
      document.documentElement.style.setProperty('--accent', DEFAULT_ACCENT);
    }

    if (!this.auth.token) { this.authed.set(false); return; }
    this.auth.checkAuth().subscribe({
      next: (d: { authenticated?: boolean }) => this.setAuthed(d?.authenticated === true),
      error: () => this.setAuthed(false),
    });
  }

  private setAuthed(value: boolean): void {
    this.authed.set(value);
    if (value) void this.lab.loadInitialData();
  }

  handleSubmit(e: Event): void {
    e.preventDefault();
    this.loading.set(true);
    this.err.set('');
    this.auth.login(this.u(), this.p()).subscribe({
      next: () => this.setAuthed(true),
      error: (r: HttpErrorResponse) => {
        this.err.set(r.status === 0 ? 'Connection error' : 'Invalid credentials');
        this.loading.set(false);
      },
    });
  }

  onInput(target: 'u' | 'p', e: Event): void { this[target].set((e.target as HTMLInputElement).value); }

  /** The mini pathway revealing itself closes the other side panels (MiniPathwayExperience.reveal). */
  onMiniPathwayOpened(): void {
    this.lab.isSidebarOpen.set(false);
    this.lab.isWhiteboardOpen.set(false);
    this.lab.isTokenInspectorOpen.set(false);
  }

  /** The original unmounts TokenLabProvider while the Renderer page shows; coming back remounts it and reloads. */
  backFromRenderer(): void {
    this.page.set('chat');
    void this.lab.loadInitialData();
  }

  /** ClarificationQuestionsModal submit / skip-with-text: clear the deferred message + pending questions, then send. */
  onClarificationSend(input: string | SendMessagePayload): void {
    this.lab.clearDeferredMessage();
    void this.lab.sendMessage(input);
  }
}
