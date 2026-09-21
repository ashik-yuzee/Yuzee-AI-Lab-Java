import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from './services/auth.service';
import { TokenLabService } from './services/token-lab.service';
import { NavbarComponent } from './components/navbar/navbar.component';
import { ChatAreaComponent } from './components/chat-area/chat-area.component';
import { LocationPromptModalComponent } from './components/modals/location-prompt/location-prompt-modal.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    NavbarComponent,
    ChatAreaComponent,
    LocationPromptModalComponent
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

  constructor(public auth: AuthService, public lab: TokenLabService) {}

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
