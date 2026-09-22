import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/** Port of AppleSlider.tsx — Bootstrap's native `.form-range` input plus the original's
 * label/value/min-max/helper text layout, restyled with custom SCSS. */
@Component({
  selector: 'app-range-slider',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './range-slider.component.html',
  styleUrl: './range-slider.component.scss'
})
export class RangeSliderComponent {
  @Input() id?: string;
  @Input({ required: true }) label!: string;
  @Input({ required: true }) value!: number;
  @Input({ required: true }) min!: number;
  @Input({ required: true }) max!: number;
  @Input() step = 1;
  @Input() unit = '';
  @Input() formatValue?: (value: number) => string;
  @Input() minLabel?: string;
  @Input() maxLabel?: string;
  @Input() helperText?: string;

  @Output() valueChange = new EventEmitter<number>();

  get displayValue(): string {
    if (this.formatValue) return this.formatValue(this.value);
    return this.value.toLocaleString() + (this.unit ? ` ${this.unit}` : '');
  }

  get minText(): string {
    return this.minLabel ?? `${this.min}${this.unit}`;
  }

  get maxText(): string {
    return this.maxLabel ?? `${this.max}${this.unit}`;
  }

  get hasFootRow(): boolean {
    return !!(this.minLabel || this.maxLabel || this.helperText);
  }

  onInput(e: Event): void {
    this.valueChange.emit(Number((e.target as HTMLInputElement).value));
  }
}
