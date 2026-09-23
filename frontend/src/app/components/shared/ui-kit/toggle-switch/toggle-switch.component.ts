import { AfterViewChecked, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';

/** 1:1 port of AppleToggle.tsx (checked/onChange → [checked]/(checkedChange)). */
@Component({
  selector: 'app-toggle-switch',
  standalone: true,
  templateUrl: './toggle-switch.component.html',
  styleUrl: './toggle-switch.component.scss'
})
export class ToggleSwitchComponent implements AfterViewChecked {
  @ViewChild('input', { static: true }) input!: ElementRef<HTMLInputElement>;
  @Input() id?: string;
  @Input() checked = false;
  @Input() disabled = false;
  @Input() label?: string;
  @Input() description?: string;

  @Output() checkedChange = new EventEmitter<boolean>();

  onToggle(e: Event): void {
    this.checkedChange.emit((e.target as HTMLInputElement).checked);
  }

  /** Controlled like React's <input checked>: the box reverts to `checked` until the parent changes it. */
  ngAfterViewChecked(): void {
    const el = this.input.nativeElement;
    if (el.checked !== this.checked) el.checked = this.checked;
  }
}
