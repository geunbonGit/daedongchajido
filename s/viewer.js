// Share viewer: location.hash → decode (docs/share-format.md) → draw the card on a canvas → <img> (long-press saves) →
// save / Instagram story / share sheet. Nothing leaves the phone: the payload lives in the URL fragment, which browsers
// never send to the server, and the page makes no request other than its own static files.
import { decodeShare, KIND } from '../assets/share-decode.js';
import { canvasToBlob, cardFontText, cardPixelSize, cardScene, drawCard, loadCardFonts } from '../assets/card.js';
import { cardAltText, cardTitleText, fileStem } from '../assets/format.js';

const T = globalThis.ODOMAP_TOKENS;
const $ = id => document.getElementById(id);
const PREFS_KEY = 'odomap.viewer.v1';
const PRIVACY = '카드 데이터는 QR 주소의 # 뒤에만 있어요. 서버로 전송되지 않고 이 휴대폰에서 그려져요.';

const ua = navigator.userAgent;
const IS_IOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const IN_APP = /KAKAOTALK|Instagram|NAVER\(|FBAN|FBAV|Line\/|everytimeApp|DaumApps/i.test(ua);

const state = {
  share: null,
  outline: null,
  ratio: 'square',
  look: T.autoLook(), // 발자취 auto: night on dark-mode phones until the viewer picks a look
  title: '',
  mode: 'photo',
  renders: new Map(),
  busy: null,
  video: null, // { key, status: 'recording'|'ready'|'failed', blob, url, file, abort }
  recorder: null,
  statusTimer: 0,
};

// ── prefs (per-viewer convenience; the page works without storage) ─────────────────────────────
function loadPrefs() {
  try {
    const p = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
    if (p.ratio === 'square' || p.ratio === 'story') state.ratio = p.ratio;
    if (T.looks[p.look]) state.look = p.look;
  } catch {
    /* private mode etc. */
  }
}
function savePrefs() {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify({ ratio: state.ratio, look: state.look }));
  } catch {
    /* ignore */
  }
}

// ── status line (privacy note when idle) ───────────────────────────────────────────────
function setStatus(text, { error = false, sticky = false } = {}) {
  const el = $('status');
  clearTimeout(state.statusTimer);
  el.classList.toggle('is-error', error);
  el.classList.toggle('is-note', Boolean(text));
  el.querySelector('.status-text').textContent = text || PRIVACY;
  if (text && !sticky) state.statusTimer = setTimeout(() => setStatus(''), 3600);
}

// ── renders ──────────────────────────────────────────────────────────────
const keyOf = (ratio, look = state.look, title = state.title) => `${ratio}|${look}|${cardTitleText(title)}`;

function ensureRender(ratio) {
  const key = keyOf(ratio);
  let r = state.renders.get(key);
  if (r) return r;
  const scene = cardScene(state.share, { ratio, look: state.look, title: state.title, outline: state.outline });
  const { width, height } = cardPixelSize(ratio);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const t0 = performance.now();
  drawCard(canvas.getContext('2d'), scene, width / scene.layout.width);
  (window.__odomapDrawMs ||= []).push(Math.round(performance.now() - t0));
  r = { key, ratio, scene, canvas, alt: cardAltText(state.share, scene.title), blob: null, url: null, file: null };
  r.promise = canvasToBlob(canvas, 'image/png').then(blob => {
    r.blob = blob;
    r.url = URL.createObjectURL(blob);
    r.file = new File([blob], `${fileStem(state.share)}-${ratio === 'story' ? 'story' : 'square'}.png`, { type: 'image/png' });
    return r;
  });
  state.renders.set(key, r);
  // keep the current pair plus a few recent ones
  if (state.renders.size > 6) {
    for (const [k, old] of state.renders) {
      if (state.renders.size <= 6) break;
      if (k === keyOf('square') || k === keyOf('story')) continue;
      if (old.url) URL.revokeObjectURL(old.url);
      state.renders.delete(k);
    }
  }
  return r;
}

function slotOf(ratio) {
  return document.querySelector(`.slot[data-ratio="${ratio}"]`);
}

