import { Component, EventEmitter, Input, OnChanges, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ObjectivesService } from '../../services/objectives.service';
import { ObjectiveCatalogueItem } from './objectives.types';

/**
 * Angular port of the old React app's `src/objectives/ObjectiveSuggestions.tsx` — heavily
 * simplified. The old component fed the current chat turn through a client-side BGE embedding
 * index (`MicroToolRouter`/`routing.ts`'s `fuseObjectiveMatches`) and a server `select` operation
 * that ran a whole extra Gemini call to decide SUGGEST/CLARIFY/NONE and auto-open confidence.
 *
 * None of that exists on this Java backend: `ChatController.java`'s `/objectives/{operation}`
 * switch only implements start/answer/correct/dismiss/handoff — there is no `select` operation,
 * no embedding index, and no auto-open policy service wired to an endpoint. Rather than fake a
 * network round trip, this port does a small **client-side keyword-overlap match** against the
 * already-loaded `/api/objectives/catalogue` list: given a hint (typically the latest user
 * message text, passed in by whoever embeds this component next to a chat message — out of scope
 * here per this port's brief, since that's `chat-area.component`), it surfaces up to 3 catalogue
 * entries that share meaningful words with the hint. This is a much weaker match than the old
 * semantic pipeline; it is meant as a lightweight "browse nudge", not a confident recommendation,
 * and never auto-opens a workspace the way the old app's `AUTO_OPEN` disposition did.
 */
@Component({
  selector: 'app-objective-suggestions',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './objective-suggestions.component.html',
  styleUrl: './objective-suggestions.component.scss',
})
export class ObjectiveSuggestionsComponent implements OnChanges {
  /** Text to match against the catalogue — typically the latest user message. */
  @Input() hintText = '';
  /** When set, no suggestions are shown once a session already exists for this chat turn. */
  @Input() sourceMessageId: string | null = null;

  @Output() started = new EventEmitter<string>();

  suggestions = signal<ObjectiveCatalogueItem[]>([]);
  private dismissedIds = new Set<string>();

  constructor(public objectives: ObjectivesService) {
    void this.objectives.loadCatalogue().then(() => this.recompute());
  }

  ngOnChanges(): void {
    this.recompute();
  }

  start(toolId: string): void {
    void this.objectives.start(toolId);
    this.started.emit(toolId);
  }

  dismiss(toolId: string): void {
    this.dismissedIds.add(toolId);
    this.recompute();
  }

  private recompute(): void {
    const text = this.hintText.trim().toLowerCase();
    if (!text || this.alreadyHasSession()) {
      this.suggestions.set([]);
      return;
    }
    const words = new Set(text.match(/[a-z]{4,}/g) ?? []);
    if (!words.size) {
      this.suggestions.set([]);
      return;
    }
    const ranked = this.objectives
      .catalogue()
      .filter(o => o.available && !this.dismissedIds.has(o.tool_id))
      .map(item => ({ item, score: this.overlap(words, item) }))
      .filter(x => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 3)
      .map(x => x.item);
    this.suggestions.set(ranked);
  }

  private overlap(words: Set<string>, item: ObjectiveCatalogueItem): number {
    const itemWords = `${item.button_label} ${item.topic_name} ${item.clear_purpose}`.toLowerCase().match(/[a-z]{4,}/g) ?? [];
    let score = 0;
    for (const w of itemWords) if (words.has(w)) score++;
    return score;
  }

  private alreadyHasSession(): boolean {
    if (!this.sourceMessageId) return false;
    return this.objectives.sessions().some(s => s.sourceMessageId === this.sourceMessageId);
  }
}
