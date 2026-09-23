import { Component, effect, signal, untracked } from '@angular/core';
import { ApiService } from '../../../services/api.service';
import { AuthService } from '../../../services/auth.service';
import { TokenLabService, formatCost } from '../../../services/token-lab.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { RouterModelSelectorComponent } from '../../shared/router-model-selector/router-model-selector.component';

interface LifetimeStats {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  thinkingTokens: number;
  costUsd: number;
  whiteboard?: { calls: number; inputTokens: number; outputTokens: number; costUsd: number };
}

interface PromptData { content: string; hash: string; bytes: number; filename: string; filepath: string }

const ACCENT_PRESETS = [
  { name: 'Violet', value: '#8952ee' },
  { name: 'Blue', value: '#2f6fed' },
  { name: 'Teal', value: '#0d9488' },
  { name: 'Rose', value: '#e11d48' },
  { name: 'Orange', value: '#f97316' },
  { name: 'Slate', value: '#475569' },
];

const LAB_SHORTCUTS = [
  { tab: 'context', label: 'Memory Strategy', desc: 'Context budget & memory' },
  { tab: 'reasoning', label: 'Thinking Level', desc: 'Reasoning depth control' },
  { tab: 'prompt', label: 'System Prompt', desc: 'Instruction mode & API' },
  { tab: 'optimization', label: 'Optimization', desc: 'Token economics' },
  { tab: 'benchmark', label: 'Benchmark', desc: 'Strategy comparison' },
  { tab: 'analytics', label: 'Analytics', desc: 'Session usage charts' },
];

/**
 * 1:1 port of SettingsModal.tsx. Always mounted (as in the original), so its local state persists
 * between opens; lifetime stats and the master prompt are refetched each time it opens.
 */
@Component({
  selector: 'app-settings-modal',
  standalone: true,
  imports: [IconComponent, RouterModelSelectorComponent],
  templateUrl: './settings-modal.component.html',
  styleUrl: './settings-modal.component.scss'
})
export class SettingsModalComponent {
  readonly accentPresets = ACCENT_PRESETS;
  readonly labShortcuts = LAB_SHORTCUTS;
  readonly formatCost = formatCost;

  fontPref = signal<'system' | 'open-sans'>(this.read('oala-font') === 'open-sans' ? 'open-sans' : 'system');
  accent = signal(this.read('oala-accent') || '#8952ee');
  clearConfirm = signal(false);
  cleared = signal(false);
  lifetime = signal<LifetimeStats | null>(null);
  promptData = signal<PromptData | null>(null);
  promptOpen = signal(false);
  promptCopied = signal(false);

  constructor(private api: ApiService, private auth: AuthService, public lab: TokenLabService) {
    effect(() => {
      if (!this.lab.isSettingsOpen()) return;
      untracked(() => {
        this.api.get<LifetimeStats>('/tokens/lifetime-stats').subscribe({ next: s => this.lifetime.set(s), error: e => { if (!(e?.status >= 200 && e?.status < 300)) this.lifetime.set(null); } }); // fetchLifetimeStats() resolves null on failure; its un-awaited res.json() rejects (ignored) on a bad 2xx body
        // As the original: r.json() is stored whatever the status, so a JSON error body becomes the prompt data.
        const token = this.auth.token;
        fetch('/api/system-prompt', { headers: token ? { Authorization: `Bearer ${token}` } : {} }).then(r => r.json()).then(p => this.promptData.set(p)).catch(() => {});
      });
    });
  }

  get storageSize(): string {
    const b = this.lab.localStorageStats().bytes;
    return b < 1024 ? `${b} B` : b < 1048576 ? `${(b / 1024).toFixed(1)} KB` : `${(b / 1048576).toFixed(2)} MB`;
  }

  k(n: number): string {
    return (n / 1000).toFixed(1);
  }

  toggleFont(): void {
    const next = this.fontPref() === 'system' ? 'open-sans' : 'system';
    this.fontPref.set(next);
    try {
      if (next === 'open-sans') localStorage.setItem('oala-font', 'open-sans');
      else localStorage.removeItem('oala-font');
    } catch { /* preference just won't persist */ }
    document.documentElement.classList.toggle('font-open-sans', next === 'open-sans');
  }

  applyAccent(hex: string): void {
    this.accent.set(hex);
    document.documentElement.style.setProperty('--accent', hex);
    try { localStorage.setItem('oala-accent', hex); } catch { /* preference just won't persist */ }
  }

  onCustomAccent(e: Event): void {
    this.applyAccent((e.target as HTMLInputElement).value);
  }

  close(): void {
    this.lab.isSettingsOpen.set(false);
  }

  openLabTab(tab: string): void {
    this.lab.isSettingsOpen.set(false);
    this.lab.activeLabTab.set(tab);
    this.lab.isAdvancedLabOpen.set(true);
  }

  openExportModal(): void {
    this.lab.isSettingsOpen.set(false);
    this.lab.isExportOpen.set(true);
  }

  handleClear(): void {
    this.lab.clearLocalData();
    this.clearConfirm.set(false);
    this.cleared.set(true);
    setTimeout(() => this.cleared.set(false), 2000);
  }

  copyPrompt(): void {
    const data = this.promptData();
    if (!data) return;
    navigator.clipboard.writeText(data.content).then(() => {
      this.promptCopied.set(true);
      setTimeout(() => this.promptCopied.set(false), 2000);
    });
  }

  private read(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
  }
}