function paint(ratio) {
  const r = ensureRender(ratio);
  const slot = slotOf(ratio);
  const put = () => {
    if (r.key !== keyOf(ratio)) return;
    let img = slot.querySelector('img');
    if (!img) {
      img = new Image();
      img.decoding = 'async';
      img.draggable = true;
      slot.replaceChildren(img);
    }
    img.alt = r.alt;
    img.src = r.url;
    if (slot.firstChild !== img) slot.replaceChildren(img);
  };
  if (r.url) put();
  else {
    // show the canvas at once; swap to the <img> (long-press save) as soon as the PNG is encoded
    r.canvas.setAttribute('role', 'img');
    r.canvas.setAttribute('aria-label', r.alt);
    slot.replaceChildren(r.canvas);
    r.promise.then(put, () => setStatus('카드를 만들지 못했어요.', { error: true }));
  }
  markFirstCard();
  return r;
}

/** Time to card (ms since navigation start), shown when the page is opened with ?perf — see README "Speed". */
function markFirstCard() {
  if (window.__odomapFirstCardMs) return;
  window.__odomapFirstCardMs = Math.round(performance.now());
  try {
    performance.mark('odomap-card');
  } catch {
    /* old browsers */
  }
  if (new URLSearchParams(location.search).has('perf')) {
    requestAnimationFrame(() => {
      const ms = Math.round(performance.now());
      const res = performance.getEntriesByType('resource');
      const kb = Math.round(res.reduce((s, e) => s + (e.transferSize || e.encodedBodySize || 0), 0) / 1024);
      console.info(`[odomap] card drawn ${window.__odomapFirstCardMs} ms, on screen ${ms} ms, ${res.length} files ${kb} KB`);
    });
  }
}

function refresh() {
  const current = paint(state.ratio);
  document.body.classList.remove('is-loading');
  // prepare the other ratio in the background (the story button shares the story PNG right away)
  const other = state.ratio === 'square' ? 'story' : 'square';
  const idle = window.requestIdleCallback || (fn => setTimeout(fn, 120));
  current.promise.then(() => idle(() => paint(other)));
  if (state.mode === 'video') startVideo();
}

// ── stage layout (발자취 previewSize: square ≤ 420 wide, both centered) ─────────────────────────
function layoutStage() {
  const stage = $('stage');
  const w = stage.clientWidth;
  const h = stage.clientHeight;
  const maxW = Math.min(T.stage.maxSquareW, w - T.stage.marginX * 2);
  const maxH = h - T.stage.marginY * 2;
  const aspect = T.card.heights.story / T.card.designW;
  const square = Math.max(120, Math.floor(Math.min(maxW, maxH)));
  const storyW = Math.max(68, Math.floor(Math.min(maxW, maxH / aspect)));
  const storyH = Math.floor(storyW * aspect);
  const root = document.documentElement.style;
  root.setProperty('--square-w', `${square}px`);
  root.setProperty('--story-w', `${storyW}px`);
  root.setProperty('--story-h', `${storyH}px`);
  const frame = $('frame').style;
  frame.setProperty('--frame-w', `${state.ratio === 'story' ? storyW : square}px`);
  frame.setProperty('--frame-h', `${state.ratio === 'story' ? storyH : square}px`);
}

function showRatio() {
  for (const slot of document.querySelectorAll('.slot')) {
    const r = slot.dataset.ratio;
    slot.classList.toggle('on', state.mode === 'video' ? r === 'motion' : r === state.ratio);
  }
  slotOf('motion').classList.toggle('square', state.ratio === 'square');
  layoutStage();
}

// ── segmented controls & chips ──────────────────────────────────────────────────────
function bindSeg(el, value, onChange) {
  const buttons = [...el.querySelectorAll('button')];
  el.style.setProperty('--n', buttons.length);
  const select = v => {
    buttons.forEach((b, i) => {
      const on = b.dataset.value === v;
      b.setAttribute('aria-selected', String(on));
      if (on) el.style.setProperty('--i', i);
    });
  };
  select(value);
  el.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b || b.getAttribute('aria-selected') === 'true') return;
    select(b.dataset.value);
    onChange(b.dataset.value);
  });
  return select;
}

function buildChips() {
  const box = $('looks');
  box.replaceChildren(
    ...T.lookOrder.map(id => {
      const look = T.looks[id];
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip';
      b.setAttribute('role', 'radio');
      b.dataset.look = id;
      const sw = document.createElement('span');
      sw.className = 'swatch';
      sw.style.background = `radial-gradient(circle at 50% 50%, ${look.ink} 0 26%, ${look.paper} 27%)`;
      const name = document.createElement('span');
      name.textContent = look.name;
      b.append(sw, name);
      return b;
    }),
  );
  const mark = () => box.querySelectorAll('.chip').forEach(c => c.setAttribute('aria-checked', String(c.dataset.look === state.look)));
  mark();
  box.addEventListener('click', e => {
    const b = e.target.closest('.chip');
    if (!b || b.dataset.look === state.look) return;
    state.look = b.dataset.look;
    mark();
    applyLook();
    savePrefs();
    refresh();
  });
}

