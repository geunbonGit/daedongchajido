// cd web && node --test test/*.test.mjs   (or npm test — Node ≥ 18, no dependencies)
//
// 1. Decodes the vectors the Kotlin encoder writes (core/src/test/resources/share/vectors.json) when that file exists.
// 2. Decodes this folder's vectors.local.json (made by tools/make-samples.mjs with the spec encoder test/encoder.mjs).
// 3. Round-trips random payloads through encoder → base32 → decoder, and checks error handling.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { base32Decode, base32Encode, decodePayload, decodeShare, dateFromDays, ShareError } from '../assets/share-decode.js';
import { cardNumbers, formatDistanceKm, minutesText, cardDateLabel, clockLabel, cardTitleText } from '../assets/format.js';
import { dateDaysOf, encodeShare, maxPayloadBytes, toBase32 } from './encoder.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const TOL = 1e-9;

// ── helpers ───────────────────────────────────────────────────────────────
/** first defined value among several possible field names (the Kotlin vectors may name things differently) */
function pick(obj, ...names) {
  for (const n of names) if (obj && obj[n] !== undefined) return obj[n];
  return undefined;
}

/** compares a decoded share against an "expected" object, field by field, for every field the expectation states */
function assertMatches(decoded, expect, label) {
  const scalar = {
    version: ['version'],
    kind: ['kind'],
    flags: ['flags'],
    dateDays: ['dateDays', 'date_days'],
    startMin: ['startMin', 'start_min'],
    durationS: ['durationS', 'duration_s'],
    distance10: ['distance10', 'distance_10'],
    pioneered10: ['pioneered10', 'pioneered_10'],
    driveCount: ['driveCount', 'drive_count'],
    title: ['title'],
    regionSet: ['regionSet', 'region_set'],
    visitedTotal: ['visitedTotal', 'visited_total'],
    quantExp: ['quantExp', 'quant_exp'],
  };
  for (const [field, names] of Object.entries(scalar)) {
    const want = pick(expect, ...names);
    if (want === undefined || (want === null && decoded[field] === null)) continue;
    assert.equal(decoded[field], want, `${label}: ${field}`);
  }
  const lists = { newRegions: ['newRegions', 'new_regions', 'firstRegions'], passedRegions: ['passedRegions', 'passed_regions', 'regions'], visited: ['visited', 'visitedRegions'] };
  for (const [field, names] of Object.entries(lists)) {
    const want = pick(expect, ...names);
    if (want === undefined) continue;
    assert.deepEqual(decoded[field], want, `${label}: ${field}`);
  }
  const lines = pick(expect, 'lines');
  if (lines === undefined) return;
  assert.equal(decoded.lines.length, lines.length, `${label}: line count`);
  lines.forEach((want, i) => {
    const got = decoded.lines[i];
    const style = pick(want, 'style');
    if (style !== undefined) assert.equal(got.style, style, `${label}: line ${i} style`);
    const level = pick(want, 'level', 'width');
    if (level !== undefined) assert.equal(got.level, level, `${label}: line ${i} level`);
    const fresh = pick(want, 'fresh', 'isNew', 'new', 'pioneered');
    if (fresh !== undefined) assert.equal(got.fresh, Boolean(fresh), `${label}: line ${i} fresh`);
    const q = pick(want, 'q', 'ints', 'quantized');
    if (q !== undefined) assert.deepEqual(got.q, q, `${label}: line ${i} ints`);
    let pts = pick(want, 'points', 'coords', 'latLng', 'pts');
    if (pts !== undefined) {
      // accept [[lat,lng],…], [{lat,lng},…] or a flat [lat,lng,lat,lng,…]
      if (pts.length && typeof pts[0] === 'number') pts = Array.from({ length: pts.length / 2 }, (_, k) => [pts[2 * k], pts[2 * k + 1]]);
      pts = pts.map(p => (Array.isArray(p) ? p : [p.lat, p.lng]));
      assert.equal(got.points.length, pts.length, `${label}: line ${i} point count`);
      pts.forEach(([lat, lng], k) => {
        assert.ok(Math.abs(got.points[k][0] - lat) <= TOL, `${label}: line ${i} pt ${k} lat ${got.points[k][0]} vs ${lat}`);
        assert.ok(Math.abs(got.points[k][1] - lng) <= TOL, `${label}: line ${i} pt ${k} lng ${got.points[k][1]} vs ${lng}`);
      });
    }
  });
}

