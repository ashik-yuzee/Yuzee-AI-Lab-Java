/**
 * Presentation helpers ported from the original app:
 * - readableMarkdown            ← components/readableMarkdown.ts
 * - learningToneIn              ← re-exported from the mini-pathway port of miniPathway/learningTypes.ts
 * - acceptedResponse / responseToReadableText ← ux/responsePresentation.ts
 * - markdownToHtml              ← stands in for react-markdown + remark-gfm (no markdown library is
 *   installed here). Emits the same element structure react-markdown produced for the `text`
 *   block (p, h1–h6, ul/ol/li with tight/loose paragraphs, blockquote, pre/code, hr, a, strong,
 *   em, del, img, br and GFM tables wrapped exactly like ProtocolV13Renderer's custom `table`
 *   component). All text is HTML-escaped; raw HTML in the source is shown as text.
 */
import { learningToneIn } from '../mini-pathway/learning-types';
import { validateProtocol } from './protocol-validator';
import type { YuzeeResponseV13 } from '../../models/types';

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

export { learningToneIn };

export function acceptedResponse(value: unknown): YuzeeResponseV13 | null {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    return validateProtocol(parsed).protocolAccepted ? parsed as YuzeeResponseV13 : null;
  } catch { return null; }
}

/** A user-facing projection: never copy/speak transport or private state fields. */
export function responseToReadableText(value: unknown): string {
  const response: any = acceptedResponse(value);
  if (!response) return typeof value === 'string' && !/^\s*[\[{]/.test(value) ? value : 'This response could not be displayed. Please try again.';
  const parts: string[] = [];
  for (const block of response.content_blocks) {
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

// ---------------------------------------------------------------------------
// Markdown → HTML
// ---------------------------------------------------------------------------

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function safeUrl(url: string): string {
  const u = url.trim();
  // Same policy as react-markdown's defaultUrlTransform: relative URLs or http(s)/mailto/tel/irc(s)/xmpp.
  const colon = u.indexOf(':');
  if (colon === -1) return u;
  const beforeDelim = u.slice(0, colon);
  if (/[/?#]/.test(beforeDelim)) return u;
  return /^(https?|ircs?|mailto|xmpp|tel)$/i.test(beforeDelim) ? u : '';
}

/** micromark-util-sanitize-uri `normalizeUri`: percent-encode what a URL may not contain (as mdast-util-to-hast does). */
function normalizeUri(value: string): string {
  const result: string[] = [];
  let start = 0;
  let skip = 0;
  const alnum = (c: number) => (c > 47 && c < 58) || (c > 64 && c < 91) || (c > 96 && c < 123);
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    let replace = '';
    if (code === 37 && alnum(value.charCodeAt(index + 1)) && alnum(value.charCodeAt(index + 2))) skip = 2;
    else if (code < 128) { if (!/[!#$&-;=?-Z_a-z~]/.test(String.fromCharCode(code))) replace = String.fromCharCode(code); }
    else if (code > 55295 && code < 57344) {
      const next = value.charCodeAt(index + 1);
      if (code < 56320 && next > 56319 && next < 57344) { replace = String.fromCharCode(code, next); skip = 1; }
      else replace = '\uFFFD';
    } else replace = String.fromCharCode(code);
    if (replace) { result.push(value.slice(start, index), encodeURIComponent(replace)); start = index + skip + 1; }
    if (skip) { index += skip; skip = 0; }
  }
  return result.join('') + value.slice(start);
}

/**
 * Per-render state. GFM task-list checkboxes and footnote ids/data-attributes are markup Angular's
 * HTML sanitizer strips (it drops <input>, id and data-*). They are emitted as placeholders (a
 * `class="md-<nonce>-N"` attribute or a `<span class="mde-<nonce>-N"></span>` element, neither of
 * which escaped user text can produce) and filled in only after the rest has been sanitised.
 * The filled-in markup is fixed; its only user-derived part, a footnote label, is URI-normalised and escaped.
 */
interface MdCtx { nonce: string; store: string[]; defined: Set<string>; defs: Map<string, string[]>; order: string[]; refCounts: Map<string, number>; }
let md: MdCtx;
/** Per-render: true for ChatArea's plain <Markdown> (no ProtocolV13Renderer table components). */
let plainTables = false;
const markAttrs = (attrs: string) => `class="md-${md.nonce}-${md.store.push(attrs) - 1}"`;
const markEl = (html: string) => `<span class="mde-${md.nonce}-${md.store.push(html) - 1}"></span>`;
const footnoteId = (label: string) => label.replace(/[\t\n\r ]+/g, ' ').trim().toLowerCase();
const FOOTDEF = /^ {0,3}\[\^([^\]\s]+)\]:[ \t]?(.*)$/;

function inline(src: string): string {
  const slots: string[] = [];
  const hold = (html: string) => `\u0000${slots.push(html) - 1}\u0000`;
  let s = src;
  // Code spans.
  s = s.replace(/(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)/g, (_m, _t, code: string) => hold(`<code>${esc(code.replace(/\n/g, ' ').replace(/^ (.*) $/, '$1'))}</code>`));
  // Backslash escapes.
  s = s.replace(/\\([!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~])/g, (_m, c: string) => hold(esc(c)));
  // GFM footnote references (only to labels that have a definition).
  s = s.replace(/\[\^([^\]\s]+)\]/g, (m, label: string) => {
    const id = footnoteId(label);
    if (!md.defined.has(id)) return m;
    if (!md.order.includes(id)) md.order.push(id);
    const k = (md.refCounts.get(id) ?? 0) + 1;
    md.refCounts.set(id, k);
    const safe = esc(normalizeUri(id));
    return hold(`<sup><a href="#user-content-fn-${safe}" ${markAttrs(`id="user-content-fnref-${safe}${k > 1 ? '-' + k : ''}" data-footnote-ref="true" aria-describedby="footnote-label"`)}>${md.order.indexOf(id) + 1}</a></sup>`);
  });
  // Images and links (destinations may contain one level of balanced parentheses).
  const href = (url: string) => esc(safeUrl(normalizeUri(url)));
  s = s.replace(/!\[([^\]]*)\]\(\s*<?((?:[^()\s<>]|\([^()\s]*\))*)>?(?:\s+"([^"]*)")?\s*\)/g, (_m, alt: string, url: string, title?: string) =>
    hold(`<img src="${href(url)}" alt="${esc(alt)}"${title ? ` title="${esc(title)}"` : ''}>`));
  s = s.replace(/\[([^\]]+)\]\(\s*<?((?:[^()\s<>]|\([^()\s]*\))*)>?(?:\s+"([^"]*)")?\s*\)/g, (_m, text: string, url: string, title?: string) =>
    hold(`<a href="${href(url)}"${title ? ` title="${esc(title)}"` : ''}>${inline(text)}</a>`));
  s = s.replace(/<((?:https?|mailto):[^\s<>]+)>/g, (_m, url: string) => hold(`<a href="${href(url)}">${esc(url)}</a>`));
  // GFM autolink literals.
  s = s.replace(/(^|[\s(*_~])((?:https?:\/\/|www\.)[^\s<]*[^\s<.,:;"')\]*_~?!])/g, (_m, pre: string, url: string) =>
    pre + hold(`<a href="${href(url.startsWith('www.') ? 'http://' + url : url)}">${esc(url)}</a>`));
  s = s.replace(/(^|[\s(*_~])([\w.+-]+@[\w-]+(?:\.[\w-]+)+)/g, (_m, pre: string, email: string) =>
    pre + hold(`<a href="${href('mailto:' + email)}">${esc(email)}</a>`));
  s = esc(s);
  s = s
    .replace(/(\*\*\*|___)(?=\S)([\s\S]*?\S)\1/g, '<em><strong>$2</strong></em>')
    .replace(/(\*\*|__)(?=\S)([\s\S]*?\S)\1/g, '<strong>$2</strong>')
    .replace(/\*(?=[^\s*])([^*]*?[^\s*])\*/g, '<em>$1</em>')
    .replace(/(^|[^\w])_(?=[^\s_])([^_]*?[^\s_])_(?!\w)/g, '$1<em>$2</em>')
    .replace(/(?<!~)(~~?)(?![\s~])([\s\S]*?[^\s~])\1(?!~)/g, '<del>$2</del>')
    // Hard breaks: two trailing spaces or a trailing backslash.
    .replace(/(?: {2,}|\\)\n/g, '<br>\n');
  return s.replace(/\u0000(\d+)\u0000/g, (_m, i: string) => slots[Number(i)]);
}

const LIST_ITEM = /^( {0,3})([-*+]|\d{1,9}[.)])( +|$)/;
const HR = /^ {0,3}([-*_])(?: *\1){2,} *$/;
const HEADING = /^ {0,3}(#{1,6})(?: +(.*?))?(?: +#+)? *$/;
const FENCE = /^ {0,3}(`{3,}|~{3,})(.*)$/;
const TABLE_DELIM = /^ *\|? *:?-+:? *(?:\| *:?-+:? *)*\|? *$/;

function splitRow(line: string): string[] {
  let l = line.trim();
  if (l.startsWith('|')) l = l.slice(1);
  if (l.endsWith('|') && !l.endsWith('\\|')) l = l.slice(0, -1);
  const cells: string[] = [];
  let cur = '';
  for (let i = 0; i < l.length; i++) {
    if (l[i] === '\\' && l[i + 1] === '|') { cur += '|'; i++; continue; }
    if (l[i] === '|') { cells.push(cur.trim()); cur = ''; continue; }
    cur += l[i];
  }
  cells.push(cur.trim());
  return cells;
}

function startsBlock(line: string): boolean {
  return HEADING.test(line) || FENCE.test(line) || /^ {0,3}>/.test(line) || HR.test(line)
    || /^ {0,3}([-*+]|1[.)]) +\S/.test(line) || FOOTDEF.test(line);
}

type MdBlock = { html: string; p: boolean; inner?: string };

function blocks(lines: string[]): string { return blockList(lines).map(b => b.html).join('\n'); }

function blockList(lines: string[]): MdBlock[] {
  const out: MdBlock[] = [];
  const push = (html: string) => out.push({ html, p: false });
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }

    // GFM footnote definition: rendered in the footnotes section, not here.
    const def = line.match(FOOTDEF);
    if (def) {
      const body = [def[2]];
      i++;
      while (i < lines.length) {
        const next = lines[i];
        if (!next.trim()) {
          let j = i;
          while (j < lines.length && !lines[j].trim()) j++;
          if (j < lines.length && /^ {4}/.test(lines[j])) { while (i < j) { body.push(''); i++; } continue; }
          break;
        }
        if (/^ {4}/.test(next)) { body.push(next.slice(4)); i++; continue; }
        if (body[body.length - 1].trim() !== '' && !startsBlock(next)) { body.push(next); i++; continue; }
        break;
      }
      const id = footnoteId(def[1]);
      if (!md.defs.has(id)) md.defs.set(id, body);
      continue;
    }

    const fence = line.match(FENCE);
    if (fence && !(fence[1][0] === '`' && fence[2].includes('`'))) {
      const marker = fence[1];
      const lang = fence[2].trim().split(/\s+/)[0];
      const body: string[] = [];
      i++;
      while (i < lines.length && !new RegExp(`^ {0,3}${marker[0]}{${marker.length},} *$`).test(lines[i])) body.push(lines[i++]);
      i++;
      push(`<pre><code${lang ? ` class="language-${esc(lang)}"` : ''}>${esc(body.join('\n'))}${body.length ? '\n' : ''}</code></pre>`);
      continue;
    }

    const heading = line.match(HEADING);
    if (heading) {
      const n = heading[1].length;
      push(`<h${n}>${inline(heading[2] ?? '')}</h${n}>`);
      i++;
      continue;
    }

    if (HR.test(line)) {
      push('<hr>');
      i++;
      continue;
    }

    if (/^ {0,3}>/.test(line)) {
      const body: string[] = [];
      while (i < lines.length && lines[i].trim() && (/^ {0,3}>/.test(lines[i]) || body.length)) {
        if (!/^ {0,3}>/.test(lines[i]) && startsBlock(lines[i])) break;
        body.push(lines[i].replace(/^ {0,3}> ?/, ''));
        i++;
      }
      push(`<blockquote>\n${blocks(body)}\n</blockquote>`);
      continue;
    }

    if (line.includes('|') && i + 1 < lines.length && TABLE_DELIM.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      const head = splitRow(line);
      const delimRow = lines[i + 1];
      const delimCount = splitRow(delimRow).length;
      if (head.length === delimCount) {
        i += 2;
        const rows: string[][] = [];
        while (i < lines.length && lines[i].trim() && !startsBlock(lines[i])) rows.push(splitRow(lines[i++]));
        if (plainTables) {
          // react-markdown's default table (ChatArea has no custom components): cell alignment as inline text-align.
          const align = splitRow(delimRow).map(d => { const c = d.trim(); return c.startsWith(':') ? (c.endsWith(':') ? 'center' : 'left') : c.endsWith(':') ? 'right' : ''; });
          const cell = (tag: string, c: string, ci: number) => `<${tag}${align[ci] ? ' ' + markAttrs(`style="text-align:${align[ci]}"`) : ''}>${inline(c)}</${tag}>`;
          const headHtml = `<tr>${head.map((c, ci) => cell('th', c, ci)).join('')}</tr>`;
          const bodyHtml = rows.map(r => `<tr>${head.map((_h, ci) => cell('td', r[ci] ?? '', ci)).join('')}</tr>`).join('');
          push(`<table><thead>${headHtml}</thead>${rows.length ? `<tbody>${bodyHtml}</tbody>` : ''}</table>`);
          continue;
        }
        const tr = (cells: string) => `<tr class="md-tr">${cells}</tr>`;
        const headHtml = tr(head.map(c => `<th class="md-th">${inline(c)}</th>`).join(''));
        const bodyHtml = rows.map(r => tr(head.map((_h, ci) => `<td class="md-td">${inline(r[ci] ?? '')}</td>`).join(''))).join('');
        push(`<div class="response-table-scroll md-table-wrap"><table class="md-table"><thead class="md-thead">${headHtml}</thead>${rows.length ? `<tbody>${bodyHtml}</tbody>` : ''}</table></div>`);
        continue;
      }
    }

    const item = line.match(LIST_ITEM);
    if (item && (line.slice(item[0].length).trim() !== '' || !!lines[i + 1]?.startsWith(' '))) {
      const ordered = /\d/.test(item[2]);
      const delim = item[2].slice(-1);
      const items: string[][] = [];
      let loose = false;
      let sawBlank = false;
      while (i < lines.length) {
        const m = lines[i].match(LIST_ITEM);
        if (m && (/\d/.test(m[2]) === ordered) && m[2].slice(-1) === delim) {
          if (sawBlank && items.length) loose = true;
          sawBlank = false;
          const contentOffset = m[0].length + (m[3].length > 4 ? 1 - m[3].length : 0);
          const indent = Math.max(contentOffset, m[1].length + m[2].length + 1);
          const body = [lines[i].slice(m[0].length)];
          i++;
          while (i < lines.length) {
            const next = lines[i];
            if (!next.trim()) { body.push(''); i++; continue; }
            const leading = next.match(/^ */)![0].length;
            if (leading >= indent) {
              body.push(next.slice(indent));
              i++;
              continue;
            }
            const lastNonBlank = body[body.length - 1];
            if (lastNonBlank !== '' && !startsBlock(next) && !LIST_ITEM.test(next)) { body.push(next.trim()); i++; continue; }
            break;
          }
          while (body.length && body[body.length - 1] === '') { body.pop(); sawBlank = true; }
          if (body.includes('')) loose = true;
          items.push(body);
          continue;
        }
        break;
      }
      const start = ordered ? parseInt(item[2], 10) : 1;
      const tag = ordered ? 'ol' : 'ul';
      let hasTask = false;
      const lis = items.map(itemLines => {
        // GFM task list item: `[ ]` / `[x]` followed by whitespace at the start of the first paragraph.
        const task = itemLines[0].match(/^\[([ xX])\](?=[ \t])/);
        let body = itemLines;
        let ws = '';
        if (task) { const rest = body[0].slice(3); ws = rest.match(/^[ \t]*/)![0]; body = [rest.slice(ws.length), ...body.slice(1)]; }
        const res = blockList(body);
        const isTask = !!task && res[0]?.p === true;
        if (isTask) {
          hasTask = true;
          const box = markEl(`<input type="checkbox" disabled=""${task![1] === ' ' ? '' : ' checked=""'}>`) + ws;
          res[0] = { html: `<p>${box}${res[0].inner}</p>`, p: true, inner: box + res[0].inner };
        }
        // mdast-util-to-hast listItem: tight items unwrap paragraphs; newlines around other children.
        const parts: string[] = [];
        res.forEach((b, idx) => { if (loose || idx !== 0 || !b.p) parts.push('\n'); parts.push(b.p && !loose ? b.inner! : b.html); });
        const tail = res[res.length - 1];
        if (tail && (loose || !tail.p)) parts.push('\n');
        return `<li${isTask ? ' class="task-list-item"' : ''}>${parts.join('')}</li>`;
      }).join('\n');
      push(`<${tag}${ordered && start !== 1 ? ` start="${start}"` : ''}${hasTask ? ' class="contains-task-list"' : ''}>\n${lis}\n</${tag}>`);
      continue;
    }

    // Paragraph (with lazy continuation).
    const para: string[] = [line.replace(/^ +/, '')];
    i++;
    while (i < lines.length && lines[i].trim()) {
      if (/^ {0,3}(=+|-+) *$/.test(lines[i])) { para.push(lines[i].trim()); i++; break; }
      if (startsBlock(lines[i])) break;
      if (lines[i].includes('|') && i + 1 < lines.length && TABLE_DELIM.test(lines[i + 1])) break;
      para.push(lines[i].replace(/^ +/, ''));
      i++;
    }
    const setext = para.length > 1 ? para[para.length - 1].match(/^(=+|-+) *$/) : null;
    if (setext) {
      const n = setext[1][0] === '=' ? 1 : 2;
      push(`<h${n}>${inline(para.slice(0, -1).join('\n').trim())}</h${n}>`);
      continue;
    }
    const html = inline(para.join('\n').replace(/[ \t]+$/, ''));
    out.push({ html: `<p>${html}</p>`, p: true, inner: html });
  }
  return out;
}

/**
 * `sanitize` receives the markup with placeholders (the component passes Angular's HTML sanitizer);
 * task checkboxes and footnote attributes are filled in afterwards.
 */
export function markdownToHtml(markdown: string, sanitize: (html: string) => string = html => html, options: { plainTables?: boolean } = {}): string {
  if (!markdown) return '';
  plainTables = !!options.plainTables;
  const lines = markdown.replace(/\r\n?/g, '\n').replace(/\u0000/g, '\uFFFD').replace(/^\t+/gm, t => '    '.repeat(t.length)).split('\n');
  md = { nonce: Math.random().toString(36).slice(2, 10), store: [], defined: new Set(), defs: new Map(), order: [], refCounts: new Map() };
  for (const l of lines) { const d = l.match(FOOTDEF); if (d) md.defined.add(footnoteId(d[1])); }
  let html = blocks(lines);
  if (md.order.length) {
    // mdast-util-to-hast footer: referenced definitions in first-reference order, back-links in the last paragraph.
    const lis: string[] = [];
    for (let n = 0; n < md.order.length; n++) {
      const id = md.order[n];
      const safe = esc(normalizeUri(id));
      const content = blockList(md.defs.get(id) ?? []);
      const refs = Array.from({ length: md.refCounts.get(id) ?? 1 }, (_v, k) => k + 1).map(k =>
        `<a href="#user-content-fnref-${safe}${k > 1 ? '-' + k : ''}" ${markAttrs(`data-footnote-backref="" aria-label="Back to reference ${n + 1}${k > 1 ? '-' + k : ''}" class="data-footnote-backref"`)}>↩${k > 1 ? `<sup>${k}</sup>` : ''}</a>`).join(' ');
      const tail = content[content.length - 1];
      if (tail?.p) content[content.length - 1] = { ...tail, html: `<p>${tail.inner} ${refs}</p>` };
      else content.push({ html: refs, p: false });
      lis.push(`<li ${markAttrs(`id="user-content-fn-${safe}"`)}>\n${content.map(b => b.html).join('\n')}\n</li>`);
    }
    const section = `<section ${markAttrs('data-footnotes="true" class="footnotes"')}><h2 ${markAttrs('class="sr-only" id="footnote-label"')}>Footnotes</h2>\n<ol>\n${lis.join('\n')}\n</ol>\n</section>`;
    html = html ? html + '\n' + section : section;
  }
  const { nonce, store } = md;
  return sanitize(html)
    .replace(new RegExp(`<span class="mde-${nonce}-(\\d+)"></span>`, 'g'), (_m, i: string) => store[Number(i)])
    .replace(new RegExp(`class="md-${nonce}-(\\d+)"`, 'g'), (_m, i: string) => store[Number(i)]);
}
