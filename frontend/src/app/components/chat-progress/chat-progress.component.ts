import { Component, EventEmitter, Input, OnDestroy, OnInit, Output, computed, signal } from '@angular/core';
import { ChatProgressPhase } from '../../models/types';
import { IconComponent } from '../shared/icon/icon.component';

// Port of ux/streamProgress.ts (copy) + ChatStreamingStatus.tsx.
const PHASES: Record<ChatProgressPhase, { titles: string[]; subtext: string }> = {
  routing: { titles: ['Choosing useful guidance'], subtext: 'Finding a focus for your question. You can stop at any time.' },
  waiting: { titles: ['Getting your answer ready', 'Waiting for a response'], subtext: 'You can stop at any time. Your question will stay here.' },
  thinking: { titles: ['Considering your question', 'Working on a useful answer'], subtext: 'A clear answer first, then the next useful step.' },
  receiving: { titles: ['Your answer is coming through', 'Receiving your answer'], subtext: 'We will show it once the complete response has been checked.' },
  retrying: { titles: ['Gemini is busy — retrying once'], subtext: 'Your message is kept. You can stop this request at any time.' },
  reviewing: { titles: ['Reviewing the explanation', 'Checking claims against the context'], subtext: 'Checking the detail against the information provided.' },
  checking: { titles: ['Checking the display format'], subtext: 'Making sure the answer is ready to display.' },
};

export function chatProgressCopy(phase: ChatProgressPhase, tick: number, elapsed: number) {
  const copy = PHASES[phase] ?? PHASES.waiting;
  return { title: copy.titles[Math.max(0, Math.floor(tick)) % copy.titles.length], subtext: elapsed >= 12000 ? 'This is taking longer than usual. You can keep waiting or stop.' : copy.subtext };
}

@Component({
  selector: 'app-chat-progress',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './chat-progress.component.html',
  styles: [':host{display:block}']
})
export class ChatProgressComponent implements OnInit, OnDestroy {
  private readonly phaseValue = signal<ChatProgressPhase>('waiting');
  private readonly startedAtValue = signal(Date.now());
  @Input() set phase(value: ChatProgressPhase | null | undefined) { this.phaseValue.set(value || 'waiting'); }
  @Input() set startedAt(value: number | null | undefined) { this.startedAtValue.set(value || Date.now()); this.tickNow(); }
  @Output() stop = new EventEmitter<void>();

  private readonly now = signal(Date.now());
  private readonly reduced = signal(false);
  private timer?: ReturnType<typeof setInterval>;
  private media?: MediaQueryList;
  private readonly onMedia = () => this.reduced.set(!!this.media?.matches);

  readonly copy = computed(() => {
    const elapsed = Math.max(0, this.now() - this.startedAtValue());
    return chatProgressCopy(this.phaseValue(), this.reduced() ? 0 : elapsed / 3000, elapsed);
  });

  ngOnInit(): void {
    this.media = matchMedia('(prefers-reduced-motion: reduce)');
    this.onMedia();
    this.media.addEventListener('change', this.onMedia);
    this.timer = setInterval(() => this.tickNow(), 1000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    this.media?.removeEventListener('change', this.onMedia);
  }

  private tickNow(): void { this.now.set(Date.now()); }
}
