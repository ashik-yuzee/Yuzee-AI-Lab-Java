import { Component, EventEmitter, OnInit, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../../services/api.service';
import { TokenLabService } from '../../../services/token-lab.service';

export type FactCategory = 'general' | 'like' | 'dislike';

export interface ProfileFact {
  id: string;
  text: string;
  category: FactCategory;
}

export interface ProfileContradiction {
  id: string;
  factId: string;
  conflictingStatement: string;
  reason: string;
  resolved: boolean;
}

const FACTS_KEY = 'yuzee_profile_facts';
const CONTRADICTIONS_KEY = 'yuzee_contradictions';

const CATEGORY_LABEL: Record<FactCategory, string> = { general: 'Facts', like: 'Likes', dislike: 'Dislikes' };
const CATEGORY_ICON: Record<FactCategory, string> = { general: '📋', like: '❤️', dislike: '👎' };

@Component({
  selector: 'app-user-profile-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user-profile-modal.component.html',
  styleUrl: './user-profile-modal.component.scss'
})
export class UserProfileModalComponent implements OnInit {
  @Output() closed = new EventEmitter<void>();

  readonly categories: FactCategory[] = ['general', 'like', 'dislike'];
  readonly categoryLabel = CATEGORY_LABEL;
  readonly categoryIcon = CATEGORY_ICON;

  activeTab: 'profile' | 'contradictions' = 'profile';

  facts = signal<ProfileFact[]>([]);
  contradictions = signal<ProfileContradiction[]>([]);
  detecting = signal(false);

  locationDraft = '';
  editingId: string | null = null;
  editingText = '';
  newFactText = '';
  newCategory: FactCategory = 'general';

  constructor(private api: ApiService, public lab: TokenLabService) {}

  ngOnInit(): void {
    this.locationDraft = this.lab.userLocation() ?? '';
    this.facts.set(this.loadFromStorage<ProfileFact>(FACTS_KEY));
    this.contradictions.set(this.loadFromStorage<ProfileContradiction>(CONTRADICTIONS_KEY));
  }

  factsByCategory(cat: FactCategory): ProfileFact[] {
    return this.facts().filter(f => f.category === cat);
  }

  unresolvedCount(): number {
    return this.contradictions().filter(c => !c.resolved).length;
  }

  factText(factId: string): string {
    return this.facts().find(f => f.id === factId)?.text ?? '(fact no longer stored)';
  }

  saveLocation(): void {
    this.lab.setLocation(this.locationDraft.trim());
  }

  addFact(): void {
    const text = this.newFactText.trim();
    if (!text) return;
    this.persistFacts([...this.facts(), { id: this.newId(), text, category: this.newCategory }]);
    this.newFactText = '';
  }

  startEdit(fact: ProfileFact): void {
    this.editingId = fact.id;
    this.editingText = fact.text;
  }

  saveEdit(): void {
    const text = this.editingText.trim();
    if (!text || !this.editingId) {
      this.editingId = null;
      return;
    }
    this.persistFacts(this.facts().map(f => f.id === this.editingId ? { ...f, text } : f));
    this.editingId = null;
  }

  cancelEdit(): void {
    this.editingId = null;
  }

  deleteFact(id: string): void {
    this.persistFacts(this.facts().filter(f => f.id !== id));
  }

  clearAllFacts(): void {
    this.persistFacts([]);
  }

  resolveContradiction(id: string): void {
    this.persistContradictions(this.contradictions().map(c => c.id === id ? { ...c, resolved: true } : c));
  }

  dismissContradiction(id: string): void {
    this.persistContradictions(this.contradictions().filter(c => c.id !== id));
  }

  clearResolved(): void {
    this.persistContradictions(this.contradictions().filter(c => !c.resolved));
  }

  /** Angular templates can't call `.some(c => ...)` inline (arrow functions aren't template expressions). */
  hasResolvedContradictions(): boolean {
    return this.contradictions().some(c => c.resolved);
  }

  /** Extract facts (and check for contradictions) from what the user has said in this conversation so far. */
  async detectFromConversation(): Promise<void> {
    const userMessage = (this.lab.activeConversation()?.messages ?? [])
      .filter(m => m.role === 'user')
      .map(m => String(m.content ?? ''))
      .join('\n')
      .trim();
    if (!userMessage) return;

    this.detecting.set(true);
    try {
      const modelId = this.lab.activeModelId();
      const conversationId = this.lab.activeConversationId() ?? undefined;

      const factsRes = await firstValueFrom(
        this.api.post<{ facts: { id: string; text: string; category: FactCategory }[] }>(
          '/extract-profile-facts', { userMessage, modelId }
        )
      );
      const incoming = factsRes.facts ?? [];
      const existingTextsLower = new Set(this.facts().map(f => f.text.trim().toLowerCase()));
      const merged = [...this.facts()];
      for (const f of incoming) {
        const key = (f.text ?? '').trim().toLowerCase();
        if (!key || existingTextsLower.has(key)) continue;
        existingTextsLower.add(key);
        merged.push({ id: f.id || this.newId(), text: f.text, category: f.category || 'general' });
      }
      this.persistFacts(merged);

      const contraRes = await firstValueFrom(
        this.api.post<{ contradictions: { factId: string; conflictingStatement: string; reason: string }[] }>(
          '/detect-contradictions',
          { userMessage, profileFacts: merged, conversationId, modelId }
        )
      );
      const incomingContra = contraRes.contradictions ?? [];
      const existingContraKeys = new Set(this.contradictions().map(c => `${c.factId}::${c.conflictingStatement}`));
      const mergedContra = [...this.contradictions()];
      for (const c of incomingContra) {
        const key = `${c.factId}::${c.conflictingStatement}`;
        if (existingContraKeys.has(key)) continue;
        existingContraKeys.add(key);
        mergedContra.push({ id: this.newId(), factId: c.factId, conflictingStatement: c.conflictingStatement, reason: c.reason, resolved: false });
      }
      this.persistContradictions(mergedContra);
    } catch {
      // ponytail: best-effort — extraction failures just leave the local list unchanged.
    } finally {
      this.detecting.set(false);
    }
  }

  private persistFacts(facts: ProfileFact[]): void {
    this.facts.set(facts);
    this.saveToStorage(FACTS_KEY, facts);
  }

  private persistContradictions(items: ProfileContradiction[]): void {
    this.contradictions.set(items);
    this.saveToStorage(CONTRADICTIONS_KEY, items);
  }

  private loadFromStorage<T>(key: string): T[] {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  }

  private saveToStorage(key: string, value: unknown): void {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {}
  }

  private newId(): string {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  }
}
