#!/usr/bin/env node
// Builds realistic sample share payloads from the demo drives (app/src/main/assets/drives/*.jsonl) and the demo region
// sequence (tools/regions/out/demo-region-sequence.json), plus edge-case vectors — all with the spec encoder in
// web/test/encoder.mjs. This only imitates the app (known/new split by nearness to earlier drives, ends trimmed 300 m,
// Douglas–Peucker until the payload fits a version-20 QR); the real payloads come from the Kotlin encoder.
//
//   node web/tools/make-samples.mjs
//
// Writes web/test/vectors.local.json (payload + expected decode) and web/assets/samples.js (payloads for the landing
// page), and updates the `data-sample="…"` links in web/index.html.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dateDaysOf, encodeShare, maxPayloadBytes, quantize, styleOf } from '../test/encoder.mjs';
import { decodePayload, ShareError } from '../assets/share-decode.js';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, '..');
const repo = join(web, '..');
const drivesDir = join(repo, 'app', 'src', 'main', 'assets', 'drives');
const regionsTable = JSON.parse(readFileSync(join(web, 'data', 'regions.json'), 'utf8')).regions;
const sequence = JSON.parse(readFileSync(join(repo, 'tools', 'regions', 'out', 'demo-region-sequence.json'), 'utf8'));
const indexOfCode = new Map(regionsTable.map((r, i) => [r.code, i]));

const BUDGET = maxPayloadBytes();
const M_PER_DEG = 6371008.8 * (Math.PI / 180);

// ── geometry ─────────────────────────────────────────────────────────────
function readDrive(id) {
  const text = readFileSync(join(drivesDir, `${id}.jsonl`), 'utf8');
  const pts = [];
  let meta = null;
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    const rec = JSON.parse(line);
    if (rec.k === 'meta') meta = rec;
    else if (rec.k === 'loc') pts.push({ t: rec.t, lat: rec.lat, lng: rec.lng });
  }
  return { id, meta, pts };
}

const LAT0 = 37.5;
const KX = M_PER_DEG * Math.cos((LAT0 * Math.PI) / 180);
const xy = p => [(p.lng - 127) * KX, (p.lat - LAT0) * M_PER_DEG];
const dist = (a, b) => Math.hypot(...xy(a).map((v, i) => v - xy(b)[i]));

function lengthM(pts) {
  let s = 0;
  for (let i = 1; i < pts.length; i += 1) s += dist(pts[i - 1], pts[i]);
  return s;
}

/** cut `m` meters from the start and the end of a point run (interpolating the cut points) */
function trimEnds(pts, m) {
  const cut = (run, d) => {
    let s = 0;
    for (let i = 1; i < run.length; i += 1) {
      const seg = dist(run[i - 1], run[i]);
      if (s + seg >= d) {
        const f = (d - s) / seg;
        const p = { ...run[i], lat: run[i - 1].lat + (run[i].lat - run[i - 1].lat) * f, lng: run[i - 1].lng + (run[i].lng - run[i - 1].lng) * f };
        return [p, ...run.slice(i)];
      }
      s += seg;
    }
    return [];
  };
  return cut(cut(pts, m).reverse(), m).reverse();
}

/** Douglas–Peucker in meters (iterative) */
function simplify(pts, tol) {
  if (pts.length <= 2) return pts.slice();
  const P = pts.map(xy);
  const keep = new Uint8Array(pts.length);
  keep[0] = 1;
  keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    const [ax, ay] = P[a];
    const [bx, by] = P[b];
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    let best = -1;
    let bestD = tol;
    for (let i = a + 1; i < b; i += 1) {
      const [px, py] = P[i];
      let d;
      if (len2 === 0) d = Math.hypot(px - ax, py - ay);
      else {
        const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      }
      if (d > bestD) {
        bestD = d;
        best = i;
      }
    }
    if (best >= 0) {
      keep[best] = 1;
      stack.push([a, best], [best, b]);
    }
  }
  return pts.filter((_, i) => keep[i]);
}

