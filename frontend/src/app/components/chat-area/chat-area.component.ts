import { Component, AfterViewChecked, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TokenLabService } from '../../services/token-lab.service';
import { ChatMessage, UserEvent, YuzeeResponseV13 } from '../../models/types';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { ChatProgressComponent } from '../chat-progress/chat-progress.component';
import { ComposerComponent, ComposerSendEvent } from '../composer/composer.component';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { ChatTurnDividerComponent } from '../chat-turn-divider/chat-turn-divider.component';

@Component({
  selector: 'chat-area',
  standalone: true,
  imports: [CommonModule, ProtocolRendererComponent, ChatProgressComponent, ComposerComponent, SidebarComponent, ChatTurnDividerComponent],
  templateUrl: './chat-area.component.html',
  styleUrl: './chat-area.component.scss'
})
export class ChatAreaComponent implements AfterViewChecked {
  @ViewChild('messagesArea') messagesArea?: ElementRef<HTMLElement>;

  private lastScrollHeight = 0;

  constructor(public lab: TokenLabService) {}

  /** Divider shows before a message whose calendar day differs from the previous one (or the first message). */
  showDividerBefore(messages: ChatMessage[], index: number): boolean {
    if (index === 0) return true;
    const prev = new Date(messages[index - 1].timestamp ?? Date.now());
    const cur = new Date(messages[index].timestamp ?? Date.now());
    return prev.toDateString() !== cur.toDateString();
  }

  /** ChatMessage.timestamp is an ISO string (from the backend's Instant); the divider wants epoch millis. */
  toEpoch(timestamp?: string): number | undefined {
    if (!timestamp) return undefined;
    const ms = new Date(timestamp).getTime();
    return Number.isNaN(ms) ? undefined : ms;
  }

  /**
   * A protocol-rendered interaction (question answer or recommended-action click) or an
   * action-execute confirmation always resolves through the same chat-send path as free text —
   * the server re-validates the submitted UserEvent against the interaction it actually last
   * sent (see ChatController/ProtocolValidator), never trusting this client-side echo alone.
   */
  onInteract(event: UserEvent, renderer: ProtocolRendererComponent): void {
    if (this.lab.isStreaming()) return;
    this.lab.sendMessage(event.value ?? '', event.userEvent);
    // sendMessage is fire-and-forget (SSE handled internally); the interaction widget only needs
    // to leave its "submitting" state, not know the eventual answer, so report acceptance now.
    renderer.reportInteractionResult(true);
  }

  ngAfterViewChecked(): void {
    const el = this.messagesArea?.nativeElement;
    if (el && el.scrollHeight !== this.lastScrollHeight) {
      el.scrollTop = el.scrollHeight;
      this.lastScrollHeight = el.scrollHeight;
    }
  }

  async newConv(): Promise<void> { await this.lab.createConversation(); }

  async selectConv(id: string): Promise<void> { await this.lab.selectConversation(id); }

  async deleteConv(e: Event, id: string): Promise<void> {
    e.stopPropagation();
    await this.lab.deleteConversation(id);
  }

  onComposerSend(event: ComposerSendEvent): void {
    if (this.lab.isStreaming()) return;
    // Attachments aren't wired into the backend contract yet (see task follow-up); the raw text,
    // @Oala mention included, is sent as-is — the server re-detects it independently, same as the
    // old app's parseOalaMention on the client.
    this.lab.sendMessage(event.text, event.addressedOala ? { addressedOala: true } : undefined);
  }

  asString(content: unknown): string {
    if (typeof content === 'string') return content;
    return JSON.stringify(content);
  }

  isValidProtocol(parsed: unknown): boolean {
    const p = parsed as YuzeeResponseV13;
    return !!p && Array.isArray(p.content_blocks) && p.content_blocks.length > 0;
  }

  asProtocol(parsed: unknown): YuzeeResponseV13 { return parsed as YuzeeResponseV13; }

  tokenTotal(msg: ChatMessage): number {
    if (!msg.tokenUsage) return 0;
    return (msg.tokenUsage.promptTokens || 0) + (msg.tokenUsage.outputTokens || 0);
  }

  renderMarkdown(text: string): string {
    return text
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\n\n/g, '</p><p>')
      .replace(/^/, '<p>')
      .replace(/$/, '</p>');
  }
}
