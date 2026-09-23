import { Component, ElementRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { IconComponent } from '../icon/icon.component';

/** 1:1 port of AppleConfirmDialog.tsx (onConfirm/onCancel → (confirmed)/(cancelled)). */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss'
})
export class ConfirmDialogComponent implements OnChanges {
  @Input({ required: true }) isOpen = false;
  @Input({ required: true }) title = '';
  @Input() description?: string;
  @Input() message?: string;
  @Input() confirmLabel = 'Confirm';
  @Input() cancelLabel = 'Cancel';
  @Input() isDestructive = true;

  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  @ViewChild('cancelBtn') cancelBtnRef?: ElementRef<HTMLButtonElement>;

  get dialogText(): string { return this.description || this.message || ''; }

  ngOnChanges(changes: SimpleChanges): void {
    // Focus safe cancel button by default
    if (changes['isOpen'] && this.isOpen) {
      setTimeout(() => this.cancelBtnRef?.nativeElement.focus(), 50);
    }
  }

  @HostListener('window:keydown', ['$event'])
  onKeydown(e: KeyboardEvent): void {
    if (!this.isOpen) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      this.cancelled.emit();
    }
  }
}
