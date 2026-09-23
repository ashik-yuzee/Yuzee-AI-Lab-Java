import { AfterViewChecked, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { ObjectiveSession, answerText } from './objectives.types';

/** Port of the original's objectives/ContextDetails.tsx. The host recreates it per session revision (React `key`). */
@Component({
  selector: 'app-context-details',
  standalone: true,
  styles: [':host{display:contents}'],
  templateUrl: './context-details.component.html',
})
export class ContextDetailsComponent implements AfterViewChecked {
  @Input({ required: true }) session!: ObjectiveSession;
  @Input() disabled = false;
  @Output() correct = new EventEmitter<string>();
  @Output() retry = new EventEmitter<void>();
  @ViewChild('textarea') textarea?: ElementRef<HTMLTextAreaElement>;

  editing = false;
  text = '';
  private focusPending = false;

  ngAfterViewChecked(): void {
    if (this.focusPending && this.textarea) { this.focusPending = false; this.textarea.nativeElement.focus(); }
  }

  get details(): string[] {
    const contributions = this.session.context?.shared_context?.contributions || [];
    return contributions.length
      ? contributions.filter((e: any) => e.kind !== 'USER_CORRECTION').map((e: any) => `${e.question ? e.question + ' — ' : ''}${answerText(e.value)}${e.truncated ? ' …' : ''}`)
      : [...new Set<string>(((this.session.plan?.ui || []) as any[]).flatMap(c => c.content || []).filter((n: any) => n.source_status === 'USER_CONFIRMED').map((n: any) => n.detail).filter(Boolean))];
  }

  get corrections(): any[] { return this.session.context?.user_corrections || []; }

  startEditing(): void { this.editing = true; this.focusPending = true; }

  submit(event: Event): void {
    event.preventDefault();
    if (this.text.trim()) this.correct.emit(this.text.trim());
  }

  input(event: Event): void { this.text = (event.target as HTMLTextAreaElement).value; }
}
