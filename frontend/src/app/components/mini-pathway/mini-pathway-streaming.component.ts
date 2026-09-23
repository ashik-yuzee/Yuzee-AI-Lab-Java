import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { YuzeeContentBlock, YuzeeResponseV13 } from '../../models/types';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { IconComponent } from '../shared/icon/icon.component';
import { miniPathwayPanelResponse } from './history';

/** Port of MiniPathwayStreaming.tsx. Styled by the global mini-pathway.css. */
@Component({
  selector: 'app-mini-pathway-streaming',
  standalone: true,
  imports: [ProtocolRendererComponent, IconComponent],
  template: `
    <div class="mini-pathway-stream">
      <div class="mini-pathway-stream-status">
        <div role="status" aria-live="polite"><app-icon [size]="17" name="Sparkles" /><span>{{ stage }}</span></div>
        <button type="button" (click)="stop.emit()">Stop</button>
      </div>
      <p class="mini-pathway-stream-caption">{{ blocks.length ? 'Draft · Sections may change while the full pathway is checked.' : 'Your pathway will appear here as each section arrives.' }}</p>
      <div class="mini-pathway-report" aria-label="Pathway draft" aria-busy="true">
        @for (view of views; track view.id) {
          <div class="mini-pathway-stream-section">
            <app-protocol-renderer [response]="view.response" [readOnly]="true" [hideRecommendedActions]="true" [pathwayLearningCues]="true" />
          </div>
        }
        <div class="mini-pathway-skeleton" aria-hidden="true">
          <span class="mini-pathway-skeleton-line mini-pathway-skeleton-title"></span>
          <span class="mini-pathway-skeleton-line"></span>
          <span class="mini-pathway-skeleton-line"></span>
          <span class="mini-pathway-skeleton-line mini-pathway-skeleton-short"></span>
        </div>
      </div>
    </div>
  `,
  styles: [':host{display:contents}'],
})
export class MiniPathwayStreamingComponent implements OnChanges {
  @Input() stage = '';
  @Input() blocks: YuzeeContentBlock[] = [];
  @Input() source: YuzeeResponseV13 | null = null;
  @Output() stop = new EventEmitter<void>();

  views: { id: string; response: YuzeeResponseV13 }[] = [];

  ngOnChanges(): void {
    const source = this.source;
    this.views = source ? this.blocks.map(block => ({
      id: block.id,
      response: miniPathwayPanelResponse({
        ...source, response_intent: 'GENERAL_DELIVERY', content_blocks: [block],
        interaction: { ...(source as any).interaction, kind: 'none', recommended_actions: [] },
      } as YuzeeResponseV13),
    })) : [];
  }
}