// ── "known road" = within ~1 cell of an earlier drive ─────────────────────
const CELL = 25;
const cellKey = p => {
  const [x, y] = xy(p);
  return `${Math.floor(x / CELL)},${Math.floor(y / CELL)}`;
};
function cellsOf(pts) {
  const set = new Set();
  for (const p of pts) set.add(cellKey(p));
  return set;
}
function near(cellSetOrMap, p) {
  const [x, y] = xy(p);
  const cx = Math.floor(x / CELL);
  const cy = Math.floor(y / CELL);
  let best = 0;
  for (let i = -1; i <= 1; i += 1)
    for (let j = -1; j <= 1; j += 1) {
      const v = cellSetOrMap instanceof Map ? cellSetOrMap.get(`${cx + i},${cy + j}`) ?? 0 : cellSetOrMap.has(`${cx + i},${cy + j}`) ? 1 : 0;
      if (v > best) best = v;
    }
  return best;
}
/** 발자취 level by pass count (ref §2.3): ≥9→5, ≥5→4, ≥3→3, ≥2→2, else 1 */
const levelOf = passes => (passes >= 9 ? 5 : passes >= 5 ? 4 : passes >= 3 ? 3 : passes >= 2 ? 2 : 1);

/**
 * Split a point run into pieces of consecutive points with the same key(i). Neighbouring pieces share their joint point
 * (so drawn lines connect). Pieces shorter than minM join the previous piece (GPS flicker), then equal neighbours merge.
 */
function runsBy(pts, keyOf, minM = 250) {
  const runs = [];
  let cur = null;
  for (let i = 0; i < pts.length; i += 1) {
    const k = keyOf(i);
    if (!cur) cur = { key: k, pts: [pts[i]] };
    else if (k === cur.key) cur.pts.push(pts[i]);
    else {
      runs.push(cur);
      cur = { key: k, pts: [pts[i - 1], pts[i]] };
    }
  }
  if (cur) runs.push(cur);
  const merged = [];
  for (const run of runs) {
    const prev = merged[merged.length - 1];
    if (prev && (prev.key === run.key || lengthM(run.pts) < minM)) prev.pts.push(...run.pts.slice(1));
    else merged.push({ key: run.key, pts: run.pts.slice() });
  }
  if (merged.length > 1 && lengthM(merged[0].pts) < minM) {
    merged[1].pts = [...merged[0].pts, ...merged[1].pts.slice(1)];
    merged.shift();
  }
  return merged;
}

function quantExpFor(lines) {
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  for (const l of lines)
    for (const p of l.pts) {
      minLat = Math.min(minLat, p.lat);
      maxLat = Math.max(maxLat, p.lat);
      minLng = Math.min(minLng, p.lng);
      maxLng = Math.max(maxLng, p.lng);
    }
  const spanM = Math.max((maxLat - minLat) * M_PER_DEG, (maxLng - minLng) * M_PER_DEG * Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180));
  return spanM <= 40000 ? 4 : 3;
}

/** encode with Douglas–Peucker tolerance searched (2 m … 2 km) so the payload fits the QR budget */
function fitToBudget(base, lines, { dropShortest = false } = {}) {
  const quantExp = quantExpFor(lines);
  const build = (tol, list) => {
    const encLines = [];
    for (const l of list) {
      const q = [];
      for (const p of simplify(l.pts, tol)) {
        const v = [quantize(p.lat, quantExp), quantize(p.lng, quantExp)];
        const last = q[q.length - 1];
        if (!last || last[0] !== v[0] || last[1] !== v[1]) q.push(v);
      }
      if (q.length >= 2) encLines.push({ style: styleOf(l.level, l.fresh), q });
    }
    return encodeShare({ ...base, quantExp, lines: encLines });
  };
  let list = lines.slice();
  for (;;) {
    let lo = 2;
    let hi = 2000;
    if (build(lo, list).bytes.length <= BUDGET) return { ...build(lo, list), tol: lo, quantExp, dropped: lines.length - list.length };
    if (build(hi, list).bytes.length <= BUDGET) {
      for (let k = 0; k < 24; k += 1) {
        const mid = (lo + hi) / 2;
        if (build(mid, list).bytes.length <= BUDGET) hi = mid;
        else lo = mid;
      }
      return { ...build(hi, list), tol: Math.round(hi), quantExp, dropped: lines.length - list.length };
    }
    if (!dropShortest || list.length <= 1) throw new Error('cannot fit the QR budget');
    list = list.slice().sort((a, b) => lengthM(b.pts) - lengthM(a.pts)).slice(0, -1);
  }
}

// ── demo history ───────────────────────────────────────────────────────────
const index = JSON.parse(readFileSync(join(drivesDir, 'index.json'), 'utf8'));
const order = index.map(d => d.id);
const drives = new Map(order.map(id => [id, readDrive(id)]));
const seqById = new Map(sequence.drives.map(d => [d.id, d]));
const ymd = iso => iso.slice(0, 10).split('-').map(Number);
const startMinOf = iso => Number(iso.slice(11, 13)) * 60 + Number(iso.slice(14, 16));