function applyLook() {
  T.applyLook(state.look);
  $('frame').style.setProperty('--card-paper', T.looks[state.look].paper);
}

// ── sending ───────────────────────────────────────────────────────────────
function shareSupported(file) {
  try {
    return Boolean(file && navigator.canShare && navigator.share && navigator.canShare({ files: [file] }));
  } catch {
    return false;
  }
}

async function shareFiles(file, what) {
  try {
    await navigator.share({ files: [file] });
    setStatus(`${what}을 보냈어요.`);
    return true;
  } catch (e) {
    if (e && e.name === 'AbortError') return false; // the person closed the sheet
    if (e && e.name === 'NotAllowedError') setStatus('한 번 더 눌러 주세요.', { error: true });
    else setStatus('공유하지 못했어요. 이미지 저장으로 보내 주세요.', { error: true });
    return false;
  }
}

function download(url, name) {
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.rel = 'noopener';
  document.body.append(a);
  a.click();
  a.remove();
}

function openSaveSheet(r) {
  const sheet = $('save-sheet');
  const img = $('save-sheet-img');
  img.src = r.url;
  img.alt = r.alt;
  sheet.hidden = false;
  $('save-sheet-close').focus();
}

async function onAction(kind) {
  if (state.busy) return;
  if (state.mode === 'video') return onVideoAction(kind);
  const ratio = kind === 'story' ? 'story' : state.ratio;
  const r = ensureRender(ratio);
  if (!r.file) {
    // not encoded yet (rare: a tap within ~0.2 s of a change) — wait, then ask for one more tap if sharing
    state.busy = kind;
    try {
      await r.promise;
    } finally {
      state.busy = null;
    }
    if (kind !== 'save') {
      setStatus('준비됐어요. 한 번 더 눌러 주세요.');
      return;
    }
  }
  if (kind === 'save') {
    if (IS_IOS || IN_APP) openSaveSheet(r);
    else {
      download(r.url, r.file.name);
      setStatus('이미지를 저장했어요.');
    }
    return;
  }
  if (kind === 'story') {
    if (shareSupported(r.file)) {
      await shareFiles(r.file, '스토리 이미지');
      return;
    }
    // no Web Share (desktop, some in-app browsers): show the story size and save it instead
    if (state.ratio !== 'story') setRatio('story');
    if (IS_IOS || IN_APP) openSaveSheet(r);
    else download(r.url, r.file.name);
    setStatus('스토리 크기 이미지를 저장했어요. 인스타그램에서 스토리로 올려 주세요.', { sticky: false });
    return;
  }
  // share
  if (shareSupported(r.file)) {
    await shareFiles(r.file, '카드');
    return;
  }
  await shareLink();
}

async function shareLink() {
  const url = location.href;
  try {
    if (navigator.share) {
      await navigator.share({ title: '대동차지도', url });
      return;
    }
  } catch (e) {
    if (e && e.name === 'AbortError') return;
  }
  try {
    await navigator.clipboard.writeText(url);
    setStatus('카드 링크를 복사했어요. 링크를 받은 사람도 같은 카드를 볼 수 있어요.');
  } catch {
    setStatus('이 브라우저에서는 공유할 수 없어요. 이미지 저장을 눌러 주세요.', { error: true });
  }
}

// ── video (lazy module; feature-detected) ──────────────────────────────────────────────
let videoModule = null;
async function videoApi() {
  if (!videoModule) videoModule = await import('./record.js');
  return videoModule;
}

