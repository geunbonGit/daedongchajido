// Share card renderer (Canvas 2D) — a port of 발자취 drawExploreCard / exploreCardModel (design-footprint-ref §7, design.md §6).
// Everything is laid out in design units (card width 360) and drawn with ctx scaled to the output (×3 → 1080 px), so the
// preview, the PNG and the video frames are the same picture. All sizes and colors come from ODOMAP_TOKENS (tokens.js).
//
//   paper (+ 44-unit grid) → route lines (one ink, thicker for roads driven more often; one layer at 0.92 so overlaps never
//   darken) or, for kind 3, region shapes → text: date · 한마디 · big number + unit · label · stats line · 대동차지도 wordmark.
import { KIND } from './share-decode.js';
import { cardDateLabel, cardNumbers, cardTitleText } from './format.js';

const tokens = () => {
  const t = globalThis.ODOMAP_TOKENS;
  if (!t) throw new Error('tokens.js must be loaded before card.js');
  return t;
};

const EARTH_RADIUS_M = 6371008.8; // 발자취 geo (IUGG mean radius)
const M_PER_DEG = (EARTH_RADIUS_M * Math.PI) / 180;
const MAPLIBRE_EARTH_M = 2 * Math.PI * 6378137;
const MAPLIBRE_TILE_PT = 512;

export const RATIOS = ['square', 'story'];

/** Output size in px for a ratio: 1080×1080 · 1080×1920 */
export function cardPixelSize(ratio) {
  const c = tokens().card;
  return { width: c.exportW, height: Math.round((c.heights[ratio] * c.exportW) / c.designW) };
}

/** Grid line positions along a side, centered so both margins match (발자취 gridPositions) */
export function gridPositions(length, cell) {
  const out = [];
  if (!(length > 0) || !(cell > 0)) return out;
  for (let at = (length % cell) / 2; at <= length + 1e-6; at += cell) out.push(Math.round(at * 1000) / 1000);
  return out;
}

/** 발자취 cardLayout: positions in design units for a ratio, with or without the 한마디 line */
export function cardLayout(ratio, hasTitle) {
  const c = tokens().card;
  const width = c.designW;
  const height = c.heights[ratio];
  const frame = c.frames[ratio];
  const dateTop = frame.top;
  const dateBottom = dateTop + c.type.date * c.inkH.hangul;
  const titleBaseline = hasTitle ? dateBottom + c.titleGap + c.type.title * c.inkH.hangul : null;
  const headBottom = titleBaseline ?? dateBottom;
  const statsBaseline = height - frame.bottom - 1;
  const labelBaseline = statsBaseline - c.labelGap;
  const heroBaseline = labelBaseline - c.type.label * c.inkH.hangul - c.heroGap;
  const heroTop = heroBaseline - c.type.hero * c.inkH.digit;
  const top = headBottom + frame.gap;
  const bottom = heroTop - frame.gap;
  return {
    ratio,
    width,
    height,
    padX: frame.padX,
    dateTop,
    titleBaseline,
    box: { x: frame.padX, y: top, w: width - frame.padX * 2, h: Math.max(40, bottom - top) },
    heroBaseline,
    labelBaseline,
    statsBaseline,
  };
}

// ── fitting & projection (발자취 cardFit: north up, equirectangular around the middle latitude) ───────────────────
function boundsOf(rings) {
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  for (const ring of rings)
    for (const [lat, lng] of ring) {
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
      if (lng < minLng) minLng = lng;
      if (lng > maxLng) maxLng = lng;
    }
  return Number.isFinite(minLat) ? { minLat, maxLat, minLng, maxLng } : null;
}

