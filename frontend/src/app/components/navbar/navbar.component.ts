import { Component, EventEmitter, Output, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { TokenLabService } from '../../services/token-lab.service';
import { AuthService } from '../../services/auth.service';
import { Conversation } from '../../models/types';

/** Keys the parent (AppComponent) understands for opening a specific tool/modal. */
export type ToolMenuKey =
  | 'settings' | 'profile' | 'career-context' | 'token-inspector'
  | 'context-inspector' | 'memory-timeline' | 'analytics' | 'benchmark';

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [CommonModule, DecimalPipe],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.scss'
})
export class NavbarComponent {
  convMenuOpen = signal(false);
  toolsMenuOpen = signal(false);

  @Output() openTool = new EventEmitter<ToolMenuKey>();

  constructor(public lab: TokenLabService, private auth: AuthService) {}

  toggleConvMenu(): void { this.convMenuOpen.update(v => !v); }
  toggleToolsMenu(): void { this.toolsMenuOpen.update(v => !v); }

  selectTool(key: ToolMenuKey): void {
    this.toolsMenuOpen.set(false);
    this.openTool.emit(key);
  }

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
