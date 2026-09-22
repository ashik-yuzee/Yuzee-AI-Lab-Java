import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TokenLabService } from '../../../services/token-lab.service';
import { ChatMessage, Conversation } from '../../../models/types';

/**
 * Port of yuzee-ai-token-lab/src/components/ExportModal.tsx. Fully client-side: builds a JSON or
 * CSV blob from TokenLabService's live `conversations`/`sessionStats` signals and downloads it,
 * no backend call.
 *
 * Simplifications vs the old app (field sets differ — see models/types.ts's ChatMessage):
 * - No `currentConversation`-over-`conversations` merge: the old app needed that because
 *   `conversations` there could go stale while a live conversation's telemetry kept streaming in
 *   a separate piece of state. Here `TokenLabService.conversations` is the single signal that's
 *   already updated in place on every turn (see `sendMessage()`/`finishStream()`), so it's always
 *   current — merging it with itself would be a no-op.
 * - No per-turn cost column: the old CSV computed `calcTurnCost(model, usage)` client-side. No
 *   equivalent pricing table/helper exists on this app's frontend (cost is computed server-side
 *   into `SessionStats.estimatedCostUsd` only), so a fabricated per-row cost would be worse than
 *   omitting the column. Session-level `estimatedCostUsd` is still included in the JSON export.
 * - No per-turn feedback column: the old `msg.feedback` field has no equivalent on this app's
 *   `ChatMessage`. `validationFailed` (which does exist) is exported instead, as the closest
 *   per-turn quality signal.
 */
@Component({
  selector: 'app-export-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './export-modal.component.html',
  styleUrl: './export-modal.component.scss',
})
export class ExportModalComponent {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  includeRawText = signal(false);

  constructor(private tokenLab: TokenLabService) {}

  setIncludeRawText(value: boolean): void {
    this.includeRawText.set(value);
  }

  close(): void {
    this.closed.emit();
  }

  downloadJson(): void {
    const conversations = this.tokenLab.conversations();
    const payload = {
      exportTimestamp: Date.now(),
      sessionStats: this.tokenLab.sessionStats(),
      conversations: conversations.map(conv => this.toExportedConversation(conv)),
    };
    this.download(
      JSON.stringify(payload, null, 2),
      'application/json',
      `yuzee-token-lab-export-${Date.now()}.json`
    );
  }

  downloadCsv(): void {
    const header = [
      'ConversationId', 'ConversationTitle', 'TurnId', 'Role', 'ModelId', 'Strategy', 'ResponseMode',
      'PromptTokens', 'OutputTokens', 'TotalTokens', 'CompactionStrategy', 'CompactionTurnsKept',
      'CompactionTurnsDropped', 'CompactionTokensUsed', 'CompactionTokenBudget', 'ValidationFailed', 'Timestamp',
    ].join(',');

    const rows: string[] = [header];
    for (const conv of this.tokenLab.conversations()) {
      for (const msg of conv.messages ?? []) {
        rows.push(this.toCsvRow(conv, msg));
      }
    }

    this.download(rows.join('\n'), 'text/csv', `yuzee-token-telemetry-${Date.now()}.csv`);
  }

  private toExportedConversation(conv: Conversation) {
    return {
      conversationId: conv.id,
      title: conv.title,
      modelId: conv.modelId,
      strategy: conv.strategy,
      optimizationMode: conv.optimizationMode,
      responseMode: conv.responseMode,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
      turnsCount: conv.messages?.length ?? 0,
      turns: (conv.messages ?? []).map(msg => ({
        messageId: msg.id,
        role: msg.role,
        content: this.includeRawText() ? msg.content : '[REDACTED_FOR_RESEARCH_PRIVACY]',
        tokenUsage: msg.tokenUsage ?? null,
        compaction: msg.compaction ?? null,
        validationFailed: msg.validationFailed ?? false,
        timestamp: msg.timestamp ?? null,
      })),
    };
  }

  private toCsvRow(conv: Conversation, msg: ChatMessage): string {
    const u = msg.tokenUsage;
    const c = msg.compaction;
    const total = u ? (u.totalTokens ?? u.promptTokens + u.outputTokens) : '';
    return [
      conv.id,
      `"${(conv.title || '').replace(/"/g, '""')}"`,
      msg.id,
      msg.role,
      conv.modelId ?? '',
      conv.strategy ?? '',
      conv.responseMode ?? '',
      u?.promptTokens ?? '',
      u?.outputTokens ?? '',
      total,
      c?.strategy ?? '',
      c?.turnsKept ?? '',
      c?.turnsDropped ?? '',
      c?.tokensUsed ?? '',
      c?.tokenBudget ?? '',
      msg.validationFailed ?? false,
      msg.timestamp ?? '',
    ].join(',');
  }

  private download(content: string, mimeType: string, filename: string): void {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }
}
