// Landing page: draws the sample drive card with the same renderer as the share viewer, keeps the sample links in sync
// with assets/samples.js, and falls back gracefully while the demo video / screenshots are not uploaded yet.
import { decodeShare } from './share-decode.js';
import { canvasToBlob, cardFontText, loadCardFonts, renderCard } from './card.js';
import { cardAltText } from './format.js';
import { SAMPLES } from './samples.js';

const T = globalThis.ODOMAP_TOKENS;

for (const a of document.querySelectorAll('a[data-sample]')) {
  const payload = SAMPLES[a.dataset.sample];
  if (payload) a.href = `s/#${payload}`;
}

let heroUrl = null;
async function drawHero() {
  const frame = document.getElementById('hero-card-frame');
  if (!frame || !SAMPLES.drive) return;
  const share = decodeShare(SAMPLES.drive);
  await loadCardFonts(cardFontText(share), 4000);
  const look = T.autoLook();
  const { canvas, scene } = renderCard(share, { ratio: 'square', look });
  const blob = await canvasToBlob(canvas);
  const img = new Image();
  img.alt = cardAltText(share, scene.title);
  img.decoding = 'async';
  if (heroUrl) URL.revokeObjectURL(heroUrl);
  heroUrl = URL.createObjectURL(blob);
  img.src = heroUrl;
  await img.decode().catch(() => {});
  frame.replaceChildren(img);
}

// demo video: until assets/demo.mp4 exists the poster stays, with a short note
const demo = document.getElementById('demo');
if (demo) {
  const video = demo.querySelector('video');
  const source = video.querySelector('source');
  const missing = () => {
    video.removeAttribute('controls');
    demo.querySelector('.video-missing').hidden = false;
  };
  source.addEventListener('error', missing);
  video.addEventListener('error', missing);
  if (video.networkState === HTMLMediaElement.NETWORK_NO_SOURCE) missing();
}

// screenshots: assets/screens/0N.png, placeholder until they are added
for (const img of document.querySelectorAll('img[data-fallback]')) {
  const swap = () => {
    if (img.src.endsWith(img.dataset.fallback)) return;
    img.src = img.dataset.fallback;
  };
  img.addEventListener('error', swap, { once: true });
  if (img.complete && img.naturalWidth === 0 && img.currentSrc) swap();
}

// follow the phone's light / dark mode (발자취 auto look)
try {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    T.applyLook(T.autoLook());
    drawHero().catch(() => {});
  });
} catch {
  /* old Safari */
}

drawHero().catch(error => console.warn('[odomap] sample card', error));
