import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/** ponytail: React's `icon?: ComponentType` has no Angular equivalent without a whole icon
 * component registry — simplified to a CSS class name (e.g. a bootstrap-icons class) that
 * gets rendered as `<i class="{{icon}}">`. Swap for a richer icon input if a real icon set
 * is adopted later. */
export interface SegmentOption {
  id: string;
  label: string;
  badge?: string;
  icon?: string;
}

let nextControlId = 0;

/** Port of AppleSegmentedControl.tsx as a Bootstrap button-group (.btn-group + .btn-check),
 * restyled with custom SCSS so it reads as a segmented control, not stock Bootstrap buttons. */
@Component({
  selector: 'app-segmented-control',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './segmented-control.component.html',
  styleUrl: './segmented-control.component.scss'
})
export class SegmentedControlComponent {
  @Input({ required: true }) options: SegmentOption[] = [];
  @Input({ required: true }) value = '';
  @Input() size: 'sm' | 'md' = 'md';

  @Output() valueChange = new EventEmitter<string>();

  readonly controlId = `segctl-${nextControlId++}`;

  select(id: string): void {
    if (id !== this.value) this.valueChange.emit(id);
  }
}
