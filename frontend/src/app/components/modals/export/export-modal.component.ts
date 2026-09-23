import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { calcTurnCost } from '../../../utils/chat-helpers';
import { Conversation } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

/** 1:1 port of ExportModal.tsx — client-side JSON / CSV download of TokenLabService's conversations. */
@Component({
  selector: 'app-export-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './export-modal.component.html',
  styleUrl: './export-modal.component.scss',
})
export class ExportModalComponent {
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();

  includeRawText = signal(false);

  constructor(private lab: TokenLabService) {}

  onToggle(e: Event): void {
    this.includeRawText.set((e.target as HTMLInputElement).checked);
  }

  close(): void {
    this.lab.isExportOpen.set(false);
    this.closed.emit();
  }

  /** Merge currentConversation (has live telemetry) over conversations list. */
  private get mergedConversations(): Conversation[] {
    const current = this.lab.currentConversation();
    return this.lab.conversations().map(c => (current && c.id === current.id ? current : c));
  }

  downloadJson(): void {
    const exportData = this.mergedConversations.map(conv => ({
      conversationId: conv.id,
      title: conv.title,
      model: conv.model,
      strategy: conv.strategy,
      preset: conv.preset,
      responseMode: conv.responseMode,
      thinkingLevel: conv.thinkingLevel,
      turnsCount: conv.messages?.length || 0,
      turns: conv.messages?.map(msg => ({
        messageId: msg.id,
        role: msg.role,
        content: this.includeRawText() ? msg.content : '[REDACTED_FOR_RESEARCH_PRIVACY]',
        usage: msg.telemetry?.usage || null,
        contextBreakdown: msg.telemetry?.contextMetrics || null,
        compactionEvent: msg.telemetry?.compactionMetrics || null,
        feedback: msg.feedback || null,
        timestamp: msg.createdAt,
      })),
      compactionHistory: conv.compactionHistory,
    }));
    const json = JSON.stringify({ exportTimestamp: Date.now(), sessionStats: this.lab.sessionStats(), conversations: exportData }, null, 2);
    this.download(json, 'application/json', `yuzee-token-lab-export-${Date.now()}.json`);
  }

  downloadCsv(): void {
    const rows: string[] = [
      [
        'ConversationId', 'ConversationTitle', 'TurnId', 'Role', 'Model', 'Strategy', 'ThinkingLevel',
        'GrossInputTokens', 'UncachedInputTokens', 'CachedReadTokens', 'OutputTokens', 'ThinkingTokens',
        'TotalBilledTokens', 'EstimatedCostUSD', 'LatencyMs', 'Feedback', 'Timestamp',
      ].join(','),
    ];

    this.mergedConversations.forEach(conv => {
      conv.messages?.forEach(msg => {
        const u = msg.telemetry?.usage;
        const model = msg.telemetry?.model || conv.model || '';
        const cost = u && model ? calcTurnCost(model, u) : null;
        const uncached = u ? (u.uncachedInputTokens ?? (u.inputTokens - (u.cachedTokens ?? 0))) : '';
        rows.push([
          conv.id,
          `"${(conv.title || '').replace(/"/g, '""')}"`,
          msg.id,
          msg.role,
          model,
          conv.strategy,
          conv.thinkingLevel,
          u?.inputTokens ?? '',
          uncached,
          u?.cachedTokens ?? '',
          u?.outputTokens ?? '',
          u?.thinkingTokens ?? '',
          u?.totalTokens ?? '',
          cost !== null ? cost.toFixed(8) : '',
          u?.latencyMs ?? '',
          msg.feedback?.type ?? '',
          msg.createdAt,
        ].join(','));
      });
    });

    this.download(rows.join('\n'), 'text/csv', `yuzee-token-telemetry-${Date.now()}.csv`);
  }

  private download(content: string, type: string, filename: string): void {
    const url = URL.createObjectURL(new Blob([content], { type }));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }
}
