// Font chunks (tools/build-fonts.py → fonts/fonts.css): every file exists, the card's fixed words sit in the small
// chunk "a", and every Hangul syllable the pages show sits in "a" or "b" — so a page never waits for a rare chunk
// because of its own text, and the card only needs "a" (+ "b" for most titles).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = join(dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(join(web, 'fonts', 'fonts.css'), 'utf8');

function faces() {
  const out = [];
  for (const m of css.matchAll(/@font-face\s*{([^}]*)}/g)) {
    const body = m[1];
    const weight = Number(/font-weight:\s*(\d+)/.exec(body)[1]);
    const file = /url\('([^']+)'\)/.exec(body)[1];
    const chunk = /-(\w+)\.woff2$/.exec(file)[1];
    const ranges = /unicode-range:\s*([^;]+);/
      .exec(body)[1]
      .split(',')
      .map(r => {
        const [a, b] = r.trim().replace(/^U\+/, '').split('-');
        return [parseInt(a, 16), parseInt(b ?? a, 16)];
      });
    out.push({ weight, file, chunk, ranges });
  }
  return out;
}
const covers = (face, cp) => face.ranges.some(([a, b]) => cp >= a && cp <= b);
const hangulOf = text => [...new Set([...text].filter(c => c >= '가' && c <= '힣'))];

test('every @font-face file exists, per weight a · b · c* · x', () => {
  const list = faces();
  for (const w of [500, 700, 800]) {
    const chunks = list.filter(f => f.weight === w).map(f => f.chunk);
    assert.ok(chunks.includes('a') && chunks.includes('b') && chunks.includes('x'), `${w}: ${chunks}`);
  }
  for (const f of list) assert.ok(existsSync(join(web, 'fonts', f.file)), f.file);
  // most specific last: the browser tries the last matching rule first, so a/b must come after the wide c spans
  for (const w of [500, 700, 800]) {
    const order = list.filter(f => f.weight === w).map(f => f.chunk);
    assert.deepEqual(order.slice(-2), ['b', 'a'], `${w} order ${order}`);
  }
});

test('card words and digits live in chunk a (weights 500 and 700)', () => {
  const words = '0123456789+.km/·대동차지도 처음 달린 길 내 지도 가 본 시·군·구 곳 드라이브 번 첫 방문 시간 분 미만 일월화수목금토…';
  for (const w of [500, 700]) {
    const a = faces().find(f => f.weight === w && f.chunk === 'a');
    for (const ch of new Set(words)) if (ch !== ' ') assert.ok(covers(a, ch.codePointAt(0)), `${w} a: ${ch}`);
  }
});

test('all page text is in chunks a + b', () => {
  const files = ['index.html', 's/index.html', 's/viewer.js', 'assets/format.js'];
  const text = files.map(f => readFileSync(join(web, f), 'utf8')).join('\n');
  for (const w of [500, 700, 800]) {
    const ab = faces().filter(f => f.weight === w && (f.chunk === 'a' || f.chunk === 'b'));
    const missing = hangulOf(text).filter(ch => !ab.some(f => covers(f, ch.codePointAt(0))));
    assert.deepEqual(missing, [], `${w}: rebuild fonts (python3 web/tools/build-fonts.py) — missing ${missing.join('')}`);
  }
});
