import { signal, computed, Signal, WritableSignal } from '@angular/core';

const DEFAULT_WIDTH_KEY = 'yuzee-mini-pathway-width';
const MIN_WIDTH = 320;
const DEFAULT_WIDTH = 440;
const DOCK_RESERVE = 360; // leave room to read the chat when the panel is docked

/**
 * Port of the old React app's miniPathway/useDrawerResize.ts hook. A plain instantiable class
 * (one per component instance) rather than a directive or a full hook re-implementation —
 * the panel just needs a width signal plus a few pointer/keyboard handlers wired directly onto
 * its own resize-handle element in the template.
 *
 * Usage: `resize = new DrawerResizeController();` then bind `resize.width()` to the panel's
 * width style, and `(pointerdown)="resize.onPointerDown($event)"` etc. on the drag handle.
 */
export class DrawerResizeController {
  private readonly widthKey: string;
  private dragging = false;
  private dragStartX = 0;
  private dragStartWidth = 0;

  private preferred: WritableSignal<number | null>;
  resizing = signal(false);

  constructor(widthKey: string = DEFAULT_WIDTH_KEY) {
    this.widthKey = widthKey;
    this.preferred = signal<number | null>(this.readStored());
  }

  width: Signal<number> = computed(() => this.clamp(this.preferred() ?? DEFAULT_WIDTH));

  private maxWidth(): number {
    const viewport = typeof window !== 'undefined' ? window.innerWidth : 1200;
    return Math.max(MIN_WIDTH, viewport - DOCK_RESERVE);
  }

  private clamp(value: number): number {
    return Math.round(Math.max(MIN_WIDTH, Math.min(this.maxWidth(), value)));
  }

  private readStored(): number | null {
    try {
      const value = Number(localStorage.getItem(this.widthKey));
      return Number.isFinite(value) && value >= MIN_WIDTH ? value : null;
    } catch {
      return null;
    }
  }

  private store(value: number | null): void {
    try {
      if (value === null) localStorage.removeItem(this.widthKey);
      else localStorage.setItem(this.widthKey, String(value));
    } catch {
      /* Resizing still works if browser storage is unavailable. */
    }
  }

  onPointerDown(event: PointerEvent): void {
    if (event.button !== 0) return;
    event.preventDefault();
    const el = event.currentTarget as HTMLElement;
    el.setPointerCapture(event.pointerId);
    this.dragging = true;
    this.dragStartX = event.clientX;
    this.dragStartWidth = this.width();
    this.resizing.set(true);
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.dragging) return;
    const next = this.clamp(this.dragStartWidth + (this.dragStartX - event.clientX));
    this.preferred.set(next);
    this.store(next);
  }

  onPointerUp(event: PointerEvent): void {
    if (!this.dragging) return;
    this.dragging = false;
    this.resizing.set(false);
    const el = event.currentTarget as HTMLElement;
    if (el?.hasPointerCapture?.(event.pointerId)) el.releasePointerCapture(event.pointerId);
  }

  onKeyDown(event: KeyboardEvent): void {
    const step = event.shiftKey ? 80 : 20;
    const current = this.width();
    const next =
      event.key === 'ArrowLeft' ? current + step :
      event.key === 'ArrowRight' ? current - step :
      event.key === 'Home' ? MIN_WIDTH :
      event.key === 'End' ? this.maxWidth() :
      null;
    if (next === null) return;
    event.preventDefault();
    const clamped = this.clamp(next);
    this.preferred.set(clamped);
    this.store(clamped);
  }

  /** Double-click on the handle resets to the default width. */
  reset(): void {
    this.preferred.set(null);
    this.store(null);
  }
}
