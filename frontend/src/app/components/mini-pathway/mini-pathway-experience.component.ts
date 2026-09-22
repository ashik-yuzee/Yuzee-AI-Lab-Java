import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { PathwayHistorySelectorComponent } from './pathway-history-selector.component';
import { PathwayLearningCuesComponent, PathwayColourGuideComponent } from './pathway-learning-cues.component';
import { DrawerResizeController } from './drawer-resize';
import { MiniPathwayRun } from './mini-pathway.types';
import { YuzeeContentBlock } from '../../models/types';

/**
 * Port of the old React app's components/MiniPathwayExperience.tsx (+ MiniPathwayStreaming.tsx,
 * PathwayHistorySelector.tsx, PathwayLearningCues.tsx, miniPathway/useDrawerResize.ts) — a
 * resizable side panel that generates and displays a short AI-generated mini career pathway
 * report for the current conversation.
 *
 * Backend: ChatController.generateMiniPathway/getMiniPathways (see
 * backend/src/main/java/com/yuzee/tokenlab/controller/ChatController.java).
 *   GET  /api/conversations/:id/mini-pathway            -> saved runs [{id, goal, report, createdAt}]
 *   POST /api/conversations/:id/mini-pathway (SSE)       -> {phase} / {block} / {done, pathway} / {error}
 *
 * Simplifications vs. the old app:
 *  - No automatic-offer-confidence flow. The old app decided "offer" vs. "automatic" generation
 *    from a client-side BGE embedding confidence score (miniPathway/policy.ts's
 *    decideMiniPathway(), now ported server-side in PathwayPolicyService.java as a standalone
 *    decision gate for the routing engineer to wire up). This component does not consume that
 *    score; it always shows a manual "Generate mini pathway" trigger instead of an automatic
 *    offer card, per this port's brief.
 *  - No expand-to-fullscreen affordance (the old app's Maximize2/Minimize2 toggle) — only the
 *    resizable docked panel + open/close, since fullscreen wasn't part of the requested API.
 *  - The completed-report learning cues/colour guide are shown once for the whole report (text
 *    combined across all content_blocks), not once per rendered block — the shared
 *    ProtocolRendererComponent has no per-block "pathwayLearningCues" slot to hook into and is
 *    not being modified for this port.
 */
@Component({
  selector: 'app-mini-pathway-experience',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ProtocolRendererComponent,
    PathwayHistorySelectorComponent,
    PathwayLearningCuesComponent,
    PathwayColourGuideComponent,
  ],
  templateUrl: './mini-pathway-experience.component.html',
  styleUrl: './mini-pathway-experience.component.scss'
})
export class MiniPathwayExperienceComponent implements OnChanges {
  @Input() conversationId: string | null = null;
  @Input() open = false;

  @Output() closed = new EventEmitter<void>();

  readonly resize = new DrawerResizeController();

  goal = '';

  generating = signal(false);
  stage = signal('');
  streamBlocks = signal<YuzeeContentBlock[]>([]);
  error = signal<string | null>(null);

  runs = signal<MiniPathwayRun[]>([]);
  runsLoading = signal(false);
  selectedRunId = signal<string | null>(null);

  selectedRun = computed<MiniPathwayRun | null>(
    () => this.runs().find(r => r.id === this.selectedRunId()) ?? null
  );

  /** Report text flattened across all content_blocks, fed to the learning-cues badges. */
  combinedText = computed(() => {
    const blocks = this.selectedRun()?.report?.content_blocks ?? [];
    return blocks
      .map(b => [b.title, b.text, ...(b.items ?? []).map(i => `${i.title} ${i.text} ${i.value}`)].filter(Boolean).join(' '))
      .join(' ');
  });

  private activeStream: { close: () => void } | null = null;

  constructor(private api: ApiService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open']?.currentValue && this.conversationId) {
      void this.loadHistory();
    }
    if (changes['conversationId'] && !changes['conversationId'].firstChange) {
      this.stop();
      this.runs.set([]);
      this.selectedRunId.set(null);
      this.error.set(null);
      this.goal = '';
      if (this.open && this.conversationId) void this.loadHistory();
    }
  }

  async loadHistory(): Promise<void> {
    if (!this.conversationId) return;
    this.runsLoading.set(true);
    try {
      const runs = await firstValueFrom(this.api.get<MiniPathwayRun[]>(`/conversations/${this.conversationId}/mini-pathway`));
      this.runs.set(runs ?? []);
      if (!this.selectedRunId() && runs?.length) this.selectedRunId.set(runs[runs.length - 1].id);
    } catch {
      this.error.set('Saved mini pathways could not be loaded.');
    } finally {
      this.runsLoading.set(false);
    }
  }

  selectRun(id: string): void {
    this.selectedRunId.set(id);
    this.error.set(null);
  }

  trackBlock(block: YuzeeContentBlock, index: number): string {
    return block?.id ?? String(index);
  }

  generate(): void {
    const goal = this.goal.trim();
    if (!goal) {
      this.error.set('Enter a goal to generate a mini pathway.');
      return;
    }
    if (!this.conversationId) {
      this.error.set('No conversation selected.');
      return;
    }

    this.activeStream?.close();
    this.error.set(null);
    this.streamBlocks.set([]);
    this.stage.set('Preparing your mini pathway');
    this.generating.set(true);

    this.activeStream = this.api.openStream(
      `/conversations/${this.conversationId}/mini-pathway`,
      { goal },
      data => this.onStreamMessage(data),
      () => this.onStreamError()
    );
  }

  private onStreamMessage(data: any): void {
    if (data?.phase) {
      this.stage.set(data.phase);
    } else if (data?.block) {
      this.streamBlocks.update(blocks => [...blocks, data.block as YuzeeContentBlock]);
    } else if (data?.done) {
      const pathway = data.pathway as MiniPathwayRun;
      this.runs.update(rs => [...rs, pathway]);
      this.selectedRunId.set(pathway.id);
      this.generating.set(false);
      this.streamBlocks.set([]);
      this.activeStream = null;
    } else if (data?.error) {
      this.error.set(data.error);
      this.generating.set(false);
      this.streamBlocks.set([]);
      this.activeStream = null;
    }
  }

  private onStreamError(): void {
    this.error.set('The mini pathway could not be generated.');
    this.generating.set(false);
    this.streamBlocks.set([]);
    this.activeStream = null;
  }

  stop(): void {
    this.activeStream?.close();
    this.activeStream = null;
    if (this.generating()) this.error.set('Stopped. You can try again when you are ready.');
    this.generating.set(false);
    this.streamBlocks.set([]);
  }

  close(): void {
    this.activeStream?.close();
    this.activeStream = null;
    this.generating.set(false);
    this.streamBlocks.set([]);
    this.closed.emit();
  }
}
