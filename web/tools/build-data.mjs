#!/usr/bin/env node
// Builds the region data the web viewer needs from tools/regions/out (no dependencies):
//   web/data/regions.json — region index table. Index = array order of tools/regions/out/regions.json `regions`
//                           (= the region number used in the QR payload, regionSet 1 = sgis-20261001).
//   web/data/outline.json — display-only simplified outlines (from regions-outline.json, 700 m tolerance),
//                           re-quantized to 1e-4° and delta-encoded so the file stays small.
//
//   node web/tools/build-data.mjs            (run from the repo root or anywhere)
//
// Outline format "odomap-web-outline/1":
//   { format, regionSet, scale: 10000, source, regions: [ polygons ] }   regions[i] belongs to region i of regions.json
//   polygons = [ polygon, ... ], polygon = [ ring, ... ] (first ring outer, rest holes — draw with the even-odd rule)
//   ring = [lat0, lng0, dLat1, dLng1, dLat2, dLng2, ...] integers in 1e-4° (first point absolute, then deltas),
//          latitude first, closing point omitted (same convention as tools/regions).
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, '..');
const src = join(web, '..', 'tools', 'regions', 'out');

const regionsSrc = JSON.parse(readFileSync(join(src, 'regions.json'), 'utf8'));
const outlineSrc = JSON.parse(readFileSync(join(src, 'regions-outline.json'), 'utf8'));

if (regionsSrc.regions.length !== 230) throw new Error(`expected 230 regions, got ${regionsSrc.regions.length}`);
const codes = regionsSrc.regions.map(r => r.code);
const outlineByCode = new Map(outlineSrc.regions.map(r => [r.code, r]));
for (const code of codes) if (!outlineByCode.has(code)) throw new Error(`outline missing region ${code}`);

const source = {
  name: regionsSrc.source.name,
  version: regionsSrc.source.version,
  license: regionsSrc.source.license,
  attribution: regionsSrc.source.attribution,
  attributionEn: regionsSrc.source.attributionEn,
};

// ── regions.json ────────────────────────────────────────────────────────
const round6 = v => Math.round(v * 1e6) / 1e6;
const regions = {
  format: 'odomap-web-regions/1',
  regionSet: 1,
  regionSetName: `sgis-${regionsSrc.source.version.replace(/^ver/, '')}`,
  count: regionsSrc.regions.length,
  note: '배열 순서 = QR 공유의 지역 번호(0부터). center = [위도, 경도] 도.',
  source,
  regions: regionsSrc.regions.map(r => ({
    code: r.code,
    label: r.label,
    name: r.name,
    sido: r.sido,
    center: [round6(r.center[0]), round6(r.center[1])],
  })),
};

// ── outline.json ────────────────────────────────────────────────────────
const SCALE = 1e4; // 1e-4° ≈ 11 m — far below the 700 m simplification of the source
const q = micro => Math.round((micro / 1e6) * SCALE);
let points = 0;
let droppedRings = 0;

function encodeRing(flat) {
  const pts = [];
  for (let i = 0; i < flat.length; i += 2) {
    const p = [q(flat[i]), q(flat[i + 1])];
    const last = pts[pts.length - 1];
    if (!last || last[0] !== p[0] || last[1] !== p[1]) pts.push(p);
  }
  // closing point is implicit: drop a duplicated first point at the end
  while (pts.length > 1 && pts[0][0] === pts[pts.length - 1][0] && pts[0][1] === pts[pts.length - 1][1]) pts.pop();
  if (pts.length < 3) return null;
  points += pts.length;
  const out = [pts[0][0], pts[0][1]];
  for (let i = 1; i < pts.length; i += 1) out.push(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
  return out;
}

const outline = {
  format: 'odomap-web-outline/1',
  regionSet: 1,
  scale: SCALE,
  note: '표시 전용(판정에 쓰지 말 것). regions[i] = regions.json 의 i번 지역. ring = [위도0, 경도0, Δ위도, Δ경도, ...] (1e-4도 정수, 닫는 점 생략), even-odd 규칙.',
  simplifyToleranceM: outlineSrc.simplifyToleranceM,
  source,
  regions: codes.map(code =>
    outlineByCode
      .get(code)
      .polygons.map(poly => {
        const rings = [];
        for (let k = 0; k < poly.length; k += 1) {
          const ring = encodeRing(poly[k]);
          if (ring) rings.push(ring);
          else if (k === 0) return null; // outer ring collapsed → drop polygon
          else droppedRings += 1;
        }
        return rings;
      })
      .filter(Boolean),
  ),
};

mkdirSync(join(web, 'data'), { recursive: true });
const regionsText = JSON.stringify(regions);
const outlineText = JSON.stringify(outline);
writeFileSync(join(web, 'data', 'regions.json'), regionsText + '\n');
writeFileSync(join(web, 'data', 'outline.json'), outlineText + '\n');
console.log(`regions.json ${regionsText.length} B · outline.json ${outlineText.length} B (${points} points, ${droppedRings} tiny rings dropped)`);