function runVectorFile(file) {
  const data = JSON.parse(readFileSync(file, 'utf8'));
  const list = Array.isArray(data) ? data : data.vectors ?? data.cases ?? [];
  assert.ok(list.length > 0, `${file} has vectors`);
  let checked = 0;
  for (const [i, v] of list.entries()) {
    const label = v.name ?? v.id ?? `#${i}`;
    const payload = pick(v, 'payload', 'base32', 'hash', 'fragment');
    assert.equal(typeof payload, 'string', `${label}: payload string`);
    const error = pick(v, 'error', 'expectError');
    if (error) {
      assert.throws(() => decodeShare(payload), e => e instanceof ShareError && (typeof error !== 'string' || e.code === error || /./.test(error)), label);
      checked += 1;
      continue;
    }
    const decoded = decodeShare(payload);
    const expect = pick(v, 'expect', 'expected', 'decoded', 'output', 'result');
    assert.ok(expect && typeof expect === 'object', `${label}: has an expected decode`);
    assertMatches(decoded, expect, label);
    checked += 1;
  }
  return checked;
}

// ── vectors ───────────────────────────────────────────────────────────────
const coreVectors = join(here, '..', '..', 'core', 'src', 'test', 'resources', 'share', 'vectors.json');
test('Kotlin encoder vectors (core/src/test/resources/share/vectors.json)', { skip: !existsSync(coreVectors) && 'file not written yet' }, () => {
  const n = runVectorFile(coreVectors);
  assert.ok(n > 0);
});

test('Kotlin vectors: every decoded field is one this test compares (no silently skipped fields)', { skip: !existsSync(coreVectors) && 'file not written yet' }, () => {
  const known = new Set(['version', 'kind', 'flags', 'dateDays', 'startMin', 'durationS', 'distance10', 'pioneered10', 'driveCount', 'title', 'regionSet', 'visitedTotal', 'quantExp', 'newRegions', 'passedRegions', 'visited', 'lines']);
  const lineKnown = new Set(['style', 'level', 'new', 'fresh', 'points', 'q']);
  const data = JSON.parse(readFileSync(coreVectors, 'utf8'));
  const kinds = new Set();
  for (const v of data.vectors) {
    for (const k of Object.keys(v.decoded)) assert.ok(known.has(k), `${v.name}: unknown decoded field ${k}`);
    for (const line of v.decoded.lines ?? []) for (const k of Object.keys(line)) assert.ok(lineKnown.has(k), `${v.name}: unknown line field ${k}`);
    kinds.add(v.decoded.kind);
    // the URL in the vector is the share prefix + payload (what the QR holds)
    if (v.url) assert.equal(v.url, data.urlPrefix + v.payload, `${v.name}: url`);
  }
  assert.deepEqual([...kinds].sort(), [1, 2, 3], 'vectors cover all three kinds');
});

test('spec-encoder vectors (web/test/vectors.local.json)', () => {
  const n = runVectorFile(join(here, 'vectors.local.json'));
  assert.ok(n >= 15, `checked ${n}`);
});

test('error vectors fail with the documented codes', () => {
  const data = JSON.parse(readFileSync(join(here, 'vectors.local.json'), 'utf8'));
  for (const v of data.vectors.filter(x => x.error)) {
    try {
      decodeShare(v.payload);
      assert.fail(`${v.name} should throw`);
    } catch (e) {
      assert.ok(e instanceof ShareError, `${v.name}: ShareError`);
      assert.equal(e.code, v.error, v.name);
    }
  }
});

test('sample payloads fit a version-20-M QR with the share URL', () => {
  const data = JSON.parse(readFileSync(join(here, 'vectors.local.json'), 'utf8'));
  assert.equal(maxPayloadBytes(), data.qrBudgetBytes);
  for (const v of data.vectors.filter(x => x.name.startsWith('sample-'))) assert.ok(v.bytes <= data.qrBudgetBytes, `${v.name} ${v.bytes} B`);
});

