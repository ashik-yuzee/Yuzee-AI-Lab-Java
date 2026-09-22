import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { TokenLabService } from '../../../services/token-lab.service';

export interface CareerContextCapsule {
  goal: string;
  targetRole: string;
  currentStage: string;
  education: string;
  keySkills: string;
  location: string;
  timeline: string;
  constraints: string;
  preferences: string;
  decisions: string;
  openQuestions: string;
}

const EMPTY_CAPSULE: CareerContextCapsule = {
  goal: '', targetRole: '', currentStage: '', education: '', keySkills: '',
  location: '', timeline: '', constraints: '', preferences: '', decisions: '', openQuestions: ''
};

// ponytail: fields used to be stored under Title-Case keys (e.g. "Target Role") before the
// camelCase rename in the React app; read either so old conversations still populate the form.
const LEGACY_KEYS: Record<keyof CareerContextCapsule, string> = {
  goal: 'Goal', targetRole: 'Target Role', currentStage: 'Current Stage', education: 'Education',
  keySkills: 'Key Skills', location: 'Location', timeline: 'Timeline', constraints: 'Constraints',
  preferences: 'Preferences', decisions: 'Decisions', openQuestions: 'Open Questions'
};

interface FieldDef {
  key: keyof CareerContextCapsule;
  label: string;
  placeholder: string;
  textarea?: boolean;
}

export const CAREER_CONTEXT_FIELDS: FieldDef[] = [
  { key: 'goal', label: 'Career Goal / Ambition', placeholder: 'e.g., Transition from IT Helpdesk to SOC Analyst Tier 1' },
  { key: 'targetRole', label: 'Target Role Title', placeholder: 'e.g., Junior Security Operations Center Analyst' },
  { key: 'currentStage', label: 'Current Stage / Experience', placeholder: 'e.g., 2 years desktop support, CompTIA Network+' },
  { key: 'keySkills', label: 'Key Verified Skills', placeholder: 'e.g., TCP/IP, Linux basics, Active Directory, Wireshark' },
  { key: 'education', label: 'Education / Certifications', placeholder: "e.g., Associate's in IT, CompTIA A+" },
  { key: 'location', label: 'Location', placeholder: 'e.g., Melbourne, Victoria' },
  { key: 'timeline', label: 'Study Timeline', placeholder: 'e.g., 6-9 months part-time (10 hrs/week)' },
  { key: 'constraints', label: 'Budget / Constraints', placeholder: 'e.g., Budget under $1,000; self-paced online only' },
  { key: 'preferences', label: 'Preferences', placeholder: 'e.g., Prefers remote roles, async communication' },
  { key: 'decisions', label: 'Decisions Already Made', placeholder: 'e.g., Decided on CompTIA Security+ first before attempting CySA+', textarea: true },
  { key: 'openQuestions', label: 'Open Questions', placeholder: 'e.g., Not sure whether to prioritize certs or a degree', textarea: true }
];

@Component({
  selector: 'app-career-context-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './career-context-modal.component.html',
  styleUrl: './career-context-modal.component.scss'
})
export class CareerContextModalComponent implements OnInit {
  @Input() conversationId: string | null = null;
  @Output() closed = new EventEmitter<void>();

  readonly fields = CAREER_CONTEXT_FIELDS;
  form: CareerContextCapsule = { ...EMPTY_CAPSULE };

  estimatedTokens = signal(0);
  saving = signal(false);
  savedNotice = signal(false);

  private tokenCountTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private api: ApiService, private lab: TokenLabService) {}

  ngOnInit(): void {
    const conv = this.lab.conversations().find(c => c.id === this.conversationId);
    this.form = this.normalize(conv?.careerContext);
    this.refreshTokenEstimate();
  }

  private normalize(raw: unknown): CareerContextCapsule {
    const src = (raw ?? {}) as Record<string, unknown>;
    const out = { ...EMPTY_CAPSULE };
    (Object.keys(out) as (keyof CareerContextCapsule)[]).forEach(key => {
      const value = src[key] ?? src[LEGACY_KEYS[key]];
      out[key] = typeof value === 'string' ? value : '';
    });
    return out;
  }

  onFieldChange(): void {
    if (this.tokenCountTimer) clearTimeout(this.tokenCountTimer);
    this.tokenCountTimer = setTimeout(() => this.refreshTokenEstimate(), 400);
  }

  private refreshTokenEstimate(): void {
    const text = Object.values(this.form).filter(Boolean).join(' ');
    if (!text) {
      this.estimatedTokens.set(0);
      return;
    }
    this.api.post<{ total: number }>('/tokens/count', { text }).subscribe({
      next: r => this.estimatedTokens.set(r.total ?? 0),
      error: () => {}
    });
  }

  async save(): Promise<void> {
    if (!this.conversationId) {
      this.closed.emit();
      return;
    }
    this.saving.set(true);
    try {
      await firstValueFrom(this.api.put(`/conversations/${this.conversationId}`, { careerContext: this.form }));
      this.lab.conversations.update(cs =>
        cs.map(c => c.id === this.conversationId ? { ...c, careerContext: { ...this.form } } : c)
      );
      this.savedNotice.set(true);
      setTimeout(() => {
        this.savedNotice.set(false);
        this.closed.emit();
      }, 800);
    } finally {
      this.saving.set(false);
    }
  }

  async clear(): Promise<void> {
    this.form = { ...EMPTY_CAPSULE };
    this.estimatedTokens.set(0);
    if (!this.conversationId) return;
    try {
      await firstValueFrom(this.api.put(`/conversations/${this.conversationId}`, { careerContext: this.form }));
      this.lab.conversations.update(cs =>
        cs.map(c => c.id === this.conversationId ? { ...c, careerContext: {} } : c)
      );
    } catch {}
  }
}
