/**
 * Ports of the original app's src/ux/responsePresentation.ts and src/components/readableMarkdown.ts,
 * plus a small GFM renderer standing in for react-markdown + remark-gfm (no new npm deps allowed).
 */
import { validateProtocol } from '../components/protocol-renderer/protocol-validator';
import type { YuzeeResponseV13 } from '../models/types';

export function acceptedResponse(value: unknown): YuzeeResponseV13 | null {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    return validateProtocol(parsed).protocolAccepted ? parsed as YuzeeResponseV13 : null;
  } catch { return null; }
}

/** A user-facing projection: never copy/speak transport or private state fields. */
export function responseToReadableText(value: unknown): string {
  const response = acceptedResponse(value);
  if (!response) return typeof value === 'string' && !/^\s*[\[{]/.test(value) ? value : 'This response could not be displayed. Please try again.';
  const parts: string[] = [];
  for (const block of response.content_blocks as any[]) {
    if (block.title) parts.push(block.title);
    if (block.text) parts.push(block.text);
    for (const item of block.items || []) parts.push(Array.from(new Set([item.title, item.text, item.value].filter(Boolean))).join(' — '));
    const d = block.data as any;
    if (d) {
      for (const card of d.cards || []) parts.push([card.title, card.subtitle, card.description, card.badge, ...(card.facts || []).map((f: any) => f.label + ': ' + f.value)].filter(Boolean).join(' — '));
      for (const m of d.milestones || []) parts.push([m.label, m.time_label, m.status, m.description].filter(Boolean).join(' — '));
      for (const n of d.nodes || []) parts.push([n.label, n.description].filter(Boolean).join(' — '));
      for (const e of d.edges || []) parts.push([d.nodes?.find((n: any) => n.id === e.from)?.label || 'Unknown step', '→', d.nodes?.find((n: any) => n.id === e.to)?.label || 'Unknown step', e.label, e.condition].filter(Boolean).join(' '));
      if (d.goal) parts.push('Goal: ' + d.goal);
      for (const lane of d.lanes || []) parts.push([lane.title, lane.summary, ...(lane.steps || []).map((s: any) => [s.label, s.description, s.status].filter(Boolean).join(' — '))].filter(Boolean).join('\n'));
      for (const m of d.metrics || []) parts.push([m.label, m.value_type === 'percentage' ? m.value + '%' : m.value_type === 'rating' ? m.value + '/' + (m.max ?? 10) : String(m.value) + (m.unit ? ' ' + m.unit : ''), m.description].filter(Boolean).join(' — '));
      if (d.source_status) parts.push(({ provided: 'Provided figures', estimated: 'Estimate', to_verify: 'Needs checking', verified: 'Marked verified in the response' } as Record<string, string>)[d.source_status] || 'Source not specified');
      for (const [i, category] of (d.categories || []).entries()) parts.push([category, ...(d.series || []).map((s: any) => s.label + ': ' + (s.values?.[i] ?? 'Not provided') + (s.unit ? ' ' + s.unit : ''))].join(' — '));
      for (const stage of d.stages || []) parts.push([stage.label, stage.status, stage.description].filter(Boolean).join(' — '));
    }
    for (const row of block.rows || []) parts.push([row.criteria, (row.cells || []).map((cell: any) => `${block.columns.find((c: any) => c.key === cell.key)?.label || cell.key}: ${cell.value || 'Not provided'}`).join('\n')].filter(Boolean).join('\n'));
  }
  if (response.interaction.kind !== 'none') {
    parts.push(response.interaction.question);
    for (const option of response.interaction.options) parts.push([option.label, option.description].filter(Boolean).join(' — '));
    for (const field of response.interaction.fields) parts.push(field.label);
  }
  return parts.filter(Boolean).join('\n\n');
}

/** Repair legacy prose containing literal bullet glyphs without changing its words.
 * Structured list blocks remain the preferred output. Code is left untouched. */
export function readableMarkdown(input: string): string {
  return input.split(/(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)/g).map((part, i) => {
    if (i % 2) return part;
    return part.split('\n').map(line => {
      if (!/^[ \t]*[•●]\s/.test(line) && (line.match(/\s[•●]\s/g) || []).length < 2) return line;
      return line.replace(/(^|\s+)[•●]\s+/g, '\n\n- ').trimStart();
    }).join('\n');
  }).join('');
}
