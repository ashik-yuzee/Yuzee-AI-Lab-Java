import { Component, Input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../shared/icon/icon.component';
import { ObjectivesService } from '../../services/objectives.service';
import { ObjectiveAnswerReceipt, ObjectiveSession, activityTitle, answerHistory, answerText, stateLabel } from './objectives.types';

/** ObjectiveHistory.tsx EnteredAnswer + AnswerHistory. */
@Component({
  selector: 'app-answer-history',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent],
  styles: [':host{display:contents}'],
  templateUrl: './answer-history.component.html',
})
export class AnswerHistoryComponent {
  @Input({ required: true }) session!: ObjectiveSession;
  answerText = answerText;
  get answers(): ObjectiveAnswerReceipt[] { return answerHistory(this.session); }
  status(entry: ObjectiveAnswerReceipt, pending: boolean): string { return pending ? (entry.localOnly ? 'Entered · on this device' : 'Entered · saved') : 'Entered'; }
  iso(ms: number): string { return new Date(ms).toISOString(); }
  local(ms: number): string { return new Date(ms).toLocaleString(); }
}

/** ObjectiveHistory.tsx ObjectiveHistory: saved activities anchored under the chat turn that started them. */
@Component({
  selector: 'app-objective-history',
  standalone: true,
  imports: [IconComponent, AnswerHistoryComponent],
  styles: [':host{display:contents}'],
  templateUrl: './objective-history.component.html',
})
export class ObjectiveHistoryComponent {
  @Input({ required: true }) sourceMessageId!: string;
  activityTitle = activityTitle;
  stateLabel = stateLabel;

  constructor(public o: ObjectivesService) {}

  get sessions(): ObjectiveSession[] { return this.o.sessions().filter(s => s.sourceMessageId === this.sourceMessageId); }

}