async function startVideo() {
  const api = await videoApi();
  const key = keyOf(state.ratio);
  if (state.video && state.video.key === key && state.video.status !== 'failed') {
    showVideoState();
    return;
  }
  stopVideo();
  const scene = cardScene(state.share, { ratio: state.ratio, look: state.look, title: state.title, outline: state.outline });
  const slot = slotOf('motion');
  const { width, height } = cardPixelSize(state.ratio);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.setAttribute('role', 'img');
  canvas.setAttribute('aria-label', '길이 그려지는 영상을 만드는 중');
  slot.replaceChildren(canvas);
  const video = { key, ratio: state.ratio, status: 'recording', blob: null, url: null, file: null };
  state.video = video;
  updateActions();
  setStatus('영상 만드는 중 0%', { sticky: true });
  const job = api.recordCard({
    canvas,
    draw: (ctx, t) => drawCard(ctx, scene, width / scene.layout.width, t, video),
    onProgress: p => {
      if (state.video === video) setStatus(`영상 만드는 중 ${Math.round(p * 100)}%`, { sticky: true });
    },
  });
  video.abort = job.abort;
  try {
    const result = await job.done;
    if (state.video !== video) return;
    video.blob = result.blob;
    video.url = URL.createObjectURL(result.blob);
    video.file = new File([result.blob], `${fileStem(state.share)}-${state.ratio === 'story' ? 'story' : 'square'}.${result.ext}`, { type: result.type });
    video.status = 'ready';
    const el = document.createElement('video');
    el.muted = true;
    el.loop = true;
    el.playsInline = true;
    el.autoplay = true;
    el.setAttribute('playsinline', '');
    el.src = video.url;
    el.setAttribute('aria-label', '길이 그려지는 카드 영상');
    slot.replaceChildren(el);
    el.play().catch(() => {});
    setStatus(shareSupported(video.file) ? '영상이 준비됐어요.' : '영상이 준비됐어요. 저장해서 올려 주세요.');
  } catch (e) {
    if (state.video !== video) return;
    video.status = 'failed';
    setStatus(e && e.message === 'hidden' ? '화면을 떠나 영상 만들기를 멈췄어요. 다시 재생을 눌러 주세요.' : '이 브라우저에서는 영상을 만들지 못했어요.', { error: true, sticky: true });
  }
  updateActions();
}

function stopVideo() {
  const v = state.video;
  if (!v) return;
  if (v.status === 'recording' && v.abort) v.abort();
  if (v.url) URL.revokeObjectURL(v.url);
  state.video = null;
}

function showVideoState() {
  const v = state.video;
  if (!v) return;
  if (v.status === 'ready') setStatus('영상이 준비됐어요.');
  updateActions();
}

async function onVideoAction(kind) {
  const v = state.video;
  if (kind === 'story' && state.ratio !== 'story') {
    setRatio('story');
    setStatus('스토리 크기 영상을 만들고 있어요. 다 되면 한 번 더 눌러 주세요.', { sticky: true });
    return;
  }
  if (!v || v.status !== 'ready') return;
  if (kind === 'save') {
    if (IS_IOS && shareSupported(v.file)) {
      // iOS saves videos to Photos from the share sheet ('동영상 저장')
      await shareFiles(v.file, '영상');
      return;
    }
    download(v.url, v.file.name);
    setStatus('영상을 저장했어요.');
    return;
  }
  if (shareSupported(v.file)) {
    await shareFiles(v.file, '영상');
    return;
  }
  download(v.url, v.file.name);
  setStatus('이 브라우저는 영상 공유를 못 해서 저장했어요.');
}

function updateActions() {
  const video = state.mode === 'video';
  const ready = !video || (state.video && state.video.status === 'ready');
  for (const id of ['act-save', 'act-share']) $(id).disabled = !ready;
  $('act-story').disabled = video && !ready && state.ratio === 'story';
  $('act-save').querySelector('.label').textContent = video ? '영상 저장' : '이미지 저장';
  $('replay').hidden = !video;
}

// ── wiring ───────────────────────────────────────────────────────────────
let selectRatio = () => {};
function setRatio(ratio) {
  if (state.ratio === ratio) return;
  state.ratio = ratio;
  selectRatio(ratio);
  savePrefs();
  showRatio();
  refresh();
  updateActions();
}

function setMode(mode) {
  state.mode = mode;
  if (mode === 'photo') {
    stopVideo();
    slotOf('motion').replaceChildren();
    setStatus('');
  }
  showRatio();
  updateActions();
  if (mode === 'video') startVideo();
}

