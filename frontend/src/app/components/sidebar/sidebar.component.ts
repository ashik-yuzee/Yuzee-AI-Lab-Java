import {
  AfterViewInit, Component, Directive, ElementRef, Input, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TokenLabService } from '../../services/token-lab.service';
import { ConfirmDialogComponent } from '../shared/confirm-dialog/confirm-dialog.component';
import { Conversation } from '../../models/types';

/** Focuses (and selects) a freshly-rendered rename `<input>` — a plain-DOM stand-in for
 * React's autoFocus on the inline rename box; trivial enough not to need @ViewChild wiring
 * across the @for loop. */
@Directive({ selector: '[appAutofocusSelect]', standalone: true })
class AutofocusSelectDirective implements AfterViewInit {
  constructor(private el: ElementRef<HTMLInputElement>) {}
  ngAfterViewInit(): void {
    const input = this.el.nativeElement;
    setTimeout(() => { input.focus(); input.select(); });
  }
}

/** Port of Sidebar.tsx: conversation list with inline rename/delete, New/Load Demo actions,
 * and a compact session-usage footer. Reads/writes all conversation state through
 * TokenLabService — no local CRUD logic of its own. */
@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, ConfirmDialogComponent, AutofocusSelectDirective],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss'
})
export class SidebarComponent {
  @Input() collapsed = false;

  conversationToDelete = signal<{ id: string; title: string } | null>(null);
  editingId = signal<string | null>(null);
  editingTitle = signal('');

  constructor(public lab: TokenLabService) {}

  async newConversation(): Promise<void> {
    await this.lab.createConversation();
  }

  async loadDemo(): Promise<void> {
    await this.lab.loadDemoConversation();
  }

  async selectConversation(conv: Conversation): Promise<void> {
    if (this.editingId() === conv.id) return;
    await this.lab.selectConversation(conv.id);
  }

  startRename(conv: Conversation, e: Event): void {
    e.stopPropagation();
    this.editingId.set(conv.id);
    this.editingTitle.set(conv.title || 'Career Exploration');
  }

  onEditingTitleInput(e: Event): void {
    this.editingTitle.set((e.target as HTMLInputElement).value);
  }

  commitRename(): void {
    const id = this.editingId();
    if (!id) return;
    const trimmed = this.editingTitle().trim();
    this.editingId.set(null);
    if (trimmed) this.lab.renameConversation(id, trimmed);
  }

  cancelRename(): void {
    this.editingId.set(null);
  }

  onRenameKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter') {
      e.preventDefault();
      this.commitRename();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      this.cancelRename();
    }
  }

  requestDelete(conv: Conversation, e: Event): void {
    e.stopPropagation();
    this.conversationToDelete.set({ id: conv.id, title: conv.title || 'Career Exploration' });
  }

  async confirmDelete(): Promise<void> {
    const target = this.conversationToDelete();
    this.conversationToDelete.set(null);
    if (target) await this.lab.deleteConversation(target.id);
  }

  cancelDelete(): void {
    this.conversationToDelete.set(null);
  }

  async resetStats(e: Event): Promise<void> {
    e.stopPropagation();
    await this.lab.resetSessionStats();
  }

  formatTokens(n: number | undefined): string {
    const v = n ?? 0;
    return v > 1000 ? `${(v / 1000).toFixed(1)}k` : String(v);
  }

  formatCost(n: number | undefined): string {
    return '$' + (n ?? 0).toFixed(4);
  }
}