export function cardFit(bounds, box, minSpanM) {
  if (!bounds || !(box.w > 0) || !(box.h > 0)) return null;
  const midLat = (bounds.minLat + bounds.maxLat) / 2;
  const midLng = (bounds.minLng + bounds.maxLng) / 2;
  const kx = M_PER_DEG * Math.cos((midLat * Math.PI) / 180);
  const ky = M_PER_DEG;
  const spanX = Math.max((bounds.maxLng - bounds.minLng) * kx, minSpanM);
  const spanY = Math.max((bounds.maxLat - bounds.minLat) * ky, minSpanM);
  return { midLat, midLng, kx, ky, scale: Math.min(box.w / spanX, box.h / spanY), cx: box.x + box.w / 2, cy: box.y + box.h / 2 };
}

const project = (fit, lat, lng) => [fit.cx + (lng - fit.midLng) * fit.kx * fit.scale, fit.cy - (lat - fit.midLat) * fit.ky * fit.scale];

/** Equivalent MapLibre zoom of a fit (1 design unit = 1 pt), as in 발자취 cardLevelWidths */
function fitZoom(fit) {
  const cos = Math.max(0.01, Math.cos((fit.midLat * Math.PI) / 180));
  return Math.log2((Math.max(1e-9, fit.scale) * MAPLIBRE_EARTH_M * cos) / MAPLIBRE_TILE_PT);
}

/** (base, step) of the map line widths at a zoom (EXPLORE_LEVEL_WIDTH_STOPS, linear between stops) */
export function levelWidthAt(zoom) {
  const stops = tokens().card.widthStops;
  const first = stops[0];
  const last = stops[stops.length - 1];
  if (!(zoom > first[0])) return { base: first[1], step: first[2] };
  if (zoom >= last[0]) return { base: last[1], step: last[2] };
  let i = 1;
  while (stops[i][0] < zoom) i += 1;
  const [z0, b0, s0] = stops[i - 1];
  const [z1, b1, s1] = stops[i];
  const u = (zoom - z0) / (z1 - z0);
  return { base: b0 + (b1 - b0) * u, step: s0 + (s1 - s0) * u };
}

/** Card line width per level 1–5 (index 0 unused): level 1 = walked width, each level thicker by the map's ratio */
export function levelWidths(fit) {
  const walked = tokens().card.line.walked;
  const { base, step } = levelWidthAt(fit ? fitZoom(fit) : 12);
  const out = [walked];
  for (let level = 1; level <= 5; level += 1) out.push(Math.round(walked * ((base + step * (level - 1)) / base) * 100) / 100);
  return out;
}

// ── scene ────────────────────────────────────────────────────────────────
function decodeOutlineRing(flat, scale) {
  const pts = new Array(flat.length / 2);
  let lat = 0;
  let lng = 0;
  for (let i = 0, k = 0; i < flat.length; i += 2, k += 1) {
    lat += flat[i];
    lng += flat[i + 1];
    pts[k] = [lat / scale, lng / scale];
  }
  return pts;
}

/** outline.json → per region: array of rings ([lat, lng] degrees); cached on the object */
export function outlineRings(outline) {
  if (!outline) return null;
  if (!outline._rings) outline._rings = outline.regions.map(polys => polys.flatMap(rings => rings.map(r => decodeOutlineRing(r, outline.scale))));
  return outline._rings;
}

/**
 * Everything needed to draw one card (no canvas work yet).
 * opts: { ratio: 'square'|'story', look: token look id, title: string|undefined (undefined → the payload's), outline }
 */
