import { AfterViewChecked, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';

/** 1:1 port of AppleSlider.tsx (value/onChange → [value]/(valueChange)). */
@Component({
  selector: 'app-range-slider',
  standalone: true,
  templateUrl: './range-slider.component.html',
  styleUrl: './range-slider.component.scss'
})
export class RangeSliderComponent implements AfterViewChecked {
  @ViewChild('input', { static: true }) input!: ElementRef<HTMLInputElement>;
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
    return `${this.value.toLocaleString()}${this.unit ? ` ${this.unit}` : ''}`;
  }

  onInput(e: Event): void {
    this.valueChange.emit(Number((e.target as HTMLInputElement).value));
  }

  /** Controlled like React's <input value>: the thumb snaps back to `value` until the parent changes it. */
  ngAfterViewChecked(): void {
    const el = this.input.nativeElement;
    if (Number(el.value) !== this.value) el.value = String(this.value);
  }
}
