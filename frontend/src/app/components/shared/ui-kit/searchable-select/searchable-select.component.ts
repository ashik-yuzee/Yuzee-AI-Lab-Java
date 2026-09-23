import { Component, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild, signal } from '@angular/core';
import { IconComponent } from '../../icon/icon.component';

export type SelectBadgeColor = 'blue' | 'emerald' | 'amber' | 'purple' | 'slate';

/** `icon` is the PascalCase lucide name (React passed the icon component itself). */
export interface SelectOption {
  value: string;
  label: string;
  description?: string;
  badge?: string;
  badgeColor?: SelectBadgeColor;
  group?: string;
  disabled?: boolean;
  icon?: string;
}

interface OptionGroup {
  groupName: string;
  items: SelectOption[];
}

/**
 * 1:1 port of AppleSelect.tsx (value/onChange → [value]/(valueChange)).
 * `popoverWidth` takes the original's Tailwind width tokens (w-72, w-80, w-84, sm:w-84, sm:w-96).
 * `footerNote` is text (the original accepted a ReactNode; no caller passes one).
 */
@Component({
  selector: 'app-searchable-select',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './searchable-select.component.html',
  styleUrl: './searchable-select.component.scss'
})
export class SearchableSelectComponent {
  @Input() id?: string;
  @Input() label?: string;
  @Input({ required: true }) value = '';
  @Input({ required: true }) options: SelectOption[] = [];
  @Input() placeholder = 'Select an option';
  @Input() leadingIcon?: string;
  @Input() compact = false;
  @Input() disabled = false;
  @Input() className = '';
  @Input() popoverWidth = 'w-84 sm:w-96';
  @Input() footerNote?: string;
  @Input() showSearchThreshold = 7;

  @Output() valueChange = new EventEmitter<string>();

  @ViewChild('searchInput') searchInputRef?: ElementRef<HTMLInputElement>;
  @ViewChild('triggerBtn') triggerBtnRef?: ElementRef<HTMLButtonElement>;

  isOpen = signal(false);
  searchQuery = signal('');
  // The original tracks this for Enter-to-select but never renders a highlight for it.
  private focusedIndex = -1;

  constructor(private elRef: ElementRef<HTMLElement>) {}

  // Close when clicking outside
  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(e: MouseEvent): void {
    if (this.isOpen() && !this.elRef.nativeElement.contains(e.target as Node)) this.close();
  }

  get popoverClasses(): string {
    return this.popoverWidth.replace(/:/g, '-');
  }

  get selectedOption(): SelectOption | undefined {
    return this.options.find(o => o.value === this.value);
  }

  get filteredOptions(): SelectOption[] {
    if (!this.searchQuery().trim()) return this.options;
    const q = this.searchQuery().toLowerCase();
    return this.options.filter(o =>
      o.label.toLowerCase().includes(q) ||
      (!!o.description && o.description.toLowerCase().includes(q)) ||
      (!!o.group && o.group.toLowerCase().includes(q))
    );
  }

  get groupedOptions(): OptionGroup[] {
    const groups: OptionGroup[] = [];
    for (const opt of this.filteredOptions) {
      const groupName = opt.group || '';
      let g = groups.find(x => x.groupName === groupName);
      if (!g) {
        g = { groupName, items: [] };
        groups.push(g);
      }
      g.items.push(opt);
    }
    return groups;
  }

  toggleOpen(): void {
    if (this.disabled) return;
    this.setOpen(!this.isOpen());
  }

  onSearchInput(e: Event): void {
    this.searchQuery.set((e.target as HTMLInputElement).value);
  }

  private setOpen(open: boolean): void {
    this.isOpen.set(open);
    // Focus search input when opening
    if (open && this.options.length >= this.showSearchThreshold) {
      setTimeout(() => this.searchInputRef?.nativeElement.focus(), 50);
    }
  }

  private close(): void {
    this.isOpen.set(false);
    this.searchQuery.set('');
  }

  selectOption(opt: SelectOption): void {
    if (opt.disabled) return;
    this.valueChange.emit(opt.value);
    this.close();
    this.triggerBtnRef?.nativeElement.focus();
  }

  // Keyboard navigation
  onKeydown(e: KeyboardEvent): void {
    if (!this.isOpen()) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
        e.preventDefault();
        this.setOpen(true);
      }
      return;
    }

    const selectable = this.filteredOptions.filter(o => !o.disabled);
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close();
      this.triggerBtnRef?.nativeElement.focus();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (selectable.length === 0) return;
      this.focusedIndex = (this.focusedIndex + 1) % selectable.length;
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (selectable.length === 0) return;
      this.focusedIndex = (this.focusedIndex - 1 + selectable.length) % selectable.length;
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (this.focusedIndex >= 0 && this.focusedIndex < selectable.length) {
        this.selectOption(selectable[this.focusedIndex]);
      }
    }
  }
}
