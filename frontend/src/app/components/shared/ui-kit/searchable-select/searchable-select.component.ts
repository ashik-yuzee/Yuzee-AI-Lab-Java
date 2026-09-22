import {
  Component, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';

export type SelectBadgeColor = 'blue' | 'emerald' | 'amber' | 'purple' | 'slate';

/** ponytail: `icon?: ComponentType` from the React version is simplified to a CSS class
 * name, same trade-off as SegmentOption in segmented-control.component.ts. */
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

let nextSelectId = 0;

/**
 * Port of AppleSelect.tsx — a real custom dropdown (not a wrapped <select>): optional search
 * box once options.length >= showSearchThreshold, grouped options, keyboard nav (Up/Down/
 * Enter/Escape), click-outside-to-close, badges. footerNote is plain text here (React's
 * ReactNode footerNote has no direct Angular equivalent without a TemplateRef — add one if a
 * consumer needs richer footer content than a string).
 */
@Component({
  selector: 'app-searchable-select',
  standalone: true,
  imports: [CommonModule],
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
  @Input() popoverWidthPx = 340;
  @Input() footerNote?: string;
  @Input() showSearchThreshold = 7;

  @Output() valueChange = new EventEmitter<string>();

  @ViewChild('searchInput') searchInputRef?: ElementRef<HTMLInputElement>;
  @ViewChild('triggerBtn') triggerBtnRef?: ElementRef<HTMLButtonElement>;

  readonly selectId = `apple-select-${nextSelectId++}`;

  isOpen = signal(false);
  searchQuery = signal('');
  focusedIndex = signal(-1);

  constructor(private elRef: ElementRef<HTMLElement>) {}

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(e: MouseEvent): void {
    if (this.isOpen() && !this.elRef.nativeElement.contains(e.target as Node)) {
      this.close();
    }
  }

  get selectedOption(): SelectOption | undefined {
    return this.options.find(o => o.value === this.value);
  }

  get showSearch(): boolean {
    return this.options.length >= this.showSearchThreshold;
  }

  get filteredOptions(): SelectOption[] {
    const q = this.searchQuery().trim().toLowerCase();
    if (!q) return this.options;
    return this.options.filter(o =>
      o.label.toLowerCase().includes(q) ||
      !!o.description?.toLowerCase().includes(q) ||
      !!o.group?.toLowerCase().includes(q)
    );
  }

  get groupedOptions(): OptionGroup[] {
    const groups: OptionGroup[] = [];
    for (const opt of this.filteredOptions) {
      const groupName = opt.group ?? '';
      let g = groups.find(x => x.groupName === groupName);
      if (!g) {
        g = { groupName, items: [] };
        groups.push(g);
      }
      g.items.push(opt);
    }
    return groups;
  }

  private get selectableOptions(): SelectOption[] {
    return this.filteredOptions.filter(o => !o.disabled);
  }

  focusedValue(): string | undefined {
    const selectable = this.selectableOptions;
    const idx = this.focusedIndex();
    return idx >= 0 && idx < selectable.length ? selectable[idx].value : undefined;
  }

  toggleOpen(): void {
    if (this.disabled) return;
    this.isOpen.update(v => !v);
    if (this.isOpen()) {
      this.focusedIndex.set(-1);
      if (this.showSearch) {
        setTimeout(() => this.searchInputRef?.nativeElement.focus(), 50);
      }
    }
  }

  close(): void {
    this.isOpen.set(false);
    this.searchQuery.set('');
  }

  selectOption(opt: SelectOption): void {
    if (opt.disabled) return;
    this.valueChange.emit(opt.value);
    this.close();
    setTimeout(() => this.triggerBtnRef?.nativeElement.focus());
  }

  onSearchInput(e: Event): void {
    this.searchQuery.set((e.target as HTMLInputElement).value);
    this.focusedIndex.set(-1);
  }

  onKeydown(e: KeyboardEvent): void {
    if (!this.isOpen()) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
        e.preventDefault();
        this.toggleOpen();
      }
      return;
    }

    const selectable = this.selectableOptions;
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close();
      this.triggerBtnRef?.nativeElement.focus();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!selectable.length) return;
      this.focusedIndex.update(i => (i + 1) % selectable.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (!selectable.length) return;
      this.focusedIndex.update(i => (i - 1 + selectable.length) % selectable.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const idx = this.focusedIndex();
      if (idx >= 0 && idx < selectable.length) this.selectOption(selectable[idx]);
    }
  }

  badgeClass(color?: SelectBadgeColor): string {
    switch (color) {
      case 'emerald': return 'badge-emerald';
      case 'amber': return 'badge-amber';
      case 'purple': return 'badge-purple';
      case 'slate': return 'badge-slate';
      case 'blue':
      default: return 'badge-blue';
    }
  }
}
