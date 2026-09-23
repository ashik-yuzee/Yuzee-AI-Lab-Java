import { Component, computed, effect, input, signal, untracked } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { ObjectivesService } from '../../services/objectives.service';
import { RoutingService } from '../../services/routing.service';
import { fuseObjectiveMatches, objectiveSelectionBoundary } from './objectives.types';

/** ObjectiveSuggestions.tsx ObjectiveToolbar — the preview toggle bar above the chat. */
@Component({
  selector: 'app-objective-toolbar',
  standalone: true,
  styles: [':host{display:contents}'],
  templateUrl: './objective-toolbar.component.html',
})
export class ObjectiveToolbarComponent {
  constructor(public o: ObjectivesService) {}
  toggle(event: Event): void { this.o.setEnabled((event.target as HTMLInputElement).checked); }
  saved(): void { this.o.setCatalogueOpen(false); this.o.reveal(); }
}

/** ObjectiveSuggestions.tsx ObjectiveSuggestions — the "Explore this further" offer under the last assistant turn. */
@Component({
  selector: 'app-objective-suggestions',
  standalone: true,
  imports: [IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './objective-suggestions.component.html',
})
export class ObjectiveSuggestionsComponent {
  sourceMessageId = input.required<string>();
  userText = input('');
  reviewText = input('');

  offer = signal<any>(null);
  status = signal('');
  hidden = computed(() => !this.o.enabled() || this.o.sessions().some(s => s.sourceMessageId === this.sourceMessageId()));
  item = computed(() => this.o.catalogue().find(x => x.tool_id === this.offer()?.objective_id));

  constructor(public o: ObjectivesService, private routing: RoutingService) {
    // useEffect([enabled, conversationId, sourceMessageId, userText, reviewText])
    effect(onCleanup => {
      const enabled = this.o.enabled(), conversationId = this.o.conversationId(), sourceMessageId = this.sourceMessageId(), userText = this.userText(), reviewText = this.reviewText();
      untracked(() => {
        this.offer.set(null); this.status.set('');
        if (!enabled || !conversationId || objectiveSelectionBoundary(userText)) return;
        const abort = new AbortController(); let begun = false;
        this.status.set('Checking whether an activity could help…');
        const timer = setTimeout(() => { abort.abort(); this.status.set('Suggestions are unavailable. You can browse activities.'); }, 125000);
        // onRouterStatus(): called with the current status, then on every change.
        const unsubscribe = this.routing.onRouterStatus(state => {
          untracked(() => {
            if (state === 'unavailable') { clearTimeout(timer); this.status.set('Suggestions are unavailable. You can browse activities.'); }
            if (state !== 'ready' || begun || abort.signal.aborted) return; begun = true;
            (async () => {
              const [userCandidates, contextCandidates] = await Promise.all([this.routing.retrieveObjectives(userText, abort.signal), reviewText.trim() ? this.routing.retrieveObjectives(reviewText, abort.signal) : Promise.resolve([])]);
              const candidates = fuseObjectiveMatches(userCandidates, contextCandidates, 20, userText);
              if (abort.signal.aborted) return;
              if (!candidates.length) { this.status.set(''); return; }
              const decision = await this.o.api(conversationId, 'select', { sourceMessageId, candidates }, abort.signal);
              if (!abort.signal.aborted) {
                this.status.set('');
                if (decision.disposition === 'AUTO_OPEN') { this.offer.set(null); await this.o.start(decision.objective_id, undefined, decision); }
                else this.offer.set(decision.decision === 'SUGGEST' && decision.disposition !== 'NONE' ? decision : null);
              }
            })().catch(() => { if (!abort.signal.aborted) this.status.set('Suggestions are unavailable. You can browse activities.'); }).finally(() => clearTimeout(timer));
          });
        });
        this.routing.startWarmup();
        onCleanup(() => { clearTimeout(timer); abort.abort(); unsubscribe(); });
      });
    }, { allowSignalWrites: true });
  }

  dismiss(): void {
    this.offer.set(null);
    void this.o.api(this.o.conversationId()!, 'dismiss', { sourceMessageId: this.sourceMessageId() }).catch(() => {});
  }
}