function showEmpty(error) {
  document.body.classList.add('show-empty');
  document.body.classList.remove('is-loading');
  $('empty').hidden = false;
  // samples are only needed here, so they are not part of the card's critical path
  import('../assets/samples.js').then(
    ({ SAMPLES }) => {
      $('empty-sample').href = `#${SAMPLES.drive}`;
    },
    () => {},
  );
  if (!error || error.code === 'empty') return;
  const steps = $('empty-steps');
  if (error.unsupported) {
    $('empty-title').textContent = '이 페이지가 읽을 수 없는 카드 형식이에요';
    $('empty-body').textContent = '카드 데이터의 형식 버전, 카드 종류, 지역 목록 버전 중 하나가 이 페이지보다 새로워요. 페이지를 새로고침해 최신 페이지를 받은 뒤 QR을 다시 찍어 주세요.';
  } else {
    $('empty-title').textContent = '카드 데이터를 읽지 못했어요';
    $('empty-body').textContent = '주소의 # 뒤 데이터가 잘렸거나 형식에 맞지 않아요. 차 화면의 QR을 휴대폰 카메라로 다시 찍어 주세요. 주소를 복사해 붙여 넣었다면 # 뒤 끝까지 복사됐는지 확인해 주세요.';
  }
  steps.hidden = true;
}

async function main() {
  loadPrefs();
  window.addEventListener('hashchange', () => location.reload());
  let share;
  try {
    share = decodeShare(location.hash);
  } catch (error) {
    applyLook();
    showEmpty(error);
    return;
  }
  state.share = share;
  state.title = cardTitleText(share.title);

  // start every download the card needs right away, then build the page while they arrive
  const fonts = loadCardFonts(cardFontText(share, { title: state.title }), 3000);
  const outline = share.kind === KIND.REGIONS ? fetch('../data/outline.json').then(r => (r.ok ? r.json() : null), () => null) : null;

  applyLook();
  document.title = `${state.title || (share.kind === KIND.REGIONS ? '가 본 곳' : share.kind === KIND.MY_MAP ? '내 지도' : '드라이브')} · 대동차지도 카드`;
  $('ends').hidden = !(share.kind === KIND.DRIVE && share.hideEnds);
  const titleInput = $('title');
  titleInput.value = state.title;
  $('title-clear').hidden = !state.title;
  buildChips();
  selectRatio = bindSeg($('ratio'), state.ratio, v => setRatio(v));
  const canVideo = typeof MediaRecorder !== 'undefined' && typeof HTMLCanvasElement.prototype.captureStream === 'function';
  if (canVideo) {
    $('mode').hidden = false;
    bindSeg($('mode'), 'photo', v => setMode(v));
  }
  showRatio();
  new ResizeObserver(() => layoutStage()).observe($('stage'));

  const [fontsReady, outlineData] = await Promise.all([fonts, outline]);
  state.outline = outlineData;
  refresh();
  if (!fontsReady) {
    // slow network: the card was drawn with the fallback font — redraw once the real one arrives
    fonts.done.then(ok => {
      if (!ok) return;
      for (const r of state.renders.values()) if (r.url) URL.revokeObjectURL(r.url);
      state.renders.clear();
      refresh();
    });
  }
  requestAnimationFrame(() => requestAnimationFrame(() => document.body.classList.add('is-ready')));

  let titleTimer = 0;
  titleInput.addEventListener('input', () => {
    $('title-clear').hidden = !titleInput.value;
    clearTimeout(titleTimer);
    titleTimer = setTimeout(async () => {
      const value = titleInput.value;
      await loadCardFonts({ [T.card.weight.title]: cardTitleText(value) + '…' }, 1500);
      if (titleInput.value !== value) return;
      state.title = value;
      refresh();
    }, 140);
  });
  titleInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') titleInput.blur();
  });
  $('title-clear').addEventListener('click', () => {
    titleInput.value = '';
    $('title-clear').hidden = true;
    state.title = '';
    refresh();
    titleInput.focus();
  });
  for (const id of ['act-save', 'act-story', 'act-share']) $(id).addEventListener('click', () => onAction($(id).dataset.kind));
  $('replay').addEventListener('click', () => {
    const el = slotOf('motion').querySelector('video');
    if (el) {
      el.currentTime = 0;
      el.play().catch(() => {});
    } else if (state.video && state.video.status === 'failed') {
      state.video = null;
      startVideo();
    }
  });
  const closeSheet = () => {
    $('save-sheet').hidden = true;
  };
  $('save-sheet-close').addEventListener('click', closeSheet);
  $('save-sheet').addEventListener('click', e => {
    if (e.target === $('save-sheet')) closeSheet();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeSheet();
  });
  updateActions();
  setStatus('');
}

main().catch(error => {
  console.error(error);
  showEmpty({ code: 'invalid' });
});
