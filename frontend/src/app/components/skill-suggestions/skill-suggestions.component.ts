import { Component, Input, OnChanges, OnDestroy, computed, signal } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { RoutingService } from '../../services/routing.service';
import { SkillChoice, SkillOffer, SkillReview, allowSkillReview, skillMessage } from '../../utils/chat-helpers';

/**
 * Port of SkillSuggestions.tsx (RoutingService.reviewResponseSkills is MicroToolRouter's: up to 3 offers).
 * The React effect re-runs on [sourceMessageId, reviewText, userText, completedToolId, retry]; here every
 * input change or Retry calls reset(), which aborts the previous review exactly like the effect cleanup.
 */
@Component({
  selector: 'app-skill-suggestions',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './skill-suggestions.component.html',
  styleUrl: './skill-suggestions.component.scss'
})
export class SkillSuggestionsComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) sourceMessageId!: string;
  @Input() reviewText = '';
  @Input() userText = '';
  @Input() completedToolId?: string;
  @Input({ required: true }) onChoose!: (message: string, choice: SkillChoice) => Promise<boolean> | boolean;

  readonly review = signal<SkillReview | null>(null);
  readonly busy = signal(false);
  private readonly run = signal(0);
  private started = false;
  private controller = new AbortController();

  readonly allowed = computed(() => { this.run(); return allowSkillReview(this.userText); });
  readonly retryable = computed(() => ['unavailable', 'not-ready', 'timeout', 'busy', 'inference-failed'].includes(this.review()?.reason ?? ''));

  private unsubscribe = () => {};

  constructor(private routing: RoutingService) {}

  ngOnChanges(): void { this.reset(); }

  ngOnDestroy(): void { this.controller.abort(); this.unsubscribe(); }

  retry(): void { this.reset(); }

  private reset(): void {
    this.controller.abort();
    this.unsubscribe();
    const controller = this.controller = new AbortController();
    this.started = false;
    this.review.set(null);
    this.run.update(n => n + 1);
    if (!allowSkillReview(this.userText)) return;
    // onRouterStatus(): called with the current status on subscribe and on every change.
    this.unsubscribe = this.routing.onRouterStatus(status => {
      if (status === 'unavailable') this.review.set({ status: 'abstained', offers: [], reason: 'unavailable' });
      if (status === 'ready' && !this.started) { this.started = true; void this.start(controller); }
    });
    this.routing.startWarmup();
  }

  private async start(controller: AbortController): Promise<void> {
    try {
      let result = await this.routing.reviewResponseSkills(this.reviewText, { signal: controller.signal });
      if (result.reason === 'busy' && !controller.signal.aborted) { await new Promise(r => setTimeout(r, 500)); result = await this.routing.reviewResponseSkills(this.reviewText, { signal: controller.signal }); }
      if (!controller.signal.aborted) { const offers = result.offers.filter(o => o.toolId !== this.completedToolId); this.review.set(offers.length ? { ...result, offers } : { ...result, status: 'abstained', offers }); }
    } catch {
      if (!controller.signal.aborted) this.review.set({ status: 'abstained', offers: [], reason: 'unavailable' });
    }
  }

  async choose(offer: SkillOffer): Promise<void> {
    this.busy.set(true);
    // routing/policy.ts eligibleTools: the catalogue RoutingService loaded before reporting 'ready'.
    try { await this.onChoose(skillMessage(offer.toolId, this.routing.eligibleTools), { toolId: offer.toolId, sourceMessageId: this.sourceMessageId }); }
    finally { this.busy.set(false); }
  }
}
