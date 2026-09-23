import { Component, OnDestroy, signal } from '@angular/core';
import { RouterStatus, RoutingService, RUNTIME_ROUTER_MODELS } from '../../../services/routing.service';

let nextId = 0;

/** 1:1 port of RouterModelSelector.tsx. */
@Component({
  selector: 'app-router-model-selector',
  standalone: true,
  templateUrl: './router-model-selector.component.html',
  styleUrl: './router-model-selector.component.scss'
})
export class RouterModelSelectorComponent implements OnDestroy {
  readonly id = `router-model-${nextId++}`;
  readonly models: readonly { id: string; label: string; tokenBudget?: number; description: string }[] = RUNTIME_ROUTER_MODELS;
  selected = signal('');
  status = signal<RouterStatus>('idle');
  private readonly unsubscribe: () => void;

  constructor(private routing: RoutingService) {
    this.selected.set(routing.getRouterModel());
    this.status.set(routing.status());
    this.unsubscribe = routing.onRouterStatus(value => { this.status.set(value); this.selected.set(routing.getRouterModel()); });
  }

  ngOnDestroy(): void { this.unsubscribe(); }

  /** routerModel(): only the ONNX list is searched, so the Cloudflare entry falls back to BGE-small's text (as in the original). */
  get description(): string {
    return RUNTIME_ROUTER_MODELS[0].description;
  }

  get statusText(): string {
    const s = this.status();
    return s === 'ready' ? 'Ready'
      : s === 'loading' ? 'Preparing selected model…'
      : s === 'unavailable' ? 'Model unavailable. Chat remains available.'
      : 'Loads when guidance starts.';
  }

  onSelect(e: Event): void {
    const el = e.target as HTMLSelectElement;
    this.routing.setRouterModel(el.value);
    el.value = this.selected(); // controlled <select value>
  }

  retry(): void {
    this.routing.startWarmup();
  }
}