// ── base32 / varint ──────────────────────────────────────────────────────
test('base32 matches RFC 4648 test vectors (no padding)', () => {
  const rfc = [
    ['', ''],
    ['f', 'MY'],
    ['fo', 'MZXQ'],
    ['foo', 'MZXW6'],
    ['foob', 'MZXW6YQ'],
    ['fooba', 'MZXW6YTB'],
    ['foobar', 'MZXW6YTBOI'],
  ];
  for (const [plain, coded] of rfc) {
    const bytes = new TextEncoder().encode(plain);
    assert.equal(base32Encode(bytes), coded);
    assert.equal(toBase32(bytes), coded);
    assert.deepEqual(Array.from(base32Decode(coded)), Array.from(bytes));
    assert.deepEqual(Array.from(base32Decode(coded.toLowerCase() + '====')), Array.from(bytes), 'lenient: case and padding');
  }
  assert.throws(() => base32Decode('MZ1W'), e => e.code === 'base32');
});

test('random payloads round-trip through the spec encoder', () => {
  let seed = 7;
  const rand = n => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed % n;
  };
  for (let round = 0; round < 300; round += 1) {
    const kind = 1 + rand(3);
    const quantExp = 3 + rand(2);
    const lines = [];
    let lat = 330000 + rand(60000);
    let lng = 1240000 + rand(80000);
    if (quantExp === 3) {
      lat = Math.round(lat / 10);
      lng = Math.round(lng / 10);
    }
    for (let l = 0; l < rand(6); l += 1) {
      const q = [];
      for (let k = 0; k < 2 + rand(12); k += 1) {
        lat += rand(4001) - 2000;
        lng += rand(4001) - 2000;
        q.push([lat, lng]);
      }
      lines.push({ level: 1 + rand(5), fresh: rand(2) === 1, q });
    }
    const input = {
      kind,
      flags: rand(4),
      dateDays: rand(5000),
      startMin: kind === 1 ? rand(1440) : 0,
      durationS: kind === 1 ? rand(40000) : 0,
      distance10: rand(10_000_000),
      pioneered10: rand(1_000_000),
      driveCount: kind === 1 ? 0 : rand(3000),
      title: ['', '출근', '강릉 여행', 'Seoul → 속초 🚗'][rand(4)],
      visitedTotal: rand(231),
      newRegions: Array.from({ length: rand(5) }, () => rand(230)),
      passedRegions: Array.from({ length: rand(9) }, () => rand(230)),
      visited: Array.from({ length: rand(40) }, () => rand(230)),
      quantExp,
      lines,
    };
    const { payload } = encodeShare(input);
    const d = decodeShare('#' + payload);
    assert.equal(d.kind, kind);
    assert.equal(d.flags, input.flags);
    assert.equal(d.dateDays, input.dateDays);
    assert.equal(d.distance10, input.distance10);
    assert.equal(d.title, input.title);
    if (kind === 1) {
      assert.deepEqual(d.newRegions, input.newRegions);
      assert.deepEqual(d.passedRegions, input.passedRegions);
    }
    if (kind === 3) assert.deepEqual(d.visited, [...new Set(input.visited)].sort((a, b) => a - b));
    if (kind !== 3) {
      assert.equal(d.lines.length, lines.length);
      d.lines.forEach((line, i) => {
        assert.deepEqual(line.q, lines[i].q);
        assert.equal(line.level, lines[i].level);
        assert.equal(line.fresh, lines[i].fresh);
        line.points.forEach(([la, ln], k) => {
          assert.ok(Math.abs(la - lines[i].q[k][0] / 10 ** quantExp) <= TOL);
          assert.ok(Math.abs(ln - lines[i].q[k][1] / 10 ** quantExp) <= TOL);
        });
      });
    } else assert.equal(d.lines.length, 0);
  }
});

