/*
 * Odomap design tokens — the ONE place for colors, type, sizes and timings used by the web pages and the share card.
 * Values are the 발자취 values from docs/design-footprint-ref.md (§1 colors, §3 type, §7 share card) and docs/design.md §6.
 * To retune the look, change values here only: the card renderer reads `ODOMAP_TOKENS`, the pages read the CSS variables
 * that `applyLook()` derives from the same values.
 *
 * Classic script (not a module) on purpose: it is loaded synchronously in <head> so CSS variables exist before first paint.
 */
(function (root) {
  'use strict';

  // ── Looks (ref §1.1 EXPLORE_PAPERS; "white" = 발자취 card style '흰색': white, no grid, paper look's ink) ──────────
  var LOOKS = {
    paper: { id: 'paper', name: '종이', scheme: 'light', paper: '#F7F7F5', ink: '#262626', fresh: '#1A73E8', text: '#1C1C1E', textSoft: '#8A8A8E', button: '#FFFFFF', grid: true },
    white: { id: 'white', name: '흰색', scheme: 'light', paper: '#FFFFFF', ink: '#262626', fresh: '#1A73E8', text: '#1C1C1E', textSoft: '#8A8A8E', button: '#FFFFFF', grid: false },
    hanji: { id: 'hanji', name: '한지', scheme: 'light', paper: '#F4F0E6', ink: '#2A2622', fresh: '#2D4B9A', text: '#24201C', textSoft: '#8C847A', button: '#FFFDF8', grid: true },
    night: { id: 'night', name: '밤', scheme: 'dark', paper: '#141416', ink: '#ECEAE5', fresh: '#8AB4F8', text: '#F2F2F0', textSoft: '#8E8E93', button: '#26262A', grid: true },
  };

  var T = {
    looks: LOOKS,
    lookOrder: ['paper', 'white', 'hanji', 'night'],
    defaultLook: 'paper',

    font: {
      // 'Odomap Sans' = Pretendard (SIL OFL 1.1, fonts/LICENSE.txt) split into unicode-range chunks by
      // tools/build-fonts.py (fonts/fonts.css). Weights 500 · 700 · 800; the card uses 500 + 700 only.
      family: 'Odomap Sans',
      fallback: "-apple-system, BlinkMacSystemFont, 'Apple SD Gothic Neo', 'Noto Sans KR', 'Malgun Gothic', sans-serif",
    },

    // ref §1.4 paper grid (cards: 44-unit cells, 0.5 line, centered so both margins match)
    grid: { cell: 44, line: 0.5, light: 'rgba(0, 0, 0, 0.063)', dark: 'rgba(255, 255, 255, 0.051)' },

    // ref §7 share card (drawExploreCard). Design units: card width 360, exported ×3 (1080 px).
    card: {
      designW: 360,
      exportW: 1080,
      heights: { square: 360, story: 640 },
      // story leaves room for Instagram's progress bar (top) and reply bar (bottom)
      frames: {
        square: { padX: 24, top: 24, bottom: 24, gap: 18 },
        story: { padX: 28, top: 68, bottom: 76, gap: 28 },
      },
      type: { date: 13, title: 17, titleMin: 12, hero: 40, unit: 18, unitGap: 6, label: 13, stats: 13, statsMin: 11, brand: 13 },
      weight: { date: 500, title: 700, hero: 700, unit: 700, label: 500, stats: 500, brand: 700 },
      // ink-height estimates (fraction of font size) used by the layout: digits 0.72, Hangul 0.8
      inkH: { digit: 0.72, hangul: 0.8 },
      titleGap: 9, // date bottom → title cap
      labelGap: 22, // label baseline → stats baseline
      heroGap: 9, // label top → hero baseline
      titleMaxChars: 20,
      line: {
        walked: 2, // design units (6 px in the file)
        alpha: 0.92, // whole line layer at once, so overlaps never darken
        minSpanM: 400, // fit: never zoom closer than this (car map minSpan, ref §2.6)
        // payload lines are Douglas–Peucker-simplified: resample every smoothStep units, then [1,2,1]/4 × smoothPasses
        smoothStep: 1.5,
        smoothPasses: 3,
      },
      // ref §2.3 EXPLORE_LEVEL_WIDTH_STOPS [MapLibre zoom, base, step]: width(L) = walked × (base + step·(L−1)) / base
      widthStops: [
        [8, 1.9, 0.35],
        [12, 1.7, 0.6],
        [16, 1.4, 1.1],
        [19, 2.2, 1.8],
      ],
      // 발자취 lockup: mark diameter = brand size, gap 0.36×, word at 0.98×
      brand: { word: '대동차지도', gapK: 0.36, wordK: 0.98, room: 14, markColor: 'ink', wordColor: 'textSoft' },
      // kind 3 (가 본 곳): every outline hairline, visited shapes filled (like 발자취 동네 badges). Visited shapes are
      // separated by paper-colored hairlines so each region stays countable inside a filled cluster.
      regions: { outline: 0.5, outlineAlpha: 0.18, visitedFillAlpha: 0.85, visitedEdge: 0.5, visitedEdgeColor: 'paper', visitedEdgeAlpha: 0.9 },
      // tiny source credit drawn inside kind-3 images (ref §3 출처 9/500)
      credit: { size: 9, weight: 500, text: '지역 경계 © 통계청 SGIS · vuski/admdongkor CC BY 4.0' },
      // video (ref §7): 300 ms blank → 2600 ms drawing (smoothstep) → head fades 360 ms → hold 1.5 s
      motion: { leadInMs: 300, drawMs: 2600, settleMs: 360, holdMs: 1500, fps: 30, headR: 3, headHaloR: 7.5, headHaloAlpha: 0.2 },
    },

    // share preview screen (ref §7 미리보기 화면 / ExploreCardControls)
    stage: {
      maxSquareW: 420,
      marginX: 24,
      marginY: 12,
      shadow: '0 10px 40px rgba(0, 0, 0, 0.12)',
      ratioMs: 340,
      actionCircle: 52,
      actionCell: 94,
      actionIcon: 24,
    },

    instagram: ['#FEDA75', '#FA7E1E', '#D62976', '#962FBF', '#4F5BD5'],
  };

  // ── derived UI colors (ref §1.2 explorePaletteOf / exploreUiColorsOf) ──────────────────────────────────────
  function hexRgb(hex) {
    var v = hex.replace('#', '');
    return [parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)];
  }
  function rgba(hex, alpha) {
    var c = hexRgb(hex);
    return 'rgba(' + c[0] + ', ' + c[1] + ', ' + c[2] + ', ' + alpha + ')';
  }
  function mixHex(a, b, t) {
    var x = hexRgb(a);
    var y = hexRgb(b);
    var out = '#';
    for (var i = 0; i < 3; i += 1) {
      var v = Math.round(x[i] * (1 - t) + y[i] * t);
      out += (v < 16 ? '0' : '') + v.toString(16).toUpperCase();
    }
    return out;
  }

  /** Every color a page or card needs for one look. */
  function uiColors(lookId) {
    var L = LOOKS[lookId] || LOOKS[T.defaultLook];
    var dark = L.scheme === 'dark';
    return {
      look: L.id,
      dark: dark,
      paper: L.paper,
      ink: L.ink,
      fresh: L.fresh,
      text: L.text,
      textSoft: L.textSoft,
      grid: L.grid ? (dark ? T.grid.dark : T.grid.light) : null,
      app: mixHex(L.paper, L.ink, dark ? 0.03 : 0.035),
      surface: L.button,
      surfaceStrong: dark ? 'rgba(255, 255, 255, 0.10)' : rgba(L.ink, 0.07),
      textMuted: rgba(L.textSoft, dark ? 0.75 : 0.8),
      textInverse: dark ? L.paper : '#FFFFFF',
      divider: dark ? 'rgba(255, 255, 255, 0.08)' : rgba(L.ink, 0.08),
      danger: dark ? '#FF9A93' : '#B42318',
    };
  }

  /** Writes the look's colors as CSS variables on <html> (pages style themselves only with these). */
  function applyLook(lookId, el) {
    if (typeof document === 'undefined') return;
    var c = uiColors(lookId);
    var s = (el || document.documentElement).style;
    s.setProperty('--paper', c.paper);
    s.setProperty('--ink', c.ink);
    s.setProperty('--fresh', c.fresh);
    s.setProperty('--text', c.text);
    s.setProperty('--text-soft', c.textSoft);
    s.setProperty('--text-muted', c.textMuted);
    s.setProperty('--text-inverse', c.textInverse);
    s.setProperty('--app', c.app);
    s.setProperty('--surface', c.surface);
    s.setProperty('--surface-strong', c.surfaceStrong);
    s.setProperty('--divider', c.divider);
    s.setProperty('--danger', c.danger);
    s.setProperty('--grid', c.grid || 'transparent');
    s.setProperty('--grid-cell', T.grid.cell + 'px');
    s.setProperty('--stage-shadow', T.stage.shadow);
    s.setProperty('--action-circle', T.stage.actionCircle + 'px');
    s.setProperty('--action-cell', T.stage.actionCell + 'px');
    s.setProperty('--ratio-ms', T.stage.ratioMs + 'ms');
    s.setProperty('--insta', 'linear-gradient(45deg, ' + T.instagram.join(', ') + ')');
    s.setProperty('--font', "'" + T.font.family + "', " + T.font.fallback);
    if (!el) {
      document.documentElement.dataset.look = c.look;
      document.documentElement.style.colorScheme = c.dark ? 'dark' : 'light';
      var meta = document.querySelector('meta[name="theme-color"]');
      if (meta) meta.setAttribute('content', c.app);
    }
  }

  /**
   * 발자취 'auto' look: night when the phone is in dark mode, else paper. Pages opt in with <html data-auto-look>
   * (applied here, before first paint, so dark-mode phones never flash a light page).
   */
  function autoLook() {
    try {
      return root.matchMedia && root.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : T.defaultLook;
    } catch (e) {
      return T.defaultLook;
    }
  }

  T.rgba = rgba;
  T.mixHex = mixHex;
  T.uiColors = uiColors;
  T.applyLook = applyLook;
  T.autoLook = autoLook;
  root.ODOMAP_TOKENS = T;
  /** <html data-auto-look="storageKey">: a look saved by that page ({ look } JSON in localStorage) wins over auto */
  function firstLook() {
    var el = typeof document !== 'undefined' ? document.documentElement : null;
    if (!el || !el.hasAttribute('data-auto-look')) return T.defaultLook;
    try {
      var key = el.getAttribute('data-auto-look');
      var saved = key ? JSON.parse(root.localStorage.getItem(key) || '{}').look : null;
      if (saved && LOOKS[saved]) return saved;
    } catch (e) {
      /* storage blocked */
    }
    return autoLook();
  }
  applyLook(firstLook());
})(typeof globalThis !== 'undefined' ? globalThis : this);
