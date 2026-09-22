import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ClarificationAnswer,
  ClarificationQuestion,
  ClarificationQuestionsCardComponent
} from '../../clarification-questions/clarification-questions-card.component';

const QUICK_REPLIES = ['Skip this for now', 'I am not sure yet', 'Show me a simple path first'];

@Component({
  selector: 'app-clarification-questions-modal',
  standalone: true,
  imports: [CommonModule, ClarificationQuestionsCardComponent],
  templateUrl: './clarification-questions-modal.component.html',
  styleUrl: './clarification-questions-modal.component.scss'
})
export class ClarificationQuestionsModalComponent {
  @Input() questions: ClarificationQuestion[] = [];
  @Input() bridgeMessage?: string;
  @Input() disabled = false;

  /** Fired with the answers once the user submits the embedded card. */
  @Output() answered = new EventEmitter<ClarificationAnswer[]>();
  /** Fired with a quick-reply label when the user picks an escape-hatch button instead of answering. */
  @Output() skipped = new EventEmitter<string>();
  /** Fired when the user dismisses the modal (X button / backdrop) without answering or skipping. */
  @Output() closed = new EventEmitter<void>();

  readonly quickReplies = QUICK_REPLIES;

  onAnswered(answers: ClarificationAnswer[]): void {
    this.answered.emit(answers);
  }

  onQuickReply(label: string): void {
    this.skipped.emit(label);
  }
}
