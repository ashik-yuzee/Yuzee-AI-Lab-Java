import { AfterViewInit, Component, ElementRef, ViewChild, signal } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { IconComponent } from '../../shared/icon/icon.component';

const SESSION_KEY = 'yuzee_location_prompted';

/**
 * 1:1 port of LocationPromptModal.tsx. Like the original it hides itself once skipped/answered in
 * this browser session (sessionStorage) or when a location is already known.
 */
@Component({
  selector: 'app-location-prompt-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './location-prompt-modal.component.html',
  styleUrl: './location-prompt-modal.component.scss'
})
export class LocationPromptModalComponent implements AfterViewInit {
  @ViewChild('locationInput') inputRef?: ElementRef<HTMLInputElement>;

  visible = signal(false);
  input = signal('');
  error = signal('');

  constructor(private lab: TokenLabService) {
    let prompted = false;
    try { prompted = !!sessionStorage.getItem(SESSION_KEY); } catch { /* treat as not prompted */ }
    this.visible.set(!lab.userLocation() && !prompted);
  }

  ngAfterViewInit(): void {
    if (this.visible()) setTimeout(() => this.inputRef?.nativeElement.focus());
  }

  onInput(e: Event): void {
    this.input.set((e.target as HTMLInputElement).value);
    this.error.set('');
  }

  close(): void {
    try { sessionStorage.setItem(SESSION_KEY, '1'); } catch { /* session-only flag */ }
    this.visible.set(false);
  }

  save(e: Event): void {
    e.preventDefault();
    const value = this.input().trim();
    if (!value) {
      this.error.set('Type a town, city or postcode, or choose Skip for now.');
      this.inputRef?.nativeElement.focus();
      return;
    }
    this.lab.setUserLocation(value); // TokenLabService persists it to 'yuzee_user_location'
    this.close();
  }

  onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') this.close();
    if (e.key === 'Tab') {
      const els = Array.from((e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('input,button'));
      const first = els[0];
      const last = els[els.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
    }
  }
}