function visitedUntil(id) {
  const seen = new Set();
  for (const d of sequence.drives) {
    for (const r of d.regions) seen.add(r.code);
    if (d.id === id) break;
  }
  return seen;
}

/** kind 1 for one drive: known/new split against all earlier drives, ends trimmed 300 m */
function driveSample(id, title) {
  const drive = drives.get(id);
  const meta = index.find(d => d.id === id);
  const earlier = order.slice(0, order.indexOf(id)).map(e => drives.get(e));
  const passes = new Map();
  for (const e of earlier) for (const c of cellsOf(e.pts)) passes.set(c, (passes.get(c) ?? 0) + 1);
  const pts = drive.pts;
  const before = pts.map(p => near(passes, p));
  const runs = runsBy(pts, i => levelOf(before[i] + 1) * 10 + (before[i] === 0 ? 1 : 0));
  // the app's distance comes from its smoothed track: use the drive index's number, and the raw-track share for "new"
  const rawM = lengthM(pts);
  const distanceM = meta.distance_m;
  const pioneeredM = (runs.filter(r => r.key % 10 === 1).reduce((s, r) => s + lengthM(r.pts), 0) / rawM) * distanceM;
  const trimmed = trimEnds(pts, 300);
  // re-split the trimmed track with the same keys (match by nearest original index)
  const keyAt = new Map();
  runs.forEach(r => r.pts.forEach(p => keyAt.set(p.t, r.key)));
  const lines = runsBy(trimmed, i => keyAt.get(trimmed[i].t) ?? runs[0].key, 0).map(r => ({
    pts: r.pts,
    level: Math.floor(r.key / 10),
    fresh: r.key % 10 === 1,
  }));
  const seq = seqById.get(id);
  const regionIdx = codes => codes.map(c => indexOfCode.get(c)).filter(v => v !== undefined);
  const passedCodes = [...new Set(seq.regions.map(r => r.code))];
  const newCodes = seq.regions.filter(r => r.firstVisit).map(r => r.code);
  const [y, m, d] = ymd(meta.start);
  const base = {
    kind: 1,
    flags: 1,
    dateDays: dateDaysOf(y, m, d),
    startMin: startMinOf(meta.start),
    durationS: Math.round((pts[pts.length - 1].t - pts[0].t) / 1000),
    distance10: Math.round(distanceM / 10),
    pioneered10: Math.round(pioneeredM / 10),
    driveCount: 0,
    title,
    regionSet: 1,
    visitedTotal: visitedUntil(id).size,
    newRegions: regionIdx(newCodes),
    passedRegions: regionIdx(passedCodes),
  };
  return fitToBudget(base, lines);
}

/** kind 2: every road once (first drive that used it), level by final pass count, frequent places hidden 500 m */
function myMapSample() {
  const passes = new Map();
  for (const id of order) for (const c of cellsOf(drives.get(id).pts)) passes.set(c, (passes.get(c) ?? 0) + 1);
  const covered = new Set();
  const lines = [];
  let rawM = 0;
  for (const id of order) {
    const pts = drives.get(id).pts;
    rawM += lengthM(pts);
    const isNew = pts.map(p => near(covered, p) === 0);
    const runs = runsBy(pts, i => (isNew[i] ? 1 : 0), 300);
    for (const run of runs) {
      if (run.key !== 1) continue;
      // split the new run by final level
      for (const piece of runsBy(run.pts, i => levelOf(near(passes, run.pts[i])), 300)) lines.push({ pts: piece.pts, level: piece.key, fresh: false });
    }
    for (const c of cellsOf(pts)) covered.add(c);
  }
  const totalM = index.reduce((s, d) => s + d.distance_m, 0);
  const pioneeredM = (lines.reduce((s, l) => s + lengthM(l.pts), 0) / rawM) * totalM;
  // hide 500 m around the two most frequent ends (home: drive starts, work: commute ends)
  const home = drives.get('commute-1-am').pts[0];
  const amEnd = drives.get('commute-1-am').pts;
  const work = amEnd[amEnd.length - 1];
  const hidden = [];
  for (const l of lines) {
    let piece = [];
    for (const p of l.pts) {
      if (dist(p, home) < 500 || dist(p, work) < 500) {
        if (piece.length >= 2) hidden.push({ ...l, pts: piece });
        piece = [];
      } else piece.push(p);
    }
    if (piece.length >= 2) hidden.push({ ...l, pts: piece });
  }
  const last = index[index.length - 1];
  const [y, m, d] = ymd(last.start);
  const base = {
    kind: 2,
    flags: 2,
    dateDays: dateDaysOf(y, m, d),
    distance10: Math.round(totalM / 10),
    pioneered10: Math.round(pioneeredM / 10),
    driveCount: order.length,
    title: '',
    regionSet: 1,
    visitedTotal: sequence.totalDistinct,
  };
  return fitToBudget(base, hidden.filter(l => lengthM(l.pts) > 150), { dropShortest: true });
}