export function cardScene(share, opts = {}) {
  const T = tokens();
  const ratio = opts.ratio === 'story' ? 'story' : 'square';
  const colors = T.uiColors(opts.look || T.defaultLook);
  const numbers = cardNumbers(share);
  let title = cardTitleText(opts.title !== undefined ? opts.title : share.title, T.card.titleMaxChars);
  // the app names the cumulative cards "내 지도" — same words as the label under the big number: say it once
  if (title === numbers.label) title = '';
  const layout = cardLayout(ratio, title.length > 0);
  const scene = {
    share,
    ratio,
    layout,
    colors,
    date: cardDateLabel(share.date),
    title: title || null,
    numbers,
    lines: [],
    widths: null,
    total: 0,
    regions: null,
    credit: null,
  };

  if (share.kind === KIND.REGIONS) {
    const rings = outlineRings(opts.outline);
    if (rings) {
      const fit = cardFit(boundsOf(rings.flat()), layout.box, 0);
      const visited = new Set(share.visited);
      const all = new Path2DMaker();
      const hit = new Path2DMaker();
      const order = [];
      rings.forEach((regionRings, i) => {
        for (const ring of regionRings) {
          const xy = ring.map(([lat, lng]) => project(fit, lat, lng));
          all.ring(xy);
          if (visited.has(i)) hit.ring(xy);
        }
        if (visited.has(i)) order.push(i);
      });
      // reveal order for the video: west → east sweep
      const centerLng = i => {
        const b = boundsOf(rings[i]);
        return b ? (b.minLng + b.maxLng) / 2 : 0;
      };
      order.sort((a, b) => centerLng(a) - centerLng(b));
      const each = new Map(order.map(i => [i, new Path2DMaker()]));
      for (const i of order) for (const ring of rings[i]) each.get(i).ring(ring.map(([lat, lng]) => project(fit, lat, lng)));
      scene.regions = { all: all.path(), visited: hit.path(), order: order.map(i => each.get(i).path()) };
      scene.credit = T.card.credit;
    }
    return scene;
  }

  const lines = share.lines.filter(l => l.points.length >= 2);
  const fit = cardFit(boundsOf(lines.map(l => l.points)), layout.box, T.card.line.minSpanM);
  scene.widths = levelWidths(fit);
  if (fit) {
    let s = 0;
    scene.lines = lines.map(l => {
      const xy = smoothLine(
        l.points.map(([lat, lng]) => project(fit, lat, lng)),
        T.card.line.smoothStep,
        T.card.line.smoothPasses,
      );
      const cum = new Float64Array(xy.length);
      for (let i = 1; i < xy.length; i += 1) cum[i] = cum[i - 1] + Math.hypot(xy[i][0] - xy[i - 1][0], xy[i][1] - xy[i - 1][1]);
      const line = { xy, cum, length: cum[cum.length - 1], start: s, level: l.level, fresh: l.fresh };
      s += line.length;
      return line;
    });
    scene.total = s;
    // draw thin under thick (all in one opaque layer, then one 0.92 composite)
    scene.drawOrder = scene.lines.map((l, i) => i).sort((a, b) => scene.lines[a].level - scene.lines[b].level || a - b);
  }
  return scene;
}

/**
 * The payload carries Douglas–Peucker-simplified lines; drawn as-is their corners look spiky at card scale. Like the
 * 발자취 map (which smooths [1,2,1]/4 twice before drawing, ends fixed), resample every `step` units and smooth `passes`
 * times. Ends stay put, so lines that meet still meet.
 */
export function smoothLine(xy, step = 1.5, passes = 2) {
  if (xy.length < 3 || !(step > 0) || passes <= 0) return xy;
  const dense = [xy[0]];
  for (let i = 1; i < xy.length; i += 1) {
    const [ax, ay] = xy[i - 1];
    const [bx, by] = xy[i];
    const n = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / step));
    for (let k = 1; k <= n; k += 1) dense.push([ax + ((bx - ax) * k) / n, ay + ((by - ay) * k) / n]);
  }
  let cur = dense;
  for (let p = 0; p < passes; p += 1) {
    const next = cur.slice();
    for (let i = 1; i < cur.length - 1; i += 1) {
      next[i] = [(cur[i - 1][0] + 2 * cur[i][0] + cur[i + 1][0]) / 4, (cur[i - 1][1] + 2 * cur[i][1] + cur[i + 1][1]) / 4];
    }
    cur = next;
  }
  return cur;
}

