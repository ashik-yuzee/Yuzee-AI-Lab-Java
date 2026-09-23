import { AfterViewInit, Component, Directive, ElementRef, EventEmitter, Output, computed, effect, isSignal, signal, untracked } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { ObjectivesService } from '../../../services/objectives.service';
import { UserContradiction, UserProfileFact } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

export type FactCategory = 'general' | 'like' | 'dislike';

const CATEGORY_CONFIG: Record<FactCategory, { label: string; color: string; icon: string }> = {
  general: { label: 'Facts', color: 'emerald', icon: 'User' },
  like: { label: 'Likes', color: 'sky', icon: 'Heart' },
  dislike: { label: 'Dislikes', color: 'rose', icon: 'ThumbsDown' },
};

/** React's autoFocus: focuses the input when it is mounted. */
@Directive({ selector: '[appAutofocus]', standalone: true })
class AutofocusDirective implements AfterViewInit {
  constructor(private el: ElementRef<HTMLInputElement>) {}
  ngAfterViewInit(): void { this.el.nativeElement.focus(); }
}

/**
 * 1:1 port of UserProfileModal.tsx. Always mounted (like the original), rendered while isProfileOpen. Facts, contradictions and
 * location live in TokenLabService (which persists them to the original's localStorage keys).
 */
@Component({
  selector: 'app-user-profile-modal',
  standalone: true,
  imports: [IconComponent, AutofocusDirective],
  templateUrl: './user-profile-modal.component.html',
  styleUrl: './user-profile-modal.component.scss'
})
export class UserProfileModalComponent {
  @Output() closed = new EventEmitter<void>();

  readonly categories: FactCategory[] = ['general', 'like', 'dislike'];
  readonly categoryConfig = CATEGORY_CONFIG;

  editingId = signal<string | null>(null);
  editingText = signal('');
  newFact = signal('');
  newCategory = signal<FactCategory>('general');
  locationDraft = signal('');
  activeTab = signal<'profile' | 'contradictions'>('profile');

  /**
   * The original declares FactList inside render, so every render of the modal remounts the fact lists (an open
   * edit input is recreated and its autoFocus takes focus again). The modal renders on any change to its own
   * state or to the TokenLab context, and via AuthedApp on any change to the Objective context. The template keys
   * the fact lists on this value, which is a new object whenever any of those signals changes.
   */
  readonly renderKey = computed(() => {
    this.editingId(); this.editingText(); this.newFact(); this.newCategory(); this.locationDraft(); this.activeTab();
    for (const state of [this.lab, this.objectives]) for (const value of Object.values(state)) if (isSignal(value)) value();
    return {};
  });

  constructor(public lab: TokenLabService, private objectives: ObjectivesService) {
    // Original: useState(userLocation) + useEffect(() => setLocationDraft(userLocation), [userLocation]).
    effect(() => {
      const loc = this.lab.userLocation();
      untracked(() => this.locationDraft.set(loc));
    }, { allowSignalWrites: true });
  }

  get userProfile() { return this.lab.userProfile; }
  get userContradictions() { return this.lab.userContradictions; }

  close(): void {
    this.lab.isProfileOpen.set(false);
    this.closed.emit();
  }

  inputValue(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  factsByCategory(cat: FactCategory): UserProfileFact[] {
    return this.userProfile().filter(f => (f.category || 'general') === cat);
  }

  unresolvedCount(): number {
    return this.userContradictions().filter(c => !c.resolved).length;
  }

  hasResolved(): boolean {
    return this.userContradictions().some(c => c.resolved);
  }

  saveFacts(facts: UserProfileFact[]): void {
    this.userProfile.set(facts);
  }

  addFact(): void {
    const text = this.newFact().trim();
    if (!text) return;
    this.saveFacts([...this.userProfile(), { id: `fact-${Date.now()}`, text, category: this.newCategory(), addedAt: Date.now() }]);
    this.newFact.set('');
  }

  deleteFact(id: string): void {
    this.saveFacts(this.userProfile().filter(f => f.id !== id));
  }

  startEdit(fact: UserProfileFact): void {
    this.editingId.set(fact.id);
    this.editingText.set(fact.text);
  }

  onEditKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter') this.saveEdit();
    if (e.key === 'Escape') this.editingId.set(null);
  }

  saveEdit(): void {
    const text = this.editingText().trim();
    const id = this.editingId();
    if (!text || !id) { this.editingId.set(null); return; }
    this.saveFacts(this.userProfile().map(f => f.id === id ? { ...f, text } : f));
    this.editingId.set(null);
  }

  saveLocation(): void {
    this.lab.setUserLocation(this.locationDraft());
  }

  resolveContradiction(id: string): void {
    this.saveContradictions(this.userContradictions().map(c => c.id === id ? { ...c, resolved: true } : c));
  }

  dismissContradiction(id: string): void {
    this.saveContradictions(this.userContradictions().filter(c => c.id !== id));
  }

  clearResolved(): void {
    this.saveContradictions(this.userContradictions().filter(c => !c.resolved));
  }

  private saveContradictions(items: UserContradiction[]): void {
    this.userContradictions.set(items);
  }
}
