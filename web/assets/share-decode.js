// QR share payload decoder — docs/share-format.md (v1). Pure module (no DOM): the viewer and the Node tests use this same file.
//
//   https://geunbongit.github.io/odomap/s/#<PAYLOAD>
//   PAYLOAD = RFC 4648 base32 (A–Z 2–7), upper case, no '=' padding, of the binary layout below.
//
//   u8 version(=1) · u8 kind(1 drive, 2 my map, 3 regions) · u8 flags(bit0 ends hidden, bit1 frequent places hidden)
//   uv dateDays(since 2020-01-01, KST) · uv startMin · uv durationS · uv distance10 · uv pioneered10 · uv driveCount
//   str title(≤48 B) · u8 regionSet(1 = sgis-20261001, 230) · uv visitedTotal
//   kind 1: uv n + n×u8 new regions · uv m + m×u8 passed regions
//   kind 3: 29 bytes visited bitmap (region i → byte i>>3, bit i&7, LSB first)
//   kind 1·2: u8 quantExp · uv lineCount · per line: u8 style(bit0..2 level 1–5, bit3 new road) · uv pointCount ·
//             pointCount × (sv dLat, sv dLng) — deltas chained across lines, first point relative to (0, 0)
//   uv = unsigned LEB128, sv = zigzag + uv, str = uv byte length + UTF-8. Trailing bytes are ignored.

export const SHARE_VERSION = 1;
export const KIND = Object.freeze({ DRIVE: 1, MY_MAP: 2, REGIONS: 3 });
export const FLAG = Object.freeze({ HIDE_ENDS: 1, HIDE_FREQUENT: 2 });
/** Region sets the viewer knows: number → { name, count }. Region number = index in web/data/regions.json. */
export const REGION_SETS = Object.freeze({ 1: Object.freeze({ name: 'sgis-20261001', count: 230 }) });
export const BITMAP_BYTES = 29;
export const STYLE = Object.freeze({ LEVEL_MASK: 0x07, FRESH: 0x08 });

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const BASE32_INDEX = (() => {
  const table = new Int8Array(128).fill(-1);
  for (let i = 0; i < 32; i += 1) {
    table[BASE32.charCodeAt(i)] = i;
    table[BASE32.toLowerCase().charCodeAt(i)] = i; // be lenient: some apps lower-case links
  }
  return table;
})();

/**
 * Decoder error with a stable `code` the page maps to a friendly message:
 * empty · base32 · truncated · invalid · version · kind · regionSet
 */
export class ShareError extends Error {
  constructor(code, message) {
    super(message || code);
    this.name = 'ShareError';
    this.code = code;
  }
  /** true when the payload is well-formed but newer than this viewer ("앱을 업데이트해 주세요") */
  get unsupported() {
    return this.code === 'version' || this.code === 'kind' || this.code === 'regionSet';
  }
}

/** Base32 (RFC 4648) → bytes. Ignores '=', whitespace and case; leftover bits of the last group are dropped. */
export function base32Decode(text) {
  const clean = String(text ?? '').replace(/[\s=]/g, '');
  const out = new Uint8Array(Math.floor((clean.length * 5) / 8));
  let buffer = 0;
  let bits = 0;
  let n = 0;
  for (let i = 0; i < clean.length; i += 1) {
    const c = clean.charCodeAt(i);
    const v = c < 128 ? BASE32_INDEX[c] : -1;
    if (v < 0) throw new ShareError('base32', `not base32 at ${i}`);
    buffer = ((buffer << 5) | v) & 0xffff; // only the low ≤12 bits are ever needed
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out[n++] = (buffer >> bits) & 0xff;
    }
  }
  return out;
}

/** Bytes → base32 (upper case, no padding). Used by tests and the sample generator. */
export function base32Encode(bytes) {
  let out = '';
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < bytes.length; i += 1) {
    buffer = ((buffer << 8) | bytes[i]) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += BASE32[(buffer >> bits) & 31];
    }
  }
  if (bits > 0) out += BASE32[(buffer << (5 - bits)) & 31];
  return out;
}

class Reader {
  constructor(bytes) {
    this.b = bytes;
    this.i = 0;
  }
  get left() {
    return this.b.length - this.i;
  }
  need(n, what) {
    if (this.i + n > this.b.length) throw new ShareError('truncated', `payload ends inside ${what}`);
  }
  u8(what) {
    this.need(1, what);
    return this.b[this.i++];
  }
  /** unsigned LEB128 (exact up to 2^53) */
  uv(what) {
    let value = 0;
    let mul = 1;
    for (let k = 0; k < 8; k += 1) {
      const byte = this.u8(what);
      value += (byte & 0x7f) * mul;
      if ((byte & 0x80) === 0) return value;
      mul *= 128;
    }
    throw new ShareError('invalid', `varint too long in ${what}`);
  }
  /** zigzag signed varint */
  sv(what) {
    const n = this.uv(what);
    return n % 2 === 0 ? n / 2 : -(n + 1) / 2;
  }
  bytes(n, what) {
    this.need(n, what);
    const out = this.b.subarray(this.i, this.i + n);
    this.i += n;
    return out;
  }
  str(what) {
    const length = this.uv(what);
    return utf8(this.bytes(length, what));
  }
}

let decoder = null;
function utf8(bytes) {
  if (!decoder) decoder = new TextDecoder('utf-8', { fatal: false });
  return decoder.decode(bytes);
}