/** Accumulates rings into one Path2D (one fill/stroke call → shared borders are painted once) */
class Path2DMaker {
  constructor() {
    this.p = new Path2D();
    this.n = 0;
  }
  ring(xy) {
    if (xy.length < 3) return;
    this.p.moveTo(xy[0][0], xy[0][1]);
    for (let i = 1; i < xy.length; i += 1) this.p.lineTo(xy[i][0], xy[i][1]);
    this.p.closePath();
    this.n += 1;
  }
  path() {
    return this.p;
  }
}

// ── drawing ────────────────────────────────────────────────────────────
function fontOf(weight, size) {
  const T = tokens();
  return `${weight} ${size}px '${T.font.family}', ${T.font.fallback}`;
}

/** text whose ink starts exactly at x (Skia drawLeft); returns the ink box */
function drawLeft(ctx, text, x, baseline, font, color) {
  ctx.font = font;
  const m = ctx.measureText(text);
  if (text) {
    ctx.fillStyle = color;
    ctx.fillText(text, x + m.actualBoundingBoxLeft, baseline);
  }
  return { width: m.actualBoundingBoxLeft + m.actualBoundingBoxRight, ascent: m.actualBoundingBoxAscent, descent: m.actualBoundingBoxDescent };
}

function inkWidth(ctx, text, font) {
  ctx.font = font;
  const m = ctx.measureText(text);
  return m.actualBoundingBoxLeft + m.actualBoundingBoxRight;
}

function drawPaper(ctx, scene) {
  const { width: W, height: H } = scene.layout;
  const { colors } = scene;
  const g = tokens().grid;
  ctx.fillStyle = colors.paper;
  ctx.fillRect(0, 0, W, H);
  if (colors.grid) {
    const grid = new Path2D();
    for (const x of gridPositions(W, g.cell)) grid.rect(x - g.line / 2, 0, g.line, H);
    for (const y of gridPositions(H, g.cell)) grid.rect(0, y - g.line / 2, W, g.line);
    ctx.fillStyle = colors.grid;
    ctx.fill(grid); // one fill: crossings are not painted twice
  }
}

/** 대동차지도 wordmark (word only, no symbol), vertically centered on cy; width = the word's ink width. */
export function lockupWidth(ctx, h) {
  const b = tokens().card.brand;
  return inkWidth(ctx, b.word, fontOf(tokens().card.weight.brand, h * b.wordK));
}