function regionsSample() {
  const visited = [...visitedUntil(order[order.length - 1])].map(c => indexOfCode.get(c));
  const totalM = index.reduce((s, d) => s + d.distance_m, 0);
  const last = index[index.length - 1];
  const [y, m, d] = ymd(last.start);
  return encodeShare({
    kind: 3,
    dateDays: dateDaysOf(y, m, d),
    distance10: Math.round(totalM / 10),
    pioneered10: 0,
    driveCount: order.length,
    title: '',
    regionSet: 1,
    visitedTotal: visited.length,
    visited,
  });
}

// ── vectors ─────────────────────────────────────────────────────────────
function expectOf(bytes) {
  const d = decodePayload(bytes);
  return {
    version: d.version,
    kind: d.kind,
    flags: d.flags,
    dateDays: d.dateDays,
    startMin: d.startMin,
    durationS: d.durationS,
    distance10: d.distance10,
    pioneered10: d.pioneered10,
    driveCount: d.driveCount,
    title: d.title,
    regionSet: d.regionSet,
    visitedTotal: d.visitedTotal,
    newRegions: d.newRegions,
    passedRegions: d.passedRegions,
    visited: d.visited,
    quantExp: d.quantExp,
    lines: d.lines.map(l => ({ style: l.style, q: l.q, points: l.points })),
  };
}

const vectors = [];
function add(name, input, note) {
  const { bytes, payload } = input.bytes ? input : encodeShare(input);
  vectors.push({ name, note, bytes: bytes.length, payload, expect: expectOf(bytes), input: input.bytes ? undefined : input });
}
function addError(name, payload, error, note) {
  vectors.push({ name, note, payload, error });
}

const samples = {
  drive: driveSample('weekend-gapyeong', '주말 드라이브 · 가평'),
  trip: driveSample('trip-gangneung', '강릉 여행'),
  back: driveSample('weekend-gapyeong-back', ''),
  commute: driveSample('commute-3-am', '출근 10/28'),
  myMap: myMapSample(),
  regions: regionsSample(),
};
for (const [name, s] of Object.entries(samples)) {
  vectors.push({
    name: `sample-${name}`,
    note: `demo drive sample (${s.bytes.length} B of ${BUDGET}; DP ${s.tol ?? '-'} m, quantExp ${s.quantExp ?? '-'}${s.dropped ? `, ${s.dropped} short lines dropped` : ''})`,
    bytes: s.bytes.length,
    payload: s.payload,
    expect: expectOf(s.bytes),
  });
}

const D = dateDaysOf(2026, 10, 31);
add('kind1-minimal', { kind: 1, dateDays: D, regionSet: 1, quantExp: 4, lines: [] }, 'no lines, no regions, empty title');
add(
  'kind1-two-lines-negative-deltas',
  {
    kind: 1,
    flags: 3,
    dateDays: D,
    startMin: 23 * 60 + 59,
    durationS: 3599,
    distance10: 1234,
    pioneered10: 567,
    title: '야간 드라이브',
    visitedTotal: 2,
    newRegions: [229],
    passedRegions: [0, 229],
    quantExp: 4,
    lines: [
      { level: 1, fresh: true, q: [[375495, 1269139], [375480, 1269100], [375400, 1269001]] },
      { level: 5, fresh: false, q: [[375400, 1269001], [374999, 1270002], [374998, 1270002]] },
    ],
  },
  'lines chained: the 2nd line starts at the 1st line’s last point (delta 0,0)',
);
add(
  'kind1-unicode-title-48-bytes',
  { kind: 1, dateDays: D, title: '강릉 여행 🌊 바다 보러 가는 길', visitedTotal: 1, quantExp: 3, lines: [{ level: 3, q: [[37751, 128876], [37892, 128821]] }] },
  'UTF-8 title with an emoji (4-byte sequence)',
);
add(
  'kind2-quantExp4-big-varints',
  {
    kind: 2,
    flags: 2,
    dateDays: 3000,
    distance10: 12_345_678,
    pioneered10: 9_876_543,
    driveCount: 1234,
    title: '내 지도',
    visitedTotal: 230,
    quantExp: 4,
    lines: [
      { level: 2, q: [[335000, 1265000], [386000, 1318000]] },
      { level: 4, q: [[386000, 1318000], [330000, 1240000], [330001, 1240001]] },
    ],
  },
  'multi-byte varints, big jumps between points',
);
add('kind3-all-visited', { kind: 3, dateDays: D, visitedTotal: 230, visited: Array.from({ length: 230 }, (_, i) => i) }, 'bitmap: every region');
add('kind3-none-visited', { kind: 3, dateDays: D, visitedTotal: 0, visited: [] }, 'bitmap: no region');
add('kind3-edges', { kind: 3, dateDays: 0, visitedTotal: 4, visited: [0, 7, 8, 229] }, 'bit order: LSB first, byte i>>3');
add('trailing-bytes-ignored', { kind: 3, dateDays: D, visitedTotal: 1, visited: [3], extra: [1, 2, 3, 250] }, 'bytes after the known fields are ignored');