const DAY_MS = 86400000;
const EPOCH_2020 = Date.UTC(2020, 0, 1);

/** Days since 2020-01-01 (a KST calendar date) → { year, month, day, weekday (0 = Sunday) } */
export function dateFromDays(days) {
  const d = new Date(EPOCH_2020 + days * DAY_MS);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate(), weekday: d.getUTCDay() };
}

function regionList(r, count, what) {
  const n = r.uv(what);
  r.need(n, what);
  const out = [];
  let unknown = 0;
  for (let k = 0; k < n; k += 1) {
    const v = r.u8(what);
    if (v < count) out.push(v);
    else unknown += 1;
  }
  return { list: out, unknown };
}

/**
 * Decodes the binary payload. Throws ShareError. Coordinates are returned both as the transmitted integers (`q`)
 * and dequantized degrees (`points`, = integer / 10^quantExp).
 */
export function decodePayload(bytes) {
  if (!bytes || bytes.length === 0) throw new ShareError('empty', 'no payload');
  const r = new Reader(bytes);
  const version = r.u8('version');
  if (version !== SHARE_VERSION) throw new ShareError('version', `unknown version ${version}`);
  const kind = r.u8('kind');
  if (kind !== KIND.DRIVE && kind !== KIND.MY_MAP && kind !== KIND.REGIONS) throw new ShareError('kind', `unknown kind ${kind}`);
  const flags = r.u8('flags');
  const dateDays = r.uv('dateDays');
  const startMin = r.uv('startMin');
  const durationS = r.uv('durationS');
  const distance10 = r.uv('distance10');
  const pioneered10 = r.uv('pioneered10');
  const driveCount = r.uv('driveCount');
  const title = r.str('title');
  const regionSet = r.u8('regionSet');
  const set = REGION_SETS[regionSet];
  if (!set) throw new ShareError('regionSet', `unknown region set ${regionSet}`);
  const visitedTotal = r.uv('visitedTotal');

  const out = {
    version,
    kind,
    flags,
    hideEnds: (flags & FLAG.HIDE_ENDS) !== 0,
    hideFrequent: (flags & FLAG.HIDE_FREQUENT) !== 0,
    dateDays,
    date: dateFromDays(dateDays),
    startMin,
    durationS,
    distance10,
    pioneered10,
    driveCount,
    distanceM: distance10 * 10,
    pioneeredM: pioneered10 * 10,
    title,
    regionSet,
    regionCount: set.count,
    visitedTotal,
    newRegions: [],
    passedRegions: [],
    visited: [],
    visitedBitmap: null,
    unknownRegions: 0,
    quantExp: null,
    lines: [],
    pointCount: 0,
    bytes: bytes.length,
    extraBytes: 0,
  };

  if (kind === KIND.DRIVE) {
    const fresh = regionList(r, set.count, 'new regions');
    const passed = regionList(r, set.count, 'passed regions');
    out.newRegions = fresh.list;
    out.passedRegions = passed.list;
    out.unknownRegions = fresh.unknown + passed.unknown;
  }

  if (kind === KIND.REGIONS) {
    const bitmap = r.bytes(BITMAP_BYTES, 'visited bitmap');
    out.visitedBitmap = Uint8Array.from(bitmap);
    for (let i = 0; i < set.count && i < BITMAP_BYTES * 8; i += 1) if ((bitmap[i >> 3] >> (i & 7)) & 1) out.visited.push(i);
  }

  if (kind === KIND.DRIVE || kind === KIND.MY_MAP) {
    const quantExp = r.u8('quantExp');
    if (quantExp > 9) throw new ShareError('invalid', `quantExp ${quantExp}`);
    out.quantExp = quantExp;
    const div = 10 ** quantExp;
    const lineCount = r.uv('lineCount');
    // every line needs ≥ 2 bytes (style + count): reject absurd counts before allocating
    if (lineCount * 2 > r.left) throw new ShareError('truncated', `lineCount ${lineCount} exceeds payload`);
    let lat = 0;
    let lng = 0;
    for (let l = 0; l < lineCount; l += 1) {
      const style = r.u8('line style');
      const count = r.uv('pointCount');
      if (count * 2 > r.left) throw new ShareError('truncated', `pointCount ${count} exceeds payload`);
      const q = new Array(count);
      const points = new Array(count);
      for (let k = 0; k < count; k += 1) {
        lat += r.sv('dLat');
        lng += r.sv('dLng');
        q[k] = [lat, lng];
        points[k] = [lat / div, lng / div];
      }
      const rawLevel = style & STYLE.LEVEL_MASK;
      out.lines.push({ style, level: Math.min(5, Math.max(1, rawLevel)), fresh: (style & STYLE.FRESH) !== 0, q, points });
      out.pointCount += count;
    }
  }

  out.extraBytes = r.left;
  return out;
}

/** location.hash (or any string holding the payload) → decoded share. Throws ShareError. */
export function decodeShare(hash) {
  let text = String(hash ?? '').trim();
  if (text.startsWith('#')) text = text.slice(1);
  // tolerate a pasted full link
  const at = text.lastIndexOf('#');
  if (at >= 0) text = text.slice(at + 1);
  try {
    text = decodeURIComponent(text);
  } catch {
    /* keep raw */
  }
  text = text.replace(/[\s=]/g, '');
  if (!text) throw new ShareError('empty', 'no payload');
  return decodePayload(base32Decode(text));
}
