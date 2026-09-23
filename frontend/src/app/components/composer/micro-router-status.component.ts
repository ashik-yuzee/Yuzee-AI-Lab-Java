import { Component, OnInit } from '@angular/core';
import { RoutingService } from '../../services/routing.service';

/** Port of MicroRouterStatus.tsx. */
@Component({
  selector: 'app-micro-router-status',
  standalone: true,
  template: `<span role="status" class="router-status" [attr.data-router-status]="status()">{{ status() === 'ready' ? 'Oala assist ready' : status() === 'unavailable' ? 'Oala can still answer' : 'Preparing Oala assist' }}@if (status() === 'unavailable') {<button type="button" class="router-retry" (click)="routing.startWarmup()">Retry</button>}</span>`,
  // text-xs text-slate-500 / ml-2 underline
  styles: [`
    .router-status { font-size: var(--text-xs); line-height: var(--tw-leading, var(--text-xs--line-height)); color: var(--color-slate-500); }
    .router-retry { margin-left: calc(var(--spacing) * 2); text-decoration-line: underline; }
  `]
})
export class MicroRouterStatusComponent implements OnInit {
  constructor(readonly routing: RoutingService) {}

  status(): string {
    const s = this.routing.status();
    return s;
  }

  ngOnInit(): void { this.routing.startWarmup(); }
}
