// Run: npx esbuild src/app/components/protocol-renderer/protocol-presentation.check.ts --bundle --platform=node | node
// Expected strings are react-markdown + remark-gfm output (as ProtocolV13Renderer rendered it).
import { strict as assert } from 'node:assert';
import { markdownToHtml } from './protocol-presentation';

assert.equal(markdownToHtml('- [ ] a\n- [x] b'),
  '<ul class="contains-task-list">\n<li class="task-list-item"><input type="checkbox" disabled=""> a</li>\n<li class="task-list-item"><input type="checkbox" disabled="" checked=""> b</li>\n</ul>');
assert.equal(markdownToHtml('- a\n  - b'), '<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n</ul>');
assert.equal(markdownToHtml('[bad](javascript:alert(1)) [p](https://x.com/a_(b))'), '<p><a href="">bad</a> <a href="https://x.com/a_(b)">p</a></p>');
assert.equal(markdownToHtml('Ref[^1]\n\n[^1]: note'),
  '<p>Ref<sup><a href="#user-content-fn-1" id="user-content-fnref-1" data-footnote-ref="true" aria-describedby="footnote-label">1</a></sup></p>\n' +
  '<section data-footnotes="true" class="footnotes"><h2 class="sr-only" id="footnote-label">Footnotes</h2>\n<ol>\n<li id="user-content-fn-1">\n' +
  '<p>note <a href="#user-content-fnref-1" data-footnote-backref="" aria-label="Back to reference 1" class="data-footnote-backref">↩</a></p>\n</li>\n</ol>\n</section>');
// Placeholders are filled only after sanitising; a sanitizer that strips everything leaves no injected markup.
assert.equal(markdownToHtml('- [ ] a', () => ''), '');
// User text cannot forge a placeholder.
assert.ok(!markdownToHtml('<span class="mde-x-0"></span> [t](https://a "class=\\"md-x-0\\"")').includes('<input'));
// ChatArea's plain <Markdown>: react-markdown's default table (verified against the original's react-markdown).
assert.equal(markdownToHtml('| A | B |\n|:--|--:|\n| 1 | 2 |', h => h, { plainTables: true }),
  '<table><thead><tr><th style="text-align:left">A</th><th style="text-align:right">B</th></tr></thead><tbody><tr><td style="text-align:left">1</td><td style="text-align:right">2</td></tr></tbody></table>');
// Alignment survives Angular-style sanitising that strips style attributes (it is added after).
assert.ok(markdownToHtml('| A |\n|:-:|', h => h.replace(/ style="[^"]*"/g, ''), { plainTables: true }).includes('style="text-align:center"'));
console.log('protocol-presentation: ok');
