import { Component, Input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { IconComponent } from '../../shared/icon/icon.component';
import { PathwayLearningCuesComponent } from '../../mini-pathway/pathway-learning-cues.component';
import { learningToneIn } from '../protocol-presentation';
import type { YuzeeContentBlock } from '../../../models/types';

const STATES: Record<string, { label: string; tone: string; icon: string }> = {
  current: { label: 'Start here', tone: 'blue', icon: 'Flag' },
  next: { label: 'Next step', tone: 'purple', icon: 'ArrowRight' },
  warning: { label: 'Check this', tone: 'amber', icon: 'TriangleAlert' },
  blocked: { label: 'Needs attention', tone: 'rose', icon: 'TriangleAlert' },
  complete: { label: 'Completed', tone: 'green', icon: 'Check' },
  positive: { label: 'Potential benefit', tone: 'neutral', icon: 'Info' },
  negative: { label: 'Consideration', tone: 'amber', icon: 'Info' },
};

interface GuidanceRow {
  id: string;
  value?: number;
  tone: string;
  title?: string;
  state?: { label: string; tone: string; icon: string };
  showIcon: boolean;
  cueText: string;
  learningTone?: string;
  sideLabel?: string;
  description?: string;
  extraValue?: string;
  sideText?: string;
}

/** Port of GuidanceList.tsx (styles: global guidance-* rules from guidance-list.css). */
@Component({
  selector: 'app-guidance-list',
  standalone: true,
  imports: [NgTemplateOutlet, IconComponent, PathwayLearningCuesComponent],
  templateUrl: './guidance-list.component.html',
  styleUrl: './guidance-list.component.scss'
})
export class GuidanceListComponent {
  @Input({ required: true }) block!: YuzeeContentBlock;
  @Input() pathwayLearningCues = false;

  get ordered(): boolean { return this.block.type === 'steps'; }

  get rows(): GuidanceRow[] {
    const ordered = this.ordered;
    const checkSection = /\b(what.*check|needs checking|still.*check|to confirm|to verify)\b/i.test(this.block.title);
    const items = Array.isArray(this.block.items) ? this.block.items : [];
    return items.map((item, index) => {
      const state = checkSection && item.status === 'warning' ? undefined : STATES[item.status];
      // Older responses can put the same ordinal in the title and value.
      // Keep it in the native list marker; leave measurements and other data intact.
      const numberedTitle = ordered ? item.title?.match(/^(?:(?:stage|step)\s+([1-9]\d*)\s*[:.\-–—]\s*|([1-9]\d*)[.)]\s+)(\S[\s\S]*)$/i) : null;
      const ordinal = numberedTitle ? Number(numberedTitle[1] || numberedTitle[2]) : index + 1;
      const repeatedValue = ordered && (
        (Boolean(numberedTitle) && item.value?.trim() === String(ordinal)) ||
        new RegExp(`^(?:stage|step)\\s+${ordinal}[.:]?$`, 'i').test(item.value?.trim() || '')
      );
      const value = repeatedValue ? '' : item.value;
      const cueText = [item.title, item.text, item.value].filter(Boolean).join(' ');
      return {
        id: item.id || String(index),
        value: numberedTitle ? ordinal : undefined,
        tone: state?.tone || 'neutral',
        title: item.title ? (numberedTitle ? numberedTitle[3] : item.title) : undefined,
        state,
        showIcon: ['warning', 'blocked'].includes(item.status),
        cueText,
        learningTone: this.pathwayLearningCues ? learningToneIn(cueText) : undefined,
        sideLabel: item.side_label,
        description: item.text || value || undefined,
        extraValue: item.text && value && value !== item.text ? value : undefined,
        sideText: item.side_text,
      };
    });
  }
}
