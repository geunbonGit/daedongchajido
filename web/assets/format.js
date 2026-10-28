// Card / page text — same rules as 발자취 (design-footprint-ref §3 표기, exploreCardModel.cardNumbers), Odomap wording (design.md §6).
// Pure module (no DOM), shared by the viewer, the landing page and the tests.
import { KIND } from './share-decode.js';

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** 발자취 formatDistanceKm: under 100 km two decimals (floored), from 100 km one decimal (floored). "0.00" for 0. */
export function formatDistanceKm(meters) {
  if (!(meters > 0)) return '0.00';
  const hundredths = Math.floor(meters / 10 + 1e-7);
  if (hundredths < 10000) return `${Math.floor(hundredths / 100)}.${String(hundredths % 100).padStart(2, '0')}`;
  const tenths = Math.floor(meters / 100 + 1e-7);
  return `${Math.floor(tenths / 10)}.${tenths % 10}`;
}

/** "1분 미만" · "38분" · "1시간 39분" · "2시간" */
export function minutesText(seconds) {
  const minutes = Math.round(Math.max(0, seconds || 0) / 60);
  if (minutes < 1) return '1분 미만';
  if (minutes < 60) return `${minutes}분`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours}시간` : `${hours}시간 ${rest}분`;
}

export function withCommas(value) {
  return String(Math.round(value)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Card date line "2026.10.31 토" */
export function cardDateLabel(date) {
  if (!date) return '';
  return `${date.year}.${date.month}.${date.day} ${WEEKDAYS[date.weekday]}`;
}

/** Long date "2026년 10월 31일 (토)" (page text, alt text) */
export function longDateLabel(date) {
  if (!date) return '';
  return `${date.year}년 ${date.month}월 ${date.day}일 (${WEEKDAYS[date.weekday]})`;
}

/** Minutes after midnight → "오전 9:40" / "오후 12:05" / "오전 12:30" */
export function clockLabel(startMin) {
  const m = Math.max(0, Math.round(startMin || 0)) % 1440;
  const h = Math.floor(m / 60);
  const mm = String(m % 60).padStart(2, '0');
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h < 12 ? '오전' : '오후'} ${h12}:${mm}`;
}

/** 한마디: whitespace runs → one space, trimmed, at most `max` characters (code points). '' when empty. */
export function cardTitleText(raw, max = 20) {
  const flat = String(raw ?? '').replace(/\s+/g, ' ').trim();
  const chars = Array.from(flat);
  return chars.length > max ? chars.slice(0, max).join('').trim() : flat;
}

/**
 * Card numbers for a decoded share: { hero, unit, label, stats }.
 *  kind 1: "+12.43" km 처음 달린 길 · "79.39km · 1시간 39분 · 첫 방문 8곳"
 *          (under 10 m new: hero = distance, label "달린 길", the distance is not repeated in the stats line)
 *  kind 2: "415.0" km 내 지도 · "602.3km · 드라이브 14번"
 *  kind 3: "23" /230곳 가 본 시·군·구 · "602.3km · 드라이브 14번"
 */
export function cardNumbers(share) {
  if (share.kind === KIND.REGIONS) {
    const parts = [];
    if (share.distanceM > 0) parts.push(`${formatDistanceKm(share.distanceM)}km`);
    if (share.driveCount > 0) parts.push(`드라이브 ${withCommas(share.driveCount)}번`);
    // the spec's card number is visitedTotal ("230곳 중 N곳"); the bitmap count is the fallback
    return { hero: String(share.visitedTotal || share.visited.length), unit: `/${share.regionCount}곳`, label: '가 본 시·군·구', stats: parts.join(' · ') };
  }
  if (share.kind === KIND.MY_MAP) {
    const parts = [];
    if (share.distanceM > 0) parts.push(`${formatDistanceKm(share.distanceM)}km`);
    if (share.driveCount > 0) parts.push(`드라이브 ${withCommas(share.driveCount)}번`);
    return { hero: formatDistanceKm(share.pioneeredM), unit: 'km', label: '내 지도', stats: parts.join(' · ') };
  }
  const pioneered = share.pioneeredM >= 10;
  const parts = [];
  if (pioneered) parts.push(`${formatDistanceKm(share.distanceM)}km`);
  parts.push(minutesText(share.durationS));
  if (share.newRegions.length > 0) parts.push(`첫 방문 ${share.newRegions.length}곳`);
  const stats = parts.join(' · ');
  if (!pioneered) return { hero: formatDistanceKm(share.distanceM), unit: 'km', label: '달린 길', stats };
  return { hero: `+${formatDistanceKm(share.pioneeredM)}`, unit: 'km', label: '처음 달린 길', stats };
}

/** One-sentence description of the card (img alt, share text). */
export function cardAltText(share, title) {
  const n = cardNumbers(share);
  const head = title ? `${title} — ` : '';
  if (share.kind === KIND.REGIONS) return `${head}${longDateLabel(share.date)} 기준, 전국 시·군·구 ${share.regionCount}곳 중 ${n.hero}곳을 가 본 Odomap 지도`;
  if (share.kind === KIND.MY_MAP) return `${head}${longDateLabel(share.date)} 기준, 내 지도 ${n.hero}km — 지금까지 달린 길을 그린 Odomap 지도`;
  const first = share.pioneeredM >= 10 ? `, 그중 처음 달린 길 ${formatDistanceKm(share.pioneeredM)}km` : '';
  return `${head}${longDateLabel(share.date)} ${clockLabel(share.startMin)}에 출발해 ${minutesText(share.durationS)} 동안 ${formatDistanceKm(share.distanceM)}km를 달린 드라이브${first}`;
}

/** File name stem: odomap-20261031-drive */
export function fileStem(share) {
  const d = share.date;
  const kind = share.kind === KIND.REGIONS ? 'regions' : share.kind === KIND.MY_MAP ? 'mymap' : 'drive';
  return `odomap-${d.year}${String(d.month).padStart(2, '0')}${String(d.day).padStart(2, '0')}-${kind}`;
}