function drawLockup(ctx, x, cy, h, wordColor) {
  const T = tokens();
  const b = T.card.brand;
  const font = fontOf(T.card.weight.brand, h * b.wordK);
  ctx.font = font;
  const m = ctx.measureText(b.word);
  // Hangul wordmark: the word's own ink box is centered on cy and starts at x
  const baseline = cy + (m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2;
  ctx.fillStyle = wordColor;
  ctx.fillText(b.word, x + m.actualBoundingBoxLeft, baseline);
}

/** 한마디 on one line: shrink to fit (down to titleMin), then ellipsis */
function fitTitle(ctx, title, maxWidth) {
  const t = tokens().card;
  let size = t.type.title;
  let font = fontOf(t.weight.title, size);
  const natural = inkWidth(ctx, title, font);
  if (natural <= maxWidth) return { text: title, font };
  size = Math.max(t.type.titleMin, Math.floor(((size * maxWidth) / natural) * 10) / 10);
  font = fontOf(t.weight.title, size);
  if (inkWidth(ctx, title, font) <= maxWidth) return { text: title, font };
  const chars = Array.from(title);
  while (chars.length > 1 && inkWidth(ctx, chars.join('') + '…', font) > maxWidth) chars.pop();
  return { text: chars.join('').trimEnd() + '…', font };
}

function drawText(ctx, scene) {
  const T = tokens();
  const t = T.card;
  const { layout, colors, numbers } = scene;
  const W = layout.width;
  const x0 = layout.padX;
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';

  // top: date (ink top at dateTop) · 한마디
  const dateFont = fontOf(t.weight.date, t.type.date);
  ctx.font = dateFont;
  const dm = ctx.measureText(scene.date || '0');
  drawLeft(ctx, scene.date, x0, layout.dateTop + dm.actualBoundingBoxAscent, dateFont, colors.textSoft);
  if (scene.title && layout.titleBaseline !== null) {
    const fitted = fitTitle(ctx, scene.title, W - x0 * 2);
    drawLeft(ctx, fitted.text, x0, layout.titleBaseline, fitted.font, colors.text);
  }

  // bottom left: big number + unit · label
  const hero = drawLeft(ctx, numbers.hero, x0, layout.heroBaseline, fontOf(t.weight.hero, t.type.hero), colors.text);
  drawLeft(ctx, numbers.unit, x0 + hero.width + t.type.unitGap, layout.heroBaseline, fontOf(t.weight.unit, t.type.unit), colors.textSoft);
  drawLeft(ctx, numbers.label, x0, layout.labelBaseline, fontOf(t.weight.label, t.type.label), colors.textSoft);

  // bottom right wordmark; the stats line shrinks (to statsMin) instead of touching it
  const brandH = t.type.brand;
  const brandW = lockupWidth(ctx, brandH);
  const room = W - x0 * 2 - brandW - t.brand.room;
  let statsFont = fontOf(t.weight.stats, t.type.stats);
  const statsWidth = inkWidth(ctx, numbers.stats, statsFont);
  if (statsWidth > room && statsWidth > 0) statsFont = fontOf(t.weight.stats, Math.max(t.type.statsMin, (t.type.stats * room) / statsWidth));
  const stats = drawLeft(ctx, numbers.stats, x0, layout.statsBaseline, statsFont, colors.text);
  const brandCy = stats.ascent + stats.descent > 0 ? layout.statsBaseline + (stats.descent - stats.ascent) / 2 : layout.statsBaseline - t.type.stats * 0.36;
  drawLockup(ctx, W - x0 - brandW, brandCy, brandH, colors[t.brand.wordColor] ?? colors.textSoft);

  // kind 3: tiny source credit in the box's bottom-right corner (open sea on the national map)
  if (scene.credit) {
    const c = scene.credit;
    const font = fontOf(c.weight, c.size);
    const [a, b] = c.text.split(' · ');
    const right = layout.box.x + layout.box.w;
    const bottom = layout.box.y + layout.box.h;
    ctx.font = font;
    ctx.fillStyle = colors.textSoft;
    ctx.textAlign = 'right';
    ctx.fillText(b ?? '', right, bottom);
    ctx.fillText(a, right, bottom - c.size * 1.25);
    ctx.textAlign = 'left';
  }
}

function makeCanvas(w, h) {
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h);
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

/** polyline xy cut at length a (0 < a < line.length) */
function partial(line, a) {
  const { xy, cum } = line;
  let i = 1;
  while (i < xy.length && cum[i] < a) i += 1;
  const out = xy.slice(0, i);
  const seg = cum[i] - cum[i - 1];
  const f = seg > 0 ? (a - cum[i - 1]) / seg : 0;
  out.push([xy[i - 1][0] + (xy[i][0] - xy[i - 1][0]) * f, xy[i - 1][1] + (xy[i][1] - xy[i - 1][1]) * f]);
  return out;
}

function headAt(scene, a) {
  for (const line of scene.lines) {
    if (a <= line.start + line.length) {
      const local = a - line.start;
      if (local <= 0) return line.xy[0];
      const p = partial(line, local);
      return p[p.length - 1];
    }
  }
  const last = scene.lines[scene.lines.length - 1];
  return last ? last.xy[last.xy.length - 1] : null;
}

/** Line layer: all lines opaque on their own canvas, composited once at the line alpha. `a` = drawn length (motion). */
function drawLines(ctx, scene, k, a, layerBox) {
  if (!scene.lines.length) return;
  const T = tokens();
  const W = scene.layout.width;
  const H = scene.layout.height;
  const pxW = Math.round(W * k);
  const pxH = Math.round(H * k);
  let layer = layerBox.layer;
  if (!layer || layer.width !== pxW || layer.height !== pxH) layer = layerBox.layer = makeCanvas(pxW, pxH);
  const lc = layer.getContext('2d');
  lc.setTransform(1, 0, 0, 1, 0, 0);
  lc.clearRect(0, 0, pxW, pxH);
  lc.setTransform(k, 0, 0, k, 0, 0);
  lc.strokeStyle = scene.colors.ink;
  lc.lineCap = 'round';
  lc.lineJoin = 'round';
  for (const i of scene.drawOrder) {
    const line = scene.lines[i];
    if (a <= line.start) continue;
    const pts = a >= line.start + line.length ? line.xy : partial(line, a - line.start);
    lc.lineWidth = scene.widths[line.level];
    lc.beginPath();
    lc.moveTo(pts[0][0], pts[0][1]);
    for (let j = 1; j < pts.length; j += 1) lc.lineTo(pts[j][0], pts[j][1]);
    lc.stroke();
  }
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = T.card.line.alpha;
  ctx.drawImage(layer, 0, 0);
  ctx.restore();
}

function drawRegions(ctx, scene, reveal) {
  const r = tokens().card.regions;
  const { regions, colors } = scene;
  if (!regions) return;
  ctx.save();
  ctx.lineJoin = 'round';
  ctx.fillStyle = colors.ink;
  if (reveal >= 1) {
    ctx.globalAlpha = r.visitedFillAlpha;
    ctx.fill(regions.visited, 'evenodd');
  } else if (reveal > 0) {
    // video: regions appear one after another (west → east), each fading in
    const n = regions.order.length;
    regions.order.forEach((path, i) => {
      const f = Math.min(1, Math.max(0, reveal * (n + 2) - i) / 3);
      if (f <= 0) return;
      ctx.globalAlpha = r.visitedFillAlpha * f;
      ctx.fill(path, 'evenodd');
    });
  }
  ctx.strokeStyle = colors.ink;
  ctx.globalAlpha = r.outlineAlpha;
  ctx.lineWidth = r.outline;
  ctx.stroke(regions.all);
  if (reveal > 0 && r.visitedEdgeAlpha > 0) {
    ctx.strokeStyle = colors[r.visitedEdgeColor] ?? colors.paper;
    ctx.globalAlpha = r.visitedEdgeAlpha * Math.min(1, reveal * 1.5);
    ctx.lineWidth = r.visitedEdge;
    ctx.stroke(regions.visited);
  }
  ctx.restore();
}

/** Motion timeline (ref §7): progress of the drawing at time t (ms), smoothstep */
export function drawProgress(t) {
  const m = tokens().card.motion;
  const r = (t - m.leadInMs) / m.drawMs;
  if (r <= 0) return 0;
  if (r >= 1) return 1;
  return r * r * (3 - 2 * r);
}

export function headAlpha(t) {
  const m = tokens().card.motion;
  const end = m.leadInMs + m.drawMs;
  if (t <= m.leadInMs) return 0;
  if (t < end) return 1;
  return Math.max(0, 1 - (t - end) / m.settleMs);
}

/** Total video length (ms): lead-in + drawing + head fade + hold */
export function motionLength() {
  const m = tokens().card.motion;
  return m.leadInMs + m.drawMs + m.settleMs + m.holdMs;
}

/**
 * Draws the card onto ctx (whose canvas is W·k × H·k px). t = time in ms for the video (Infinity = the finished card).
 * `cache` keeps the line layer between frames.
 */
export function drawCard(ctx, scene, k, t = Infinity, cache = {}) {
  const T = tokens();
  ctx.save();
  ctx.setTransform(k, 0, 0, k, 0, 0);
  drawPaper(ctx, scene);
  const f = t === Infinity ? 1 : drawProgress(t);
  if (scene.regions) drawRegions(ctx, scene, f);
  else if (scene.lines.length && f > 0) {
    drawLines(ctx, scene, k, f >= 1 ? Infinity : f * scene.total, cache);
    const alpha = t === Infinity ? 0 : headAlpha(t);
    const head = alpha > 0 ? headAt(scene, f * scene.total) : null;
    if (head) {
      const m = T.card.motion;
      ctx.setTransform(k, 0, 0, k, 0, 0);
      ctx.fillStyle = scene.colors.ink;
      ctx.globalAlpha = m.headHaloAlpha * alpha;
      ctx.beginPath();
      ctx.arc(head[0], head[1], m.headHaloR, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = alpha;
      ctx.beginPath();
      ctx.arc(head[0], head[1], m.headR, 0, Math.PI * 2);
      ctx.fill();
      ctx.globalAlpha = 1;
    }
  }
  ctx.setTransform(k, 0, 0, k, 0, 0);
  drawText(ctx, scene);
  ctx.restore();
}

/**
 * The text each font weight draws on this card: { 500: '…', 700: '…' }. The fonts are split into unicode-range chunks
 * (fonts/fonts.css), so loading exactly this text downloads only the chunks the card needs (usually ~15 + ~50 KB).
 */
export function cardFontText(share, opts = {}) {
  const T = tokens();
  const t = T.card;
  const title = cardTitleText(opts.title !== undefined ? opts.title : share.title, t.titleMaxChars);
  const n = cardNumbers(share);
  const parts = {
    date: cardDateLabel(share.date),
    title,
    hero: n.hero,
    unit: n.unit,
    label: n.label,
    stats: n.stats,
    brand: t.brand.word,
  };
  const out = {};
  for (const [role, text] of Object.entries(parts)) {
    const w = t.weight[role];
    out[w] = (out[w] || '') + text;
  }
  if (share.kind === KIND.REGIONS) out[t.credit.weight] = (out[t.credit.weight] || '') + t.credit.text;
  return out;
}

/**
 * Waits until the fonts for `texts` ({ weight: text }, see cardFontText) are loaded — canvas text does not wait for web
 * fonts by itself. Resolves true when loaded, false after timeoutMs (the card then draws with the fallback font; the
 * caller can redraw when `.done` settles).
 */
export function loadCardFonts(texts, timeoutMs = 5000) {
  if (typeof document === 'undefined' || !document.fonts) return Object.assign(Promise.resolve(false), { done: Promise.resolve(false) });
  const T = tokens();
  const all = texts || { 500: '가나 대동차지도 0123456789', 700: '가나 대동차지도 0123456789' };
  const done = Promise.all(
    Object.entries(all)
      .filter(([, text]) => text)
      .map(([w, text]) => document.fonts.load(`${w} 16px '${T.font.family}'`, text)),
  ).then(
    () => true,
    () => false,
  );
  let timer;
  const raced = Promise.race([
    done,
    new Promise(resolve => {
      timer = setTimeout(() => resolve(false), timeoutMs);
    }),
  ]).then(ok => {
    clearTimeout(timer);
    return ok;
  });
  raced.done = done;
  return raced;
}

/** Renders the finished card at export size on a new <canvas>. */
export function renderCard(share, opts = {}) {
  const scene = cardScene(share, opts);
  const { width, height } = cardPixelSize(scene.ratio);
  const canvas = opts.canvas || document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  drawCard(ctx, scene, width / scene.layout.width);
  return { canvas, scene };
}

export function canvasToBlob(canvas, type = 'image/png', quality) {
  return new Promise((resolve, reject) => {
    if (canvas.convertToBlob) {
      canvas.convertToBlob({ type, quality }).then(resolve, reject);
      return;
    }
    canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('toBlob failed'))), type, quality);
  });
}
