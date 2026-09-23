import {
  AfterViewInit, Component, Directive, ElementRef, signal
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { TokenLabService } from '../../services/token-lab.service';
import { ApiService } from '../../services/api.service';
import { ConfirmDialogComponent } from '../shared/confirm-dialog/confirm-dialog.component';
import { IconComponent } from '../shared/icon/icon.component';
import { Conversation } from '../../models/types';

/** React's autoFocus on the inline rename box. */
@Directive({ selector: '[appAutofocus]', standalone: true })
class AutofocusDirective implements AfterViewInit {
  constructor(private el: ElementRef<HTMLInputElement>) {}
  ngAfterViewInit(): void { this.el.nativeElement.focus(); }
}

/** Port of Sidebar.tsx. */
@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [ConfirmDialogComponent, IconComponent, AutofocusDirective],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss'
})
export class SidebarComponent {
  conversationToDelete = signal<{ id: string; title: string } | null>(null);
  editingId = signal<string | null>(null);
  editingTitle = signal('');

  constructor(public lab: TokenLabService, private api: ApiService) {}

  close(): void { this.lab.isSidebarOpen.set(false); }

  handleOpenLab(tab = 'context'): void {
    this.lab.activeLabTab.set(tab);
    this.lab.isAdvancedLabOpen.set(true);
    this.close();
  }

  newConversation(): void {
    void this.lab.startNewConversation();
    this.close();
  }

  loadDemo(): void {
    void this.lab.loadDemoConversation();
    this.close();
  }

  selectConversation(conv: Conversation): void {
    void this.lab.selectConversation(conv.id);
    this.close();
  }

  convTitle(conv: Conversation): string { return conv.title || 'Career Exploration'; }

  convMeta(conv: Conversation): string {
    const model = conv.model?.replace('gemini-', '') || '3.6-flash';
    return `${model} · ${conv.thinkingLevel || 'adaptive'} · ${Math.floor((conv.messages?.length || 0) / 2)} exchanges`;
  }

  startRename(conv: Conversation, e: Event): void {
    e.stopPropagation();
    this.editingId.set(conv.id);
    this.editingTitle.set(this.convTitle(conv));
  }

  onEditingTitleInput(e: Event): void {
    this.editingTitle.set((e.target as HTMLInputElement).value);
  }

  commitRename(conv: Conversation): void {
    // After Enter/Escape the input is removed while focused. React 19 drops the resulting blur because it
    // disables event dispatch during commit (react-dom commitBeforeMutationEffects sets _enabled = false),
    // so the original saves once. This guard drops the same blur.
    if (this.editingId() !== conv.id) return;
    const trimmed = this.editingTitle().trim();
    this.editingId.set(null);
    if (!trimmed) return;
    if (conv.id === this.lab.activeConversationId()) void this.lab.updateCurrentConversationSettings({ title: trimmed });
    // As the original: the server is updated but the local list keeps the old title until the next load,
    // and a failed PUT is an unhandled rejection.
    else void firstValueFrom(this.api.put(`/conversations/${conv.id}`, { title: trimmed }));
  }

  onRenameKeydown(conv: Conversation, e: KeyboardEvent): void {
    if (e.key === 'Enter') this.commitRename(conv);
    else if (e.key === 'Escape') this.editingId.set(null);
  }

  requestDelete(conv: Conversation, e: Event): void {
    e.stopPropagation();
    this.conversationToDelete.set({ id: conv.id, title: this.convTitle(conv) });
  }

  get deleteMessage(): string {
    return `Are you sure you want to delete "${this.conversationToDelete()?.title}"? All turns and associated memory compaction history will be removed.`;
  }

  confirmDelete(): void {
    const target = this.conversationToDelete();
    if (!target) return;
    void this.lab.removeConversation(target.id);
    this.conversationToDelete.set(null);
  }

  resetStats(e: Event): void {
    e.stopPropagation();
    void this.lab.resetSessionStats();
  }

  get savedTokens(): number { return this.lab.sessionStats()?.tokensSaved || 0; }
  get cachedTokens(): number { return this.lab.sessionStats()?.totalCachedTokens ?? 0; }
  get outputTokens(): number { return this.lab.sessionStats()?.totalModelOutputTokens || 0; }
  // Show only new uncached tokens — cached reads are a separate (cheaper) cost
  get inputTokens(): number {
    const stats = this.lab.sessionStats();
    return stats?.totalUncachedInputTokens ?? Math.max(0, (stats?.totalModelInputTokens ?? 0) - this.cachedTokens);
  }
  get readsPerTurn(): string {
    return Math.round(this.cachedTokens / Math.max(1, this.lab.sessionStats()?.userFacingChatCalls ?? 1)).toLocaleString();
  }

  k(n: number): string { return n > 1000 ? `${(n / 1000).toFixed(1)}k` : String(n); }
}
