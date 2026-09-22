import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RoutingService } from '../../../services/routing.service';

type RoutingStatus = 'idle' | 'loading' | 'ready' | 'error';

/**
 * Port of RouterModelSelector.tsx (yuzee-ai-token-lab/src/components/RouterModelSelector.tsx).
 *
 * The old component let users pick between multiple router models (RUNTIME_ROUTER_MODELS:
 * a local BGE-small embedder + a Cloudflare LLM fallback), persisted via
 * getRouterModel/setRouterModel (routing/models.ts + services/MicroToolRouter.ts).
 *
 * This app's RoutingService (frontend/src/app/services/routing.service.ts) does not carry that
 * concept forward: it always tries one hardcoded local embedding model first (internally
 * 'Xenova/bge-small-en-v1.5', matching the old default) and automatically falls back to a
 * server-side Cloudflare LLM call when the in-browser worker is unavailable — there is no
 * setRouterModel()/getRouterModel() equivalent to wire a picker to. Building a dropdown that
 * looks like it switches models, but calls nothing real, would be exactly the fake control this
 * port is meant to avoid. So instead this surfaces the real, read-only state RoutingService does
 * expose — which model is in play, whether it's ready — plus the one real action it exposes,
 * `startWarmup()`, as a retry button when the local model failed to load.
 */
@Component({
  selector: 'app-router-model-selector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './router-model-selector.component.html',
  styleUrl: './router-model-selector.component.scss'
})
export class RouterModelSelectorComponent {
  constructor(public routing: RoutingService) {}

  readonly statusText: Record<RoutingStatus, string> = {
    idle: 'Loads when guidance starts.',
    loading: 'Preparing local routing model…',
    ready: 'Ready.',
    error: 'Local model unavailable — falls back to server-side routing. Chat remains available.'
  };

  get status(): RoutingStatus {
    return this.routing.status();
  }

  retry(): void {
    this.routing.startWarmup();
  }
}
