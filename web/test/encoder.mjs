// Reference ENCODER for docs/share-format.md v1, written from the spec independently of the viewer's decoder
// (own base32 / varint code) so the two cannot share a bug. Used by the tests and by tools/make-samples.mjs.
// The app's real encoder is Kotlin (core); its vectors (core/src/test/resources/share/vectors.json) are checked too.

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function toBase32(bytes) {
  let bits = '';
  for (const b of bytes) bits += b.toString(2).padStart(8, '0');
  while (bits.length % 5 !== 0) bits += '0';
  let out = '';
  for (let i = 0; i < bits.length; i += 5) out += ALPHABET[parseInt(bits.slice(i, i + 5), 2)];
  return out;
}

class Writer {
  constructor() {
    this.out = [];
  }
  u8(v) {
    if (!Number.isInteger(v) || v < 0 || v > 255) throw new Error(`u8 out of range: ${v}`);
    this.out.push(v);
  }
  uv(v) {
    if (!Number.isSafeInteger(v) || v < 0) throw new Error(`uv out of range: ${v}`);
    let n = BigInt(v);
    do {
      let byte = Number(n & 0x7fn);
      n >>= 7n;
      if (n > 0n) byte |= 0x80;
      this.out.push(byte);
    } while (n > 0n);
  }
  sv(v) {
    if (!Number.isSafeInteger(v)) throw new Error(`sv out of range: ${v}`);
    this.uv(v >= 0 ? v * 2 : -v * 2 - 1);
  }
  str(s) {
    const bytes = new TextEncoder().encode(s);
    this.uv(bytes.length);
    for (const b of bytes) this.out.push(b);
  }
  bytes() {
    return Uint8Array.from(this.out);
  }
}

/** Calendar date (KST) → days since 2020-01-01 */
export function dateDaysOf(year, month, day) {
  return Math.round((Date.UTC(year, month - 1, day) - Date.UTC(2020, 0, 1)) / 86400000);
}

/** Line style byte: bits 0..2 level 1–5, bit 3 new road */
export function styleOf(level, fresh) {
  return (level & 7) | (fresh ? 8 : 0);
}

/**
 * input: { version=1, kind, flags=0, dateDays, startMin=0, durationS=0, distance10=0, pioneered10=0, driveCount=0, title='',
 *          regionSet=1, visitedTotal=0, newRegions=[], passedRegions=[], visited=[] (kind 3 indices), quantExp=4,
 *          lines=[{ style | (level, fresh), q: [[latInt, lngInt], ...] }], extra: [] (trailing bytes) }
 */
export function encodeShare(input) {
  const w = new Writer();
  const kind = input.kind;
  w.u8(input.version ?? 1);
  w.u8(kind);
  w.u8(input.flags ?? 0);
  w.uv(input.dateDays);
  w.uv(input.startMin ?? 0);
  w.uv(input.durationS ?? 0);
  w.uv(input.distance10 ?? 0);
  w.uv(input.pioneered10 ?? 0);
  w.uv(input.driveCount ?? 0);
  w.str(input.title ?? '');
  w.u8(input.regionSet ?? 1);
  w.uv(input.visitedTotal ?? 0);
  if (kind === 1) {
    const fresh = input.newRegions ?? [];
    const passed = input.passedRegions ?? [];
    w.uv(fresh.length);
    for (const r of fresh) w.u8(r);
    w.uv(passed.length);
    for (const r of passed) w.u8(r);
  }
  if (kind === 3) {
    const bitmap = new Uint8Array(29);
    for (const i of input.visited ?? []) bitmap[i >> 3] |= 1 << (i & 7);
    for (const b of bitmap) w.u8(b);
  }
  if (kind === 1 || kind === 2) {
    w.u8(input.quantExp ?? 4);
    const lines = input.lines ?? [];
    w.uv(lines.length);
    let prevLat = 0;
    let prevLng = 0;
    for (const line of lines) {
      w.u8(line.style ?? styleOf(line.level ?? 1, line.fresh));
      w.uv(line.q.length);
      for (const [lat, lng] of line.q) {
        w.sv(lat - prevLat);
        w.sv(lng - prevLng);
        prevLat = lat;
        prevLng = lng;
      }
    }
  }
  for (const b of input.extra ?? []) w.u8(b);
  const bytes = w.bytes();
  return { bytes, payload: toBase32(bytes) };
}

/** Degrees → transmitted integer at 10^-quantExp degrees */
export function quantize(deg, quantExp) {
  return Math.round(deg * 10 ** quantExp);
}

// ── QR budget (spec: version ≤ 20, EC level M; URL prefix in byte mode + payload in alphanumeric mode) ──────────
export const SHARE_URL_PREFIX = 'https://geunbongit.github.io/daedongchajido/s/#';
/** Data bits of QR version 20-M (669 data codewords) */
const QR20M_DATA_BITS = 669 * 8;
/** Largest payload (bytes) that fits a version-20-M QR together with the URL prefix */
export function maxPayloadBytes(prefix = SHARE_URL_PREFIX) {
  const prefixBits = 4 + 16 + new TextEncoder().encode(prefix).length * 8; // byte mode, 16-bit count (v10+)
  const room = QR20M_DATA_BITS - prefixBits - 4 - 11; // alphanumeric header, 11-bit count (v10–26)
  let chars = Math.floor(room / 11) * 2;
  if (room - (chars / 2) * 11 >= 6) chars += 1;
  return Math.floor((chars * 5) / 8);
}
