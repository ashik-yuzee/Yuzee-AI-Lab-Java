import { Component, Input } from '@angular/core';
import { learningTypesIn, LearningType } from './learning-types';

/** Port of PathwayLearningCues.tsx `PathwayLearningCues`. Styled by the global mini-pathway.css. */
@Component({
  selector: 'app-pathway-learning-cues',
  standalone: true,
  template: `
    @if (types.length) {
      <div class="pathway-learning-cues" aria-label="Learning types mentioned">
        @for (type of types; track type.id) {
          <details class="pathway-learning-cue" [attr.data-learning-tone]="type.tone">
            <summary><span class="pathway-type-dot" aria-hidden="true"></span>{{ type.label }} <span class="pathway-level">{{ type.level }}</span></summary>
            <div class="pathway-type-explanation"><p>{{ type.explanation }}</p><p><strong>What to check:</strong> {{ type.check }}</p></div>
          </details>
        }
      </div>
    }
  `,
  styles: [':host{display:contents}'],
})
export class PathwayLearningCuesComponent {
  types: LearningType[] = [];
  @Input() set text(value: string) { this.types = learningTypesIn(value ?? ''); }
}

const COLOUR_KEY: [string, string][] = [
  ['level3', 'Certificates · AQF 1–4'], ['level5', 'Diploma · AQF 5'], ['level6', 'Advanced Diploma / Associate Degree · AQF 6'],
  ['level7', 'Degrees · AQF 7'], ['level8', 'Honours / graduate study · AQF 8'], ['level9', 'Masters · AQF 9'],
  ['level10', 'Doctoral study · AQF 10'], ['short', 'Short courses · check recognition'], ['micro', 'Microcredentials · level varies'],
  ['route', 'Employment and training routes'],
];

/** Port of PathwayLearningCues.tsx `PathwayColourGuide`. */
@Component({
  selector: 'app-pathway-colour-guide',
  standalone: true,
  template: `
    <details class="pathway-colour-guide">
      <summary>Course colours and levels <span>How to read your pathway</span></summary>
      <p>Colours identify learning types. The AQF label tells you the qualification level; it does not rank course quality or tell you which option is best for you.</p>
      <div class="pathway-colour-key">
        @for (entry of key; track entry[0]) {
          <span [attr.data-learning-tone]="entry[0]"><i class="pathway-type-dot" aria-hidden="true"></i>{{ entry[1] }}</span>
        }
      </div>
      <p>Open a coloured label for an explanation. Labels describe names mentioned in the answer; they do not verify that a course is offered or accredited. An unlabelled step needs no assumed level.</p>
      <p>You do not have to complete every AQF level. Moving between courses depends on entry and credit rules. “Completed” and “Check this” describe progress or attention, separately from course type.</p>
      <a href="https://www.aqf.edu.au/framework/aqf-qualifications" target="_blank" rel="noreferrer">About qualification levels ↗</a>
    </details>
  `,
  styles: [':host{display:contents}'],
})
export class PathwayColourGuideComponent {
  readonly key = COLOUR_KEY;
}
