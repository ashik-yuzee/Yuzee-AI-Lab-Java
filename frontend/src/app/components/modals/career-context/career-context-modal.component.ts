import { Component, EventEmitter, Output, effect, signal, untracked } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { IconComponent } from '../../shared/icon/icon.component';

export interface CareerContextCapsule {
  goal: string;
  currentStage: string;
  targetRole: string;
  education: string;
  keySkills: string;
  location: string;
  timeline: string;
  constraints: string;
  preferences: string;
  decisions: string;
  openQuestions: string;
}

const EMPTY: CareerContextCapsule = {
  goal: '', currentStage: '', targetRole: '', education: '', keySkills: '', location: '',
  timeline: '', constraints: '', preferences: '', decisions: '', openQuestions: ''
};

/** Older capsules used Title-Case keys; the original reads either. */
const LEGACY_KEYS: Record<keyof CareerContextCapsule, string> = {
  goal: 'Goal', currentStage: 'Current Stage', targetRole: 'Target Role', education: 'Education',
  keySkills: 'Key Skills', location: 'Location', timeline: 'Timeline', constraints: 'Constraints',
  preferences: 'Preferences', decisions: 'Decisions', openQuestions: 'Open Questions'
};

/** 1:1 port of CareerContextModal.tsx. Always mounted (like the original), rendered while isCareerContextOpen. */
@Component({
  selector: 'app-career-context-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './career-context-modal.component.html',
  styleUrl: './career-context-modal.component.scss'
})
export class CareerContextModalComponent {
  @Output() closed = new EventEmitter<void>();

  form = signal<CareerContextCapsule>({ ...EMPTY });
  savedNotice = signal(false);

  constructor(public lab: TokenLabService) {
    effect(() => {
      const conv = this.lab.currentConversation();
      untracked(() => this.seedFrom(conv));
    }, { allowSignalWrites: true });
  }

  /** Original useEffect([currentConversation]): re-seed only when the conversation carries a capsule. */
  private seedFrom(conv: ReturnType<TokenLabService['currentConversation']>): void {
    const raw = conv?.careerContext as Record<string, unknown> | undefined;
    if (!raw) return;
    const next = { ...EMPTY };
    (Object.keys(next) as (keyof CareerContextCapsule)[]).forEach(key => {
      next[key] = (raw[key] || raw[LEGACY_KEYS[key]] || '') as string;
    });
    this.form.set(next);
  }

  get estimatedTokens(): number {
    return Math.max(0, Math.ceil(Object.values(this.form()).filter(Boolean).join(' ').length * 0.28));
  }

  set(key: keyof CareerContextCapsule, e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    this.form.update(f => ({ ...f, [key]: value }));
  }

  close(): void {
    this.lab.isCareerContextOpen.set(false);
    this.closed.emit();
  }

  handleSave(): void {
    this.lab.updateCurrentConversationSettings({ careerContext: this.form() });
    this.savedNotice.set(true);
    setTimeout(() => {
      this.savedNotice.set(false);
      this.close();
    }, 800);
  }

  handleClear(): void {
    const empty = { ...EMPTY };
    this.form.set(empty);
    this.lab.updateCurrentConversationSettings({ careerContext: empty });
  }
}
