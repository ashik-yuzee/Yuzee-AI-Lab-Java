// Run: npx esbuild src/app/utils/response-presentation.check.ts --bundle --platform=node | node
import { strict as assert } from 'node:assert';
import { readableMarkdown, responseToReadableText } from './response-presentation';

assert.equal(readableMarkdown('Intro • one • two'), 'Intro\n\n- one\n\n- two');
assert.equal(responseToReadableText('{"bad":true}'), 'This response could not be displayed. Please try again.');
assert.equal(responseToReadableText('plain prose'), 'plain prose');
console.log('response-presentation: ok');
