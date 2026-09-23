import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { TokenLabService } from '../../../services/token-lab.service';
import { ContextBreakdown, ContextSectionDetail, ExcludedSectionDetail } from '../../../models/types';
import { IconComponent } from '../../shared/icon/icon.component';

/** 1:1 port of ContextInspectorModal.tsx — reads TokenLabService.activeTurnTelemetry.contextMetrics. */
@Component({
  selector: 'app-context-inspector-modal',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './context-inspector-modal.component.html',
  styleUrl: './context-inspector-modal.component.scss'
})
export class ContextInspectorModalComponent {
  @Input() open = false;

  @Output() closed = new EventEmitter<void>();

  activeTab = signal<'included' | 'excluded'>('included');

  constructor(private lab: TokenLabService) {}

  get context(): ContextBreakdown | null {
    return this.lab.activeTurnTelemetry()?.contextMetrics ?? null;
  }

  get included(): ContextSectionDetail[] {
    const v = this.context?.includedSections;
    return Array.isArray(v) ? v : [];
  }

  get excluded(): ExcludedSectionDetail[] {
    const v = this.context?.excludedSections;
    return Array.isArray(v) ? v : [];
  }

  close(): void {
    this.lab.isContextInspectorOpen.set(false);
    this.closed.emit();
  }
}
