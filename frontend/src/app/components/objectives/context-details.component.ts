import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ObjectiveSession, answerText } from './objectives.types';

/**
 * Angular port of the old React app's `src/objectives/ContextDetails.tsx`: an expandable "Details
 * I'm using" panel with an inline correction form, plus a saved-but-not-yet-applied correction
 * banner (`session.pendingCorrection`).
 *
 * Simplification vs. the old app: `context.shared_context.contributions` is never populated by
 * this backend port (`ObjectiveService.java#buildContext` leaves `shared_context` an empty map),
 * so the "details" list always falls back to the plan's own `USER_CONFIRMED` content notes — the
 * fallback branch the old app already had for exactly this case, ported as-is (no dead branch
 * removed, since a future backend change populating shared_context would make it live again).
 */
@Component({
  selector: 'app-context-details',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './context-details.component.html',
  styleUrl: './context-details.component.scss',
})
export class ContextDetailsComponent {
  @Input({ required: true }) session!: ObjectiveSession;
  @Input() disabled = false;

  @Output() correct = new EventEmitter<string>();
  @Output() retry = new EventEmitter<void>();

  editing = false;
  text = '';

  details(): string[] {
    const contributions = this.session.context?.['shared_context']?.contributions as any[] | undefined;
    if (contributions?.length) {
      return contributions
        .filter(e => e.kind !== 'USER_CORRECTION')
        .map(e => `${e.question ? e.question + ' — ' : ''}${answerText(e.value)}${e.truncated ? ' …' : ''}`);
    }
    const fromPlan = (this.session.plan?.ui ?? [])
      .flatMap(c => Array.isArray(c.content) ? c.content : [])
      .filter(n => n.source_status === 'USER_CONFIRMED')
      .map(n => n.detail)
      .filter((d): d is string => !!d);
    return [...new Set(fromPlan)];
  }

  corrections(): { text: string; createdAt: number }[] {
    return this.session.context?.['user_corrections'] ?? [];
  }

  hasContent(): boolean {
    return this.details().length > 0 || this.corrections().length > 0 || !!this.session.pendingCorrection;
  }

  startEditing(): void {
    this.text = '';
    this.editing = true;
  }

  cancelEditing(): void {
    this.editing = false;
  }

  submit(): void {
    const value = this.text.trim();
    if (!value) return;
    this.correct.emit(value);
    this.editing = false;
  }

  requestRetry(): void {
    this.retry.emit();
  }
}
