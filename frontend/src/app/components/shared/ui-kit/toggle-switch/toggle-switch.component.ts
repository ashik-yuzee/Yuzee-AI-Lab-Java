import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Port of AppleToggle.tsx. Plain @Input/@Output (not a ControlValueAccessor) since the
 * original is a simple checked/onChange pair, not a form-integrated control — this keeps
 * the API a 1:1 map of the React props.
 */
@Component({
  selector: 'app-toggle-switch',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './toggle-switch.component.html',
  styleUrl: './toggle-switch.component.scss'
})
export class ToggleSwitchComponent {
  @Input() id?: string;
  @Input() checked = false;
  @Input() disabled = false;
  @Input() label?: string;
  @Input() description?: string;

  @Output() checkedChange = new EventEmitter<boolean>();

  onToggle(e: Event): void {
    if (this.disabled) return;
    this.checkedChange.emit((e.target as HTMLInputElement).checked);
  }
}
