import { Component, EventEmitter, Input, Output } from '@angular/core';
import { IconComponent } from '../../icon/icon.component';

/** `icon` is the PascalCase lucide name (React passed the icon component itself). */
export interface SegmentOption {
  id: string;
  label: string;
  badge?: string;
  icon?: string;
}

/** 1:1 port of AppleSegmentedControl.tsx (value/onChange → [value]/(valueChange)). */
@Component({
  selector: 'app-segmented-control',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './segmented-control.component.html',
  styleUrl: './segmented-control.component.scss'
})
export class SegmentedControlComponent {
  @Input({ required: true }) options: SegmentOption[] = [];
  @Input({ required: true }) value = '';
  @Input() size: 'sm' | 'md' = 'md';
  @Input() className = '';

  @Output() valueChange = new EventEmitter<string>();
}