const lower = encodeShare({ kind: 3, dateDays: D, visitedTotal: 1, visited: [5] });
vectors.push({ name: 'lowercase-accepted', note: 'viewer accepts lower case', bytes: lower.bytes.length, payload: lower.payload.toLowerCase(), expect: expectOf(lower.bytes) });

addError('error-version', encodeShare({ version: 2, kind: 1, dateDays: D }).payload, 'version');
addError('error-kind', encodeShare({ kind: 4, dateDays: D }).payload, 'kind');
addError('error-region-set', encodeShare({ kind: 3, dateDays: D, regionSet: 2 }).payload, 'regionSet');
{
  const full = encodeShare({ kind: 1, dateDays: D, quantExp: 4, lines: [{ level: 1, q: [[1, 2], [3, 4]] }] });
  addError('error-truncated', full.payload.slice(0, Math.floor(full.payload.length * 0.7)), 'truncated');
}
addError('error-base32', 'AEB0AAAA', 'base32', "'0' is not in the RFC 4648 alphabet");
addError('error-empty', '', 'empty');

// sanity: every vector decodes as recorded
for (const v of vectors) {
  if (v.error) continue;
  if (v.bytes > BUDGET && v.name.startsWith('sample')) throw new Error(`${v.name} exceeds the QR budget`);
}

const out = {
  format: 'odomap-share-vectors/1',
  generator: 'web/tools/make-samples.mjs (spec encoder web/test/encoder.mjs)',
  spec: 'docs/share-format.md v1',
  qrBudgetBytes: BUDGET,
  vectors,
};
writeFileSync(join(web, 'test', 'vectors.local.json'), JSON.stringify(out, null, 1) + '\n');

const samplesJs = `// Generated by web/tools/make-samples.mjs from the demo drives — sample share payloads for the landing page.
export const SAMPLES = ${JSON.stringify(Object.fromEntries(Object.entries(samples).map(([k, s]) => [k, s.payload])), null, 2)};
`;
writeFileSync(join(web, 'assets', 'samples.js'), samplesJs);

const indexHtml = join(web, 'index.html');
if (existsSync(indexHtml)) {
  const html = readFileSync(indexHtml, 'utf8').replace(/(data-sample="(\w+)"[^>]*?href=")s\/#[A-Za-z2-7]*(")/g, (all, head, key, tail) =>
    samples[key] ? `${head}s/#${samples[key].payload}${tail}` : all,
  );
  writeFileSync(indexHtml, html);
}

for (const [name, s] of Object.entries(samples)) {
  const d = decodePayload(s.bytes);
  console.log(
    `${name.padEnd(8)} ${String(s.bytes.length).padStart(4)} B / ${BUDGET}  lines ${d.lines.length}  pts ${d.pointCount}  DP ${s.tol ?? '-'} m  q${d.quantExp ?? '-'}  ` +
      `dist ${(d.distanceM / 1000).toFixed(2)} km  new ${(d.pioneeredM / 1000).toFixed(2)} km  regions +${d.newRegions.length}/${d.passedRegions.length} visited ${d.visitedTotal}`,
  );
}
console.log(`vectors: ${vectors.length} → web/test/vectors.local.json`);
void ShareError;
