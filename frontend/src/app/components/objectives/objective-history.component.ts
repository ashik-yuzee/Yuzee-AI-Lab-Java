import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ObjectivesService } from '../../services/objectives.service';
import { ObjectiveAnswerReceipt, ObjectiveSession, STATE_LABEL, answerText } from './objectives.types';

/**
 * Angular port of the old React app's `src/objectives/ObjectiveHistory.tsx`, which defined two
 * small components in one file — kept as two standalone components in this one file too (this
 * codebase already does the same for `PathwayLearningCuesComponent`/`PathwayColourGuideComponent`
 * in `mini-pathway/pathway-learning-cues.component.ts`):
 *
 * - `AnswerHistoryComponent` (`app-answer-history`, was `AnswerHistory`): the "Your answers" list
 *   for one session, used inside `ObjectiveWorkspaceComponent`.
 * - `ObjectiveHistoryComponent` (`app-objective-history`, was `ObjectiveHistory`): a per-chat-message
 *   receipt list — every session opened from a given `sourceMessageId` — meant to be dropped next
 *   to that chat message by whichever engineer wires this feature into `chat-area.component`
 *   (out of scope here per this port's brief). Reads sessions straight from `ObjectivesService`,
 *   exactly like the old app's `useObjectives()` hook did.
 */
@Component({
  selector: 'app-answer-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './answer-history.component.html',
  styleUrl: './objective-history.component.scss',
})
export class AnswerHistoryComponent {
  @Input({ required: true }) session!: ObjectiveSession;

  answerText = answerText;

  entries(): ObjectiveAnswerReceipt[] {
    return this.session.answers ?? [];
  }

  hasContent(): boolean {
    return this.entries().length > 0 || !!this.session.pendingAnswer;
  }

  entryStatus(entry: ObjectiveAnswerReceipt, pending: boolean): string {
    if (!pending) return 'Entered';
    return entry.localOnly ? 'Entered · on this device' : 'Entered · saved';
  }

  submittedAt(entry: ObjectiveAnswerReceipt): string {
    return entry.submittedAt ? new Date(entry.submittedAt).toLocaleString() : '';
  }
}

@Component({
  selector: 'app-objective-history',
  standalone: true,
  imports: [CommonModule, AnswerHistoryComponent],
  templateUrl: './objective-history.component.html',
  styleUrl: './objective-history.component.scss',
})
export class ObjectiveHistoryComponent {
  @Input({ required: true }) sourceMessageId!: string;

  @Output() view = new EventEmitter<string>();

  readonly stateLabel = STATE_LABEL;

  constructor(private objectives: ObjectivesService) {}

  sessions(): ObjectiveSession[] {
    return this.objectives.sessions().filter(s => s.sourceMessageId === this.sourceMessageId);
  }

  viewSession(id: string): void {
    this.view.emit(id);
  }
}
