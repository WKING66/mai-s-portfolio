const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');

const page = readFileSync(join(__dirname, 'concept-a.html'), 'utf8');
assert.match(page, /aria-disabled="true"[^>]*title="简历尚未上传"/);
assert.match(page, /href="https:\/\/github\.com\/WKING66"/);
assert.match(page, /href="mailto:2899964923@qq\.com"/);
assert.doesNotMatch(page, />LinkedIn<\/a>/);
assert.ok((page.match(/tilt-card/g) || []).length >= 4);
assert.equal((page.match(/class="logo-track"/g) || []).length, 4);
assert.equal((page.match(/loading="lazy"/g) || []).length, 19);
assert.match(page, /@keyframes logo-slide/);
assert.match(page, /cloneNode\(true\)/);
assert.match(page, /pointermove/);
assert.match(page, /pointerleave/);
assert.match(page, /prefers-reduced-motion: reduce/);
assert.doesNotMatch(page, /mailto:hello@example\.com/);
console.log('A 版入口与动效检查通过');
