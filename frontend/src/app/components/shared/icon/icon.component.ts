import { ChangeDetectionStrategy, Component, HostBinding, Input, OnChanges, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { LUCIDE_ICONS } from './lucide-icons';

/**
 * Renders the same SVG lucide-react renders for `<Name />`: 24x24 viewBox, stroke=currentColor,
 * round caps/joins. Size it from the parent's SCSS by targeting this host element (like the
 * original's Tailwind `w-4 h-4` on the svg); `color` on the host drives the stroke.
 */
@Component({
  selector: 'app-icon',
  standalone: true,
  template: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" [attr.fill]="fill" stroke="currentColor"
    [attr.stroke-width]="strokeWidth" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"
    [innerHTML]="markup"></svg>`,
  styles: [`:host{display:block;width:24px;height:24px}svg{display:block;width:100%;height:100%}`],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class IconComponent implements OnChanges {
  @Input({ required: true }) name!: string;
  @Input() size?: number;
  @Input() strokeWidth: number | string = 2;
  @Input() fill = 'none';

  private sanitizer = inject(DomSanitizer);
  markup: SafeHtml = '';

  @HostBinding('style.width.px') get w() { return this.size; }
  @HostBinding('style.height.px') get h() { return this.size; }

  ngOnChanges(): void {
    const nodes = LUCIDE_ICONS[this.name] ?? [];
    const html = nodes.map(([tag, attrs]) =>
      `<${tag} ${Object.entries(attrs).map(([k, v]) => `${k}="${v}"`).join(' ')}></${tag}>`).join('');
    // Static, build-time icon geometry — never user input.
    this.markup = this.sanitizer.bypassSecurityTrustHtml(html);
  }
}
