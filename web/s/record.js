// Card video (ref §7): the same drawing as the photo card, animated in real time on a canvas and recorded with
// MediaRecorder — 300 ms blank → 2.6 s route drawing → head dot fades 360 ms → 1.5 s hold (≈ 4.8 s).
// Prefers MP4 (H.264: Instagram, KakaoTalk, iOS Photos) and falls back to WebM where only that exists.
import { motionLength } from '../assets/card.js';

const CANDIDATES = [
  ['video/mp4;codecs=avc1.640028', 'video/mp4', 'mp4'],
  ['video/mp4;codecs=avc1.42E01F', 'video/mp4', 'mp4'],
  ['video/mp4;codecs=avc1', 'video/mp4', 'mp4'],
  ['video/mp4', 'video/mp4', 'mp4'],
  ['video/webm;codecs=vp9', 'video/webm', 'webm'],
  ['video/webm;codecs=vp8', 'video/webm', 'webm'],
  ['video/webm', 'video/webm', 'webm'],
];

export function pickFormat() {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return null;
  for (const [mime, type, ext] of CANDIDATES) if (MediaRecorder.isTypeSupported(mime)) return { mime, type, ext };
  return null;
}

/**
 * Plays draw(ctx, tMs) from 0 to the motion length on `canvas` and records it.
 * Returns { done: Promise<{ blob, type, ext }>, abort() }. Leaving the page (hidden tab) aborts with Error('hidden'),
 * because browsers stop animation frames there and the video would freeze.
 */
export function recordCard({ canvas, draw, onProgress, fps = 30, bitsPerSecond = 8_000_000 }) {
  const format = pickFormat();
  if (!format || typeof canvas.captureStream !== 'function') {
    return { done: Promise.reject(new Error('unsupported')), abort() {} };
  }
  const total = motionLength();
  const ctx = canvas.getContext('2d');
  draw(ctx, 0);
  const stream = canvas.captureStream(fps);
  let recorder;
  try {
    recorder = new MediaRecorder(stream, { mimeType: format.mime, videoBitsPerSecond: bitsPerSecond });
  } catch (error) {
    stream.getTracks().forEach(track => track.stop());
    return { done: Promise.reject(error), abort() {} };
  }
  const chunks = [];
  let stopped = false;
  let reason = null;
  let raf = 0;
  let t0 = null;

  const onVisibility = () => {
    if (document.hidden) abort('hidden');
  };
  const cleanup = () => {
    cancelAnimationFrame(raf);
    document.removeEventListener('visibilitychange', onVisibility);
    stream.getTracks().forEach(track => track.stop());
  };
  const done = new Promise((resolve, reject) => {
    recorder.ondataavailable = event => {
      if (event.data && event.data.size > 0) chunks.push(event.data);
    };
    recorder.onerror = event => {
      cleanup();
      reject(event.error || new Error('recorder'));
    };
    recorder.onstop = () => {
      cleanup();
      if (reason) reject(new Error(reason));
      else if (!chunks.length) reject(new Error('empty'));
      else resolve({ blob: new Blob(chunks, { type: format.type }), type: format.type, ext: format.ext });
    };
  });

  function stop() {
    if (stopped) return;
    stopped = true;
    if (recorder.state !== 'inactive') recorder.stop();
    else cleanup();
  }
  function abort(why = 'aborted') {
    reason = why;
    stop();
  }
  function frame(now) {
    if (stopped) return;
    if (t0 === null) t0 = now;
    const t = now - t0;
    // redraw every frame — also during the hold, so the recorder keeps receiving frames
    draw(ctx, Math.min(t, total));
    if (onProgress) onProgress(Math.min(1, t / total));
    if (t >= total + 120) {
      stop();
      return;
    }
    raf = requestAnimationFrame(frame);
  }

  document.addEventListener('visibilitychange', onVisibility);
  recorder.start(500);
  raf = requestAnimationFrame(frame);
  return { done, abort };
}