test('truncated payloads never decode silently', () => {
  const { bytes } = encodeShare({
    kind: 1,
    dateDays: 2000,
    title: '가평',
    newRegions: [1, 2],
    passedRegions: [1, 2, 3],
    quantExp: 4,
    lines: [{ level: 2, q: [[375000, 1270000], [375100, 1270100]] }],
  });
  for (let cut = 0; cut < bytes.length; cut += 1) {
    assert.throws(() => decodePayload(bytes.subarray(0, cut)), e => e instanceof ShareError && (e.code === 'truncated' || e.code === 'empty'), `cut at ${cut}`);
  }
});

test('unsupported version / kind / region set are flagged for "앱을 업데이트해 주세요"', () => {
  for (const [input, code] of [
    [{ version: 2, kind: 1, dateDays: 1 }, 'version'],
    [{ kind: 9, dateDays: 1 }, 'kind'],
    [{ kind: 3, dateDays: 1, regionSet: 7 }, 'regionSet'],
  ]) {
    const { payload } = encodeShare(input);
    assert.throws(() => decodeShare(payload), e => e.code === code && e.unsupported === true);
  }
  assert.throws(() => decodeShare('#'), e => e.code === 'empty' && e.unsupported === false);
});

// ── dates & card text ────────────────────────────────────────────────────
test('dateDays → KST calendar date', () => {
  assert.deepEqual(dateFromDays(0), { year: 2020, month: 1, day: 1, weekday: 3 });
  const d = dateFromDays(dateDaysOf(2026, 10, 31));
  assert.deepEqual(d, { year: 2026, month: 10, day: 31, weekday: 6 });
  assert.equal(cardDateLabel(d), '2026.10.31 토');
  assert.equal(dateDaysOf(2026, 10, 31), 2495);
});

test('발자취 number formats', () => {
  assert.equal(formatDistanceKm(0), '0.00');
  assert.equal(formatDistanceKm(1150), '1.15');
  assert.equal(formatDistanceKm(12439), '12.43');
  assert.equal(formatDistanceKm(99999), '99.99');
  assert.equal(formatDistanceKm(100000), '100.0');
  assert.equal(formatDistanceKm(415089), '415.0');
  assert.equal(minutesText(20), '1분 미만');
  assert.equal(minutesText(5940), '1시간 39분');
  assert.equal(minutesText(7200), '2시간');
  assert.equal(minutesText(38 * 60), '38분');
  assert.equal(clockLabel(9 * 60 + 40), '오전 9:40');
  assert.equal(clockLabel(12 * 60 + 5), '오후 12:05');
  assert.equal(clockLabel(0), '오전 12:00');
  assert.equal(cardTitleText('  강릉   여행 \n'), '강릉 여행');
  assert.equal(Array.from(cardTitleText('가'.repeat(30))).length, 20);
});

test('card numbers per kind (design.md §6)', () => {
  const base = { regionCount: 230, newRegions: [], passedRegions: [], visited: [], visitedTotal: 13, driveCount: 0, durationS: 5940 };
  assert.deepEqual(cardNumbers({ ...base, kind: 1, distanceM: 79390, pioneeredM: 12430, newRegions: [1, 2, 3] }), {
    hero: '+12.43',
    unit: 'km',
    label: '처음 달린 길',
    stats: '79.39km · 1시간 39분 · 첫 방문 3곳',
  });
  assert.deepEqual(cardNumbers({ ...base, kind: 1, distanceM: 14970, pioneeredM: 0 }), { hero: '14.97', unit: 'km', label: '달린 길', stats: '1시간 39분' });
  assert.deepEqual(cardNumbers({ ...base, kind: 2, distanceM: 602300, pioneeredM: 415040, driveCount: 14 }), {
    hero: '415.0',
    unit: 'km',
    label: '내 지도',
    stats: '602.3km · 드라이브 14번',
  });
  assert.deepEqual(cardNumbers({ ...base, kind: 3, distanceM: 602300, pioneeredM: 0, driveCount: 14, visitedTotal: 23 }), {
    hero: '23',
    unit: '/230곳',
    label: '가 본 시·군·구',
    stats: '602.3km · 드라이브 14번',
  });
});
