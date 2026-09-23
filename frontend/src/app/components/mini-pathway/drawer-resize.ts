import { computed, signal } from '@angular/core';

const WIDTH_KEY = 'yuzee-mini-pathway-width';

/**
 * Port of the original miniPathway/useDrawerResize.ts. Create one per component; call `reset()`
 * whenever open/expanded change (the hook's effect), `destroy()` on teardown.
 */
export class DrawerResizeController {
  private viewport = signal(window.innerWidth);
  private preferred = signal<number | null>(null);
  readonly resizing = signal(false);
  private drag: { id: number; x: number; width: number } | null = null;
  private onWindowResize = () => { this.viewport.set(window.innerWidth); this.cancel(); };

  // Leave room to read the chat when docked; small screens use the existing overlay.
  readonly maximum = computed(() => Math.max(1, this.viewport() >= this.dockBreakpoint ? this.viewport() - 360 : this.viewport()));
  readonly minimum = computed(() => Math.min(320, this.maximum()));
  readonly width = computed(() => {
    const v = this.viewport();
    const defaultWidth = v >= 1600 ? 490 : v < this.dockBreakpoint ? 480 : 440;
    return this.clamp(this.preferred() ?? defaultWidth);
  });

  constructor(private storageKey = WIDTH_KEY, private dockBreakpoint = 1200) {
    try { const value = Number(localStorage.getItem(storageKey)); this.preferred.set(Number.isFinite(value) && value >= 320 ? value : null); } catch { /* storage unavailable */ }
    this.setPreferred(this.preferred()); // the hook's storage effect also runs on mount (removes an invalid saved width)
    window.addEventListener('resize', this.onWindowResize);
  }

  destroy(): void { window.removeEventListener('resize', this.onWindowResize); }

  private clamp(value: number): number { return Math.round(Math.max(this.minimum(), Math.min(this.maximum(), value))); }

  private setPreferred(value: number | null): void {
    this.preferred.set(value);
    try { if (value === null) localStorage.removeItem(this.storageKey); else localStorage.setItem(this.storageKey, String(value)); } catch { /* Resizing still works if browser storage is unavailable. */ }
  }

  /** The hook resets any drag when open, expanded or the viewport change. */
  cancel(): void { this.drag = null; this.resizing.set(false); }

  private finish(event: PointerEvent): void {
    if (this.drag?.id !== event.pointerId) return;
    this.drag = null; this.resizing.set(false);
    const el = event.currentTarget as HTMLElement;
    if (el.hasPointerCapture(event.pointerId)) el.releasePointerCapture(event.pointerId);
  }

  onPointerDown(event: PointerEvent, expanded: boolean): void {
    if (event.button !== 0 || expanded) return;
    const el = event.currentTarget as HTMLElement;
    event.preventDefault(); el.focus(); el.setPointerCapture(event.pointerId);
    this.drag = { id: event.pointerId, x: event.clientX, width: this.width() }; this.resizing.set(true);
  }
  onPointerMove(event: PointerEvent): void {
    if (this.drag?.id !== event.pointerId) return;
    this.setPreferred(this.clamp(this.drag.width + this.drag.x - event.clientX));
  }
  onPointerUp(event: PointerEvent): void { this.finish(event); }
  onPointerCancel(event: PointerEvent): void { this.finish(event); }
  onLostPointerCapture(): void { this.cancel(); }
  onKeyDown(event: KeyboardEvent): void {
    const step = event.shiftKey ? 80 : 20, width = this.width();
    const next = event.key === 'ArrowLeft' ? width + step : event.key === 'ArrowRight' ? width - step : event.key === 'Home' ? this.minimum() : event.key === 'End' ? this.maximum() : null;
    if (next === null) return; event.preventDefault(); this.setPreferred(this.clamp(next));
  }
  onDoubleClick(): void { this.setPreferred(null); }
}
