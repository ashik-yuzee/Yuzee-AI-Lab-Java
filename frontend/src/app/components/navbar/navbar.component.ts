import { Component, EventEmitter, Output, signal } from '@angular/core';
import { TokenLabService } from '../../services/token-lab.service';
import { RoutingService } from '../../services/routing.service';
import { IconComponent } from '../shared/icon/icon.component';
import { SearchableSelectComponent, SelectOption, SelectBadgeColor } from '../shared/ui-kit/searchable-select/searchable-select.component';
import { ModelCapabilityInfo, OptimizationMode } from '../../models/types';
import { GEMINI_MODELS, RUNTIME_ROUTER_MODELS, ROUTER_MODEL_KEY, DEFAULT_ROUTER_MODEL } from './models';

const MODE_OPTIONS: SelectOption[] = [
  { value: 'MICRO_PROMPT', label: 'Oala assist (Preview)', description: 'Type @Oala in your message for Yuzee service guidance. Local routing runs only when addressed.', badge: 'Preview', badgeColor: 'amber' },
  { value: 'VANILLA', label: 'Vanilla (AI Studio)', description: 'No optimisation, no compaction, 8192 token output cap', badge: 'Default', badgeColor: 'purple' },
  { value: 'AUTO', label: 'Auto (Balanced)', description: 'Dynamic budget, prefix caching, adaptive thinking', badge: 'Adaptive', badgeColor: 'blue' },
  { value: 'SAVE_TOKENS', label: 'Save Tokens (Aggressive)', description: 'Compaction after 2 turns, 1,000 token budget', badge: 'Economical', badgeColor: 'emerald' },
  { value: 'FULL_CONTEXT', label: 'Full Context (Baseline)', description: 'Sends entire history without compression', badge: 'Baseline', badgeColor: 'slate' },
];

const ROUTER_OPTIONS: SelectOption[] = RUNTIME_ROUTER_MODELS.map(m => ({
  value: m.id,
  label: m.label,
  description: m.description,
  badge: m.id.startsWith('cloudflare/') ? 'Server' : 'Local',
  badgeColor: m.id.startsWith('cloudflare/') ? 'amber' : 'emerald',
}));

/** Port of Navbar.tsx. */
@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [IconComponent, SearchableSelectComponent],
  templateUrl: './navbar.component.html',
  styleUrl: './navbar.component.scss'
})
export class NavbarComponent {
  @Output() openRenderer = new EventEmitter<void>();

  readonly modeOptions = MODE_OPTIONS;
  readonly routerModelOptions = ROUTER_OPTIONS;

  /** The original's localMode override; reset when the conversation changes. */
  private localMode: OptimizationMode | null = null;
  private localModeConvId: string | null | undefined = undefined;
  routerModelId = signal(this.readRouterModel());

  constructor(public lab: TokenLabService, private routing: RoutingService) {}

  get modelOptions(): SelectOption[] {
    const caps = this.lab.capabilities();
    const list: ModelCapabilityInfo[] = caps?.modelsList?.length ? caps.modelsList : GEMINI_MODELS;
    return list
      .filter(m => m.selectable !== false && m.status !== 'retired')
      .map(m => {
        const group = m.categoryGroup || 'Current Models';
        let badge = m.badge;
        let badgeColor: SelectBadgeColor = 'blue';
        if (m.isDefault) { badge = 'Default'; badgeColor = 'emerald'; }
        else if (m.isRecommended) { badge = 'Recommended'; badgeColor = 'blue'; }
        else if (m.id === 'gemini-3.7-flash') { badge = 'Latest'; badgeColor = 'purple'; }
        else if (m.family === 'flash-lite') { badge = badge || 'Fast'; badgeColor = 'amber'; }
        else if (m.family === 'legacy') { badge = badge || 'Legacy'; badgeColor = 'slate'; }
        return { value: m.id, label: m.name, description: m.shortDescription || m.longDescription || '', group, badge, badgeColor };
      });
  }

  get modelValue(): string { return this.lab.currentConversation()?.model || this.lab.selectedModel(); }

  get displayMode(): string {
    const conv = this.lab.currentConversation();
    if (conv?.id !== this.localModeConvId) { this.localModeConvId = conv?.id; this.localMode = null; }
    return this.localMode ?? (conv?.mode || 'AUTO');
  }

  // Latest turn telemetry — show uncached input when cache is active
  get cachedTokens(): number { return this.lab.activeTurnTelemetry()?.usage?.cachedTokens || 0; }
  get inputTokens(): number {
    const usage = this.lab.activeTurnTelemetry()?.usage;
    const raw = usage?.inputTokens || 0;
    const uncached = usage?.uncachedInputTokens ?? (raw - this.cachedTokens);
    return this.cachedTokens > 0 ? uncached : raw;
  }
  get outputTokens(): number { return this.lab.activeTurnTelemetry()?.usage?.outputTokens || 0; }

  get hasAssistantMsg(): boolean { return (this.lab.currentConversation()?.messages || []).some(m => m.role === 'assistant'); }

  get hasUnresolvedContradictions(): boolean { return (this.lab.userContradictions() || []).some(c => !c.resolved); }

  toggleSidebar(): void { this.lab.isSidebarOpen.set(!this.lab.isSidebarOpen()); }

  togglePathway(): void {
    const open = this.lab.isWhiteboardOpen();
    this.lab.isWhiteboardOpen.set(!open);
    if (!open) this.lab.isTokenInspectorOpen.set(false);
  }

  toggleTelemetry(): void {
    const open = this.lab.isTokenInspectorOpen();
    this.lab.isTokenInspectorOpen.set(!open);
    if (!open) this.lab.isWhiteboardOpen.set(false);
  }

  openLab(): void {
    this.lab.isAdvancedLabOpen.set(true);
    this.lab.activeLabTab.set('context');
  }

  onModelChange(model: string): void { void this.lab.updateCurrentConversationSettings({ model }); }

  onModeChange(mode: string): void {
    this.localMode = mode as OptimizationMode;
    this.lab.applyOptimizationMode(mode as OptimizationMode);
  }

  onRouterModelChange(id: string): void {
    this.routing.setRouterModel(id);
    this.routerModelId.set(id);
  }

  private readRouterModel(): string {
    try { return localStorage.getItem(ROUTER_MODEL_KEY) ?? DEFAULT_ROUTER_MODEL; } catch { return DEFAULT_ROUTER_MODEL; }
  }
}
