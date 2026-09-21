import { Component, AfterViewChecked, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TokenLabService } from '../../services/token-lab.service';
import { ChatMessage, YuzeeResponseV13 } from '../../models/types';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { ChatProgressComponent } from '../chat-progress/chat-progress.component';

@Component({
  selector: 'chat-area',
  standalone: true,
  imports: [CommonModule, FormsModule, ProtocolRendererComponent, ChatProgressComponent],
  templateUrl: './chat-area.component.html',
  styleUrl: './chat-area.component.scss'
})
export class ChatAreaComponent implements AfterViewChecked {
  @ViewChild('messagesArea') messagesArea?: ElementRef<HTMLElement>;

  draftMessage = '';
  private lastScrollHeight = 0;

  constructor(public lab: TokenLabService) {}

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

  send(): void {
    const msg = this.draftMessage.trim();
    if (!msg || this.lab.isStreaming()) return;
    this.draftMessage = '';
    this.lab.sendMessage(msg);
  }

  onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.send(); }
  }

  autoResize(e: Event): void {
    const ta = e.target as HTMLTextAreaElement;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 200) + 'px';
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
