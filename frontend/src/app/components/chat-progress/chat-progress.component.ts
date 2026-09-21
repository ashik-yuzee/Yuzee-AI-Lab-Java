import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChatProgressPhase } from '../../models/types';

const PHASE_COPY: Record<ChatProgressPhase, { title: string; subtext: string }> = {
  routing:   { title: 'Choosing useful guidance',         subtext: 'Finding a focus for your question. You can stop at any time.' },
  waiting:   { title: 'Getting your answer ready',        subtext: 'You can stop at any time. Your question will stay here.' },
  thinking:  { title: 'Considering your question',        subtext: 'A clear answer first, then the next useful step.' },
  receiving: { title: 'Your answer is coming through',    subtext: 'We will show it once the complete response has been checked.' },
  retrying:  { title: 'Gemini is busy — retrying once',  subtext: 'Your message is kept. You can stop this request at any time.' },
  reviewing: { title: 'Reviewing the explanation',        subtext: 'Checking the detail against the information provided.' },
  checking:  { title: 'Checking the display format',      subtext: 'Making sure the answer is ready to display.' }
};

@Component({
  selector: 'app-chat-progress',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="chat-progress d-flex align-items-start gap-3">
      <div class="progress-icon">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/>
          <path d="M12 6v6l4 2"/>
        </svg>
      </div>
      <div class="progress-copy flex-1">
        <strong>{{ copy.title }}</strong>
        <p class="mb-0">{{ copy.subtext }}</p>
      </div>
      <button class="btn btn-sm btn-outline-secondary stop-btn" (click)="stop.emit()">
        Stop
      </button>
    </div>
  `,
  styles: [`
    .chat-progress { padding: 12px 0 16px; min-height: 76px; max-width: 740px; }
    .progress-icon { display: grid; place-items: center; width: 31px; height: 31px; border-radius: 50%;
      background: #f2edff; color: #7957c6; flex-shrink: 0; animation: breathe 2.8s ease-in-out infinite; }
    @keyframes breathe { 50% { opacity: .5; } }
    .progress-copy strong { font-size: 14px; font-weight: 600; color: #435369; }
    .progress-copy p { margin: 4px 0 0; font-size: 12px; color: #718096; }
    .stop-btn { font-size: 12px; min-height: 36px; }
    @media (prefers-reduced-motion: reduce) { .progress-icon { animation: none; } }
  `]
})
export class ChatProgressComponent {
  @Input({ required: true }) phase!: ChatProgressPhase;
  @Output() stop = new EventEmitter<void>();

  get copy() { return PHASE_COPY[this.phase] ?? PHASE_COPY.waiting; }
}
