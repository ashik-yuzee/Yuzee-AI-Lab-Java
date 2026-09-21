import { Component, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { TokenLabService } from '../../services/token-lab.service';
import { AuthService } from '../../services/auth.service';
import { Conversation } from '../../models/types';

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [CommonModule, DecimalPipe],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.scss'
})
export class NavbarComponent {
  convMenuOpen = signal(false);

  constructor(public lab: TokenLabService, private auth: AuthService) {}

  toggleConvMenu(): void { this.convMenuOpen.update(v => !v); }

  async newConversation(): Promise<void> {
    this.convMenuOpen.set(false);
    await this.lab.createConversation();
  }

  async selectConv(conv: Conversation): Promise<void> {
    this.convMenuOpen.set(false);
    await this.lab.selectConversation(conv.id);
  }

  logout(): void { this.auth.logout(); }

  get costDisplay(): string {
    const stats = this.lab.sessionStats();
    if (!stats) return '';
    return '$' + stats.estimatedCostUsd.toFixed(4);
  }
}
