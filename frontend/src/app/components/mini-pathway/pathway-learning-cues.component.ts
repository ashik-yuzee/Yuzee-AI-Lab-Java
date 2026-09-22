import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { learningTypesIn, LEARNING_TONE_LEGEND, LearningType } from './learning-types';

/**
 * Port of the old React app's components/PathwayLearningCues.tsx `PathwayLearningCues` export —
 * small inline `<details>` badges for qualification/learning-type keywords detected in the
 * report text (Certificate, Diploma, Bachelor, Masters, apprenticeship, ...), each tagged with
 * its AQF level.
 */
@Component({
  selector: 'app-pathway-learning-cues',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (types().length) {
      <div class="pathway-learning-cues" aria-label="Learning types mentioned">
        @for (type of types(); track type.id) {
          <details class="pathway-learning-cue" [attr.data-learning-tone]="type.tone">
            <summary>
              <span class="type-dot" aria-hidden="true"></span>{{ type.label }}
              <span class="level">{{ type.level }}</span>
            </summary>
            <div class="type-explanation">
              <p>{{ type.explanation }}</p>
              <p><strong>What to check:</strong> {{ type.check }}</p>
            </div>
          </details>
        }
      </div>
    }
  `,
  styles: [`
    .pathway-learning-cues { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
    .pathway-learning-cue {
      border: 1px solid #e4e9f2; border-left: 3px solid #8a94a6; border-radius: 8px;
      background: #f8f9fc; padding: 4px 8px; font-size: 11px;
    }
    .pathway-learning-cue summary { cursor: pointer; color: #27364a; list-style: none; display: flex; align-items: center; gap: 5px; }
    .pathway-learning-cue summary::-webkit-details-marker { display: none; }
    .pathway-learning-cue .level { color: #8a94a6; font-weight: 600; }
    .pathway-learning-cue .type-explanation { margin-top: 6px; color: #4b5768; }
    .pathway-learning-cue .type-explanation p { margin: 0 0 4px; }
    .type-dot { width: 7px; height: 7px; border-radius: 50%; background: #8a94a6; flex-shrink: 0; display: inline-block; }

    [data-learning-tone="level3"], [data-learning-tone="level1"], [data-learning-tone="level2"], [data-learning-tone="level4"] { border-left-color: #4c9aff; }
    [data-learning-tone="level5"] { border-left-color: #2d9c9c; }
    [data-learning-tone="level6"], [data-learning-tone="crosslevel"] { border-left-color: #2d8ac9; }
    [data-learning-tone="level7"] { border-left-color: var(--accent, #7957c6); }
    [data-learning-tone="level8"] { border-left-color: #a15fc9; }
    [data-learning-tone="level9"] { border-left-color: #c9598f; }
    [data-learning-tone="level10"] { border-left-color: #c94c4c; }
    [data-learning-tone="short"] { border-left-color: #d69e2e; }
    [data-learning-tone="micro"] { border-left-color: #38a169; }
    [data-learning-tone="route"], [data-learning-tone="industry"], [data-learning-tone="unit"] { border-left-color: #718096; }

    [data-learning-tone="level3"] .type-dot, [data-learning-tone="level1"] .type-dot, [data-learning-tone="level2"] .type-dot, [data-learning-tone="level4"] .type-dot { background: #4c9aff; }
    [data-learning-tone="level5"] .type-dot { background: #2d9c9c; }
    [data-learning-tone="level6"] .type-dot, [data-learning-tone="crosslevel"] .type-dot { background: #2d8ac9; }
    [data-learning-tone="level7"] .type-dot { background: var(--accent, #7957c6); }
    [data-learning-tone="level8"] .type-dot { background: #a15fc9; }
    [data-learning-tone="level9"] .type-dot { background: #c9598f; }
    [data-learning-tone="level10"] .type-dot { background: #c94c4c; }
    [data-learning-tone="short"] .type-dot { background: #d69e2e; }
    [data-learning-tone="micro"] .type-dot { background: #38a169; }
    [data-learning-tone="route"] .type-dot, [data-learning-tone="industry"] .type-dot, [data-learning-tone="unit"] .type-dot { background: #718096; }
  `]
})
export class PathwayLearningCuesComponent {
  @Input() text = '';

  types(): LearningType[] {
    return learningTypesIn(this.text);
  }
}

/**
 * Port of PathwayLearningCues.tsx's `PathwayColourGuide` export — a static legend explaining
 * what the learning-type colours/tones mean. Purely informational; renders the same
 * AQF-level legend used by the cues above.
 */
@Component({
  selector: 'app-pathway-colour-guide',
  standalone: true,
  imports: [CommonModule],
  template: `
    <details class="pathway-colour-guide">
      <summary>Course colours and levels <span>How to read your pathway</span></summary>
      <p>Colours identify learning types. The AQF label tells you the qualification level; it does not rank course quality or tell you which option is best for you.</p>
      <div class="pathway-colour-key">
        @for (entry of legend; track entry.tone) {
          <span [attr.data-learning-tone]="entry.tone"><i class="type-dot" aria-hidden="true"></i>{{ entry.label }}</span>
        }
      </div>
      <p>Open a coloured label for an explanation. An unlabelled step needs no assumed level. You do not have to complete every AQF level; moving between courses depends on entry and credit rules.</p>
      <a href="https://www.aqf.edu.au/framework/aqf-qualifications" target="_blank" rel="noreferrer">About qualification levels &#8599;</a>
    </details>
  `,
  styles: [`
    .pathway-colour-guide { font-size: 11px; color: #4b5768; border: 1px solid #eef1f6; border-radius: 8px; padding: 8px 10px; margin: 10px 0; background: #f8f9fc; }
    .pathway-colour-guide summary { cursor: pointer; color: #27364a; font-weight: 600; list-style: none; }
    .pathway-colour-guide summary::-webkit-details-marker { display: none; }
    .pathway-colour-guide summary span { display: block; font-weight: 400; color: #8a94a6; font-size: 10px; }
    .pathway-colour-key { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
    .pathway-colour-key span { display: flex; align-items: center; gap: 5px; }
    .pathway-colour-key .type-dot { width: 7px; height: 7px; border-radius: 50%; background: #8a94a6; display: inline-block; }
    .pathway-colour-key [data-learning-tone="level3"] .type-dot { background: #4c9aff; }
    .pathway-colour-key [data-learning-tone="level5"] .type-dot { background: #2d9c9c; }
    .pathway-colour-key [data-learning-tone="level6"] .type-dot { background: #2d8ac9; }
    .pathway-colour-key [data-learning-tone="level7"] .type-dot { background: var(--accent, #7957c6); }
    .pathway-colour-key [data-learning-tone="level8"] .type-dot { background: #a15fc9; }
    .pathway-colour-key [data-learning-tone="level9"] .type-dot { background: #c9598f; }
    .pathway-colour-key [data-learning-tone="level10"] .type-dot { background: #c94c4c; }
    .pathway-colour-key [data-learning-tone="short"] .type-dot { background: #d69e2e; }
    .pathway-colour-key [data-learning-tone="micro"] .type-dot { background: #38a169; }
    .pathway-colour-key [data-learning-tone="route"] .type-dot { background: #718096; }
    .pathway-colour-guide a { color: var(--accent, #7957c6); }
  `]
})
export class PathwayColourGuideComponent {
  legend = LEARNING_TONE_LEGEND;
}
