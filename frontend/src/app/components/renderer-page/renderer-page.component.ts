import { Component, EventEmitter, Output, computed, signal } from '@angular/core';
import { ProtocolRendererComponent } from '../protocol-renderer/protocol-renderer.component';
import { ChatProgressComponent } from '../chat-progress/chat-progress.component';
import { outputReviewScenarios, outputReviewIssues, ReviewScenario } from './output-review.data';
import { acceptedResponse, responseToReadableText } from '../protocol-renderer/protocol-presentation';
import { validateUserEventAgainstActiveInteraction } from '../protocol-renderer/protocol-validator';
import type { UserEvent } from '../../models/types';

const GROUPS = ['Counselling', 'Boxes & inputs', 'Waiting & recovery'] as const;

/**
 * Port of ProtocolRendererPage.tsx — "Counselling output review": 81 designed examples rendered
 * with the main chat components (protocol renderer + streaming status). No AI calls.
 * Mount with `<app-renderer-page (back)="…" />`.
 */
@Component({
  selector: 'app-renderer-page',
  standalone: true,
  imports: [ProtocolRendererComponent, ChatProgressComponent],
  templateUrl: './renderer-page.component.html',
  styleUrl: './renderer-page.component.scss'
})
export class RendererPageComponent {
  /** "Back to chat" (React's `onBack`). */
  @Output() back = new EventEmitter<void>();

  readonly scenarios = outputReviewScenarios;
  readonly issues = outputReviewIssues;
  readonly groups = GROUPS;

  group = signal<string>('Counselling');
  search = signal('');
  selected = signal(outputReviewScenarios[0].id);
  draft = signal('');
  custom = signal<any>(null);
  error = signal('');
  notice = signal('');
  simulateFailure = signal(false);
  revision = signal(0);
  phone = signal(false);
  stopped = signal(false);
  startedAt = signal(Date.now());

  scenario = computed<ReviewScenario>(() => this.scenarios.find(f => f.id === this.selected())!);
  response = computed<any>(() => this.custom() || this.scenario().response);
  filtered = computed(() => this.scenarios.filter(f => f.group === this.group() && [f.label, f.request, f.expected].join(' ').toLowerCase().includes(this.search().toLowerCase())));
  /** React remounted the renderer/status with key={selected+'-'+revision}; a one-item @for keyed on this does the same. */
  renderKey = computed(() => [this.selected() + '-' + this.revision()]);
  readableText = computed(() => responseToReadableText(this.response()));
  jsonText = computed(() => JSON.stringify(this.response(), null, 2));

  groupCount(name: string): number { return this.scenarios.filter(f => f.group === name).length; }

  choose(id: string): void {
    this.selected.set(id);
    this.custom.set(null);
    this.notice.set('');
    this.error.set('');
    this.stopped.set(false);
    this.revision.update(r => r + 1);
    this.startedAt.set(Date.now());
  }

  chooseGroup(name: string): void {
    this.group.set(name);
    this.search.set('');
    this.choose(this.scenarios.find(f => f.group === name)!.id);
  }

  retryStopped(): void {
    this.stopped.set(false);
    this.notice.set('Example retry selected. In the live chat, this sends the saved question again.');
  }

  preview(): void {
    const parsed = acceptedResponse(this.draft());
    if (!parsed) { this.error.set('This response does not match the supported format. Check its fields and question before trying again.'); return; }
    this.custom.set(parsed);
    this.error.set('');
    this.notice.set('');
    this.revision.update(r => r + 1);
  }

  /** React's onInteract for the preview renderer. Arrow property so `this` survives being passed as an input. */
  onInteract = async (event: UserEvent): Promise<boolean> => {
    if (this.simulateFailure()) {
      this.notice.set('Connection failure simulated. Your answer stays in the form. Turn off the failure option and try again.');
      return false;
    }
    const response = this.response();
    const result = event.userEvent ? validateUserEventAgainstActiveInteraction(event, response.interaction) : { valid: true, errors: [] as string[] };
    this.notice.set(result.valid ? 'Example answer accepted: ' + event.value + '. In live chat, Gemini uses this answer to continue.' : result.errors.join(' '));
    return result.valid;
  };
}
