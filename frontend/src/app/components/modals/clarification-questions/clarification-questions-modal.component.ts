import { Component, EventEmitter, Input, Output } from '@angular/core';
import { IconComponent } from '../../shared/icon/icon.component';
import {
  ClarificationAnswer,
  ClarificationQuestion,
  ClarificationQuestionsCardComponent
} from '../../clarification-questions/clarification-questions-card.component';

export interface ClarificationQuestionAnswerPayload {
  question_id: string;
  dimension: string;
  selected_values: string[];
  self_input: string;
  answered_at_turn: number;
}

const ESCAPE_HATCHES = ['Skip this for now', 'I am not sure yet', 'Show me a simple path first', 'Tell me what Oala can do'];

/**
 * Port of ClarificationQuestionsModal.tsx. app.component reads TokenLabService's pending questions /
 * deferred-message state and performs the actions:
 * - render only while questions are pending (the original returned null otherwise);
 * - (submitted)      → clear deferred message + pending questions, then
 *                      sendMessage({ message, userQuestionAnswers });
 * - (sendText)       → clear deferred message + pending questions, then sendMessage(text);
 * - (proceedDeferred)→ proceedWithDeferredMessage().
 */
@Component({
  selector: 'app-clarification-questions-modal',
  standalone: true,
  imports: [IconComponent, ClarificationQuestionsCardComponent],
  templateUrl: './clarification-questions-modal.component.html',
  styleUrl: './clarification-questions-modal.component.scss'
})
export class ClarificationQuestionsModalComponent {
  @Input() questions: ClarificationQuestion[] = [];
  @Input() bridgeMessage?: string;
  @Input() originalMessage?: string;
  @Input() hasDeferredMessage = false;

  @Output() submitted = new EventEmitter<{ message: string; userQuestionAnswers: ClarificationQuestionAnswerPayload[] }>();
  @Output() sendText = new EventEmitter<string>();
  @Output() proceedDeferred = new EventEmitter<void>();

  readonly escapeHatches = ESCAPE_HATCHES;

  handleSubmit(answers: ClarificationAnswer[]): void {
    const lines = answers
      .filter(a => a.selected_values.length > 0 || a.self_input)
      .map(a => `• ${a.dimension.replace(/_/g, ' ')}: ${a.self_input || a.selected_values.join(', ')}`);
    const answersText = lines.join('\n') || 'Submitted answers';
    const message = this.originalMessage ? `${this.originalMessage}\n\n${answersText}` : answersText;
    const userQuestionAnswers = answers.map((a, i) => ({
      question_id: a.question_id,
      dimension: a.dimension,
      selected_values: a.selected_values,
      self_input: a.self_input,
      answered_at_turn: i + 1,
    }));
    this.submitted.emit({ message, userQuestionAnswers });
  }

  /** X button: proceed with a deferred original message, otherwise skip with text. */
  handleSkip(): void {
    if (this.hasDeferredMessage) this.proceedDeferred.emit();
    else this.sendText.emit('Skip — proceed with what you know');
  }

  escape(label: string): void {
    if (label === 'Skip this for now' && this.hasDeferredMessage) this.proceedDeferred.emit();
    else this.sendText.emit(label);
  }
}
