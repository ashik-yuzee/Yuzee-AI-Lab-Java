import { Component, ElementRef, HostListener, Input, OnChanges, Output, EventEmitter, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Port of AppleConfirmDialog.tsx as a reusable Bootstrap-modal-shaped dialog.
 * Deliberately built from plain markup + custom SCSS (no Ionic, no bootstrap.js
 * Modal API) so visibility is driven entirely by the `isOpen` input, matching
 * the original's own hand-rolled overlay/focus/escape behavior.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [CommonModule],
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

  ngOnChanges(): void {
    if (this.isOpen) {
      setTimeout(() => this.cancelBtnRef?.nativeElement.focus(), 50);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.isOpen) this.cancelled.emit();
  }

  onConfirm(): void { this.confirmed.emit(); }
  onCancel(): void { this.cancelled.emit(); }
}
