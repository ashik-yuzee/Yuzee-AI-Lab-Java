import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from './services/auth.service';
import { TokenLabService } from './services/token-lab.service';
import { NavbarComponent, ToolMenuKey } from './components/navbar/navbar.component';
import { ChatAreaComponent } from './components/chat-area/chat-area.component';
import { LocationPromptModalComponent } from './components/modals/location-prompt/location-prompt-modal.component';
import { SettingsModalComponent } from './components/modals/settings/settings-modal.component';
import { UserProfileModalComponent } from './components/modals/user-profile/user-profile-modal.component';
import { CareerContextModalComponent } from './components/modals/career-context/career-context-modal.component';
import { TokenInspectorComponent } from './components/modals/token-inspector/token-inspector.component';
import { ContextInspectorModalComponent } from './components/modals/context-inspector/context-inspector-modal.component';
import { MemoryTimelineModalComponent } from './components/modals/memory-timeline/memory-timeline-modal.component';
import { AnalyticsDashboardModalComponent } from './components/modals/analytics-dashboard/analytics-dashboard-modal.component';
import { BenchmarkModalComponent } from './components/modals/benchmark/benchmark-modal.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    NavbarComponent,
    ChatAreaComponent,
    LocationPromptModalComponent,
    SettingsModalComponent,
    UserProfileModalComponent,
    CareerContextModalComponent,
    TokenInspectorComponent,
    ContextInspectorModalComponent,
    MemoryTimelineModalComponent,
    AnalyticsDashboardModalComponent,
    BenchmarkModalComponent
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  loginUsername = 'yuzeeadmin';
  loginPassword = '';
  loggingIn = signal(false);
  loginError = signal('');
  showLocationPrompt = signal(false);
  activeTool = signal<ToolMenuKey | null>(null);

  constructor(public auth: AuthService, public lab: TokenLabService) {}

  onOpenTool(key: ToolMenuKey): void { this.activeTool.set(key); }
  closeTool(): void { this.activeTool.set(null); }

  /** Last assistant message's own token/compaction figures, for the tools that inspect the active turn. */
  get lastAssistantTokenUsage() {
    const msgs = this.lab.activeConversation()?.messages ?? [];
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'assistant' && msgs[i].tokenUsage) return msgs[i].tokenUsage!;
    }
    return null;
  }

  ngOnInit(): void {
    if (this.auth.isAuthenticated) {
      this.lab.loadConversations();
      this.lab.loadSessionStats();
      setTimeout(() => this.showLocationPrompt.set(true), 800);
    }
  }

  doLogin(): void {
    if (this.loggingIn()) return;
    this.loggingIn.set(true);
    this.loginError.set('');
    this.auth.login(this.loginUsername, this.loginPassword).subscribe({
      next: () => {
        this.loggingIn.set(false);
        this.lab.loadConversations();
        this.lab.loadSessionStats();
        setTimeout(() => this.showLocationPrompt.set(true), 800);
      },
      error: () => {
        this.loggingIn.set(false);
        this.loginError.set('Invalid username or password');
      }
    });
  }

  onLocationSet(location: string): void {
    this.lab.setLocation(location);
    this.showLocationPrompt.set(false);
  }

  dismissLocation(): void { this.showLocationPrompt.set(false); }
}
