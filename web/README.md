# web/ — 대동차지도 사이트 (GitHub Pages)

`.github/workflows/pages.yml`이 이 폴더를 그대로 https://geunbongit.github.io/daedongchajido/ 에 올립니다. 빌드 단계도, 의존성도, 외부 요청도 없습니다.

| 경로 | 무엇 |
|---|---|
| `index.html` · `assets/landing.{css,js}` | 심사위원용 소개 페이지 (APK·소스 링크, 시연 영상, 차 화면, 샘플 카드) |
| `s/` | 공유 카드 뷰어 — 차의 QR을 휴대폰 카메라로 찍으면 열리는 페이지 |
| `assets/share-decode.js` | QR payload 디코더 ([docs/share-format.md](../docs/share-format.md) v1) |
| `assets/card.js` · `assets/format.js` · `assets/tokens.js` | 카드 그리기 (발자취 `drawExploreCard` 이식), 숫자·날짜 표기, 디자인 값 |
| `data/regions.json` · `data/outline.json` | 시·군·구 230곳 목록과 표시용 윤곽 (`tools/build-data.mjs`) |
| `fonts/` | 'Chajido Sans' = Pretendard를 unicode-range 조각으로 나눈 것 (`tools/build-fonts.py`) |
| `test/` | Node 테스트, 카드 갤러리 `test/cards.html` |

## 어떻게 동작하나

```
https://geunbongit.github.io/daedongchajido/s/#AEAQ…   ← '#' 뒤 = base32 이진 데이터 (날짜·거리·지역·선)
```

1. `s/viewer.js`가 `location.hash`를 디코드합니다 (`share-decode.js`). 모르는 version/kind/regionSet이면 "앱을 업데이트해 주세요", 잘린 주소면 "카드를 읽지 못했어요"를 보여 줍니다.
2. `card.js`가 카드를 캔버스에 그립니다 — 정사각 1080×1080 · 스토리 1080×1920, 발자취 카드와 같은 틀(design-footprint-ref §7, design.md §6). 선은 모두 먹 한 색(새 길도 색을 칠하지 않음), 자주 달린 길은 더 굵게, 큰 숫자는 `+12.43 km 처음 달린 길`.
3. 그린 캔버스를 바로 보여 주고, PNG가 만들어지면 `<img>`로 바꿉니다(길게 눌러 저장). **이미지 저장** · **인스타 스토리**(스토리 크기 파일을 Web Share로) · **공유하기**(Web Share, 안 되면 링크 복사). 영상 탭은 MediaRecorder로 약 4.8초 그리기 영상을 만듭니다(지원하는 브라우저만).
4. 비율·바탕(종이·흰색·한지·밤) 선택은 이 휴대폰의 localStorage에만 남습니다. 휴대폰이 다크 모드면 처음 바탕은 '밤'(발자취 '자동')입니다.

## 개인정보

- 드라이브 기록은 주소의 `#` 뒤(fragment)에만 있습니다. 브라우저는 fragment를 서버로 보내지 않으므로 GitHub Pages는 정적 파일 요청만 받습니다.
- 페이지는 자기 파일 말고는 아무것도 부르지 않습니다: 분석 도구·추적기·외부 글꼴·CDN 없음. `Content-Security-Policy`(`default-src 'self'`)로 강제합니다. `s/`는 `noindex`, `no-referrer`.
- 링크 미리보기 이미지(`assets/og-card.png`)는 숫자가 없는 공용 그림입니다. 미리보기 서버도 카드 내용을 볼 수 없습니다.

## 속도

카드는 글꼴이 다 온 뒤에 그려야 합니다(캔버스 글자는 나중에 바뀌지 않음). 예전에는 265 KB짜리 Pretendard 두 벌을 기다려서, 휴대폰에서 카드가 약 1.1초 뒤에 나왔습니다. 지금은:

- `tools/build-fonts.py`가 글꼴을 조각으로 나눕니다: **a** = ASCII + 카드 고정 낱말(약 11 KB), **b** = 자주 쓰는 음절 + 지역 이름 + 페이지 글자(약 36 KB), **c0–c6** = 나머지 한글(코드 순), **x** = 기호 등. 카드는 500-a · 700-a · 700-b만 필요하고(제목에 드문 글자가 있으면 그 c 조각 하나 더), 세 조각은 `<link rel=preload>`로 HTML과 함께 받습니다.
- 모듈은 `modulepreload`로 한꺼번에 받고, 화면 구성은 글꼴을 기다리지 않습니다. 샘플 payload(`samples.js`)는 빈 화면에서만 부릅니다.
- 3초 안에 글꼴이 안 오면 대체 글꼴로 먼저 그리고, 글꼴이 오면 다시 그립니다.

측정(로컬 throttling 서버, gzip, 캐시 없음, 샘플 드라이브 카드, 내비게이션 시작 → 카드 캔버스 표시, 5회 중앙값):

| 회선 | 전 | 후 |
|---|---|---|
| 5 Mbps · RTT 80 ms | ≈ 1170 ms | ≈ 430 ms |
| 20 Mbps · RTT 50 ms | ≈ 410 ms | ≈ 270 ms |

카드 한 장 그리기는 1–11 ms(지역 지도 카드가 가장 깁니다). `s/?perf#…`로 열면 콘솔에 `[odomap] card drawn … ms`가 찍히고, `performance.getEntriesByName('odomap-card')`로도 읽을 수 있습니다.

## 개발

```sh
cd web && python3 -m http.server 8000      # http://localhost:8000/ , http://localhost:8000/s/#<payload>
node --test web/test/                       # 저장소 루트에서 (또는 cd web && npm test)
```

- 테스트: `core/src/test/resources/share/vectors.json`(Kotlin 인코더가 만든 벡터)을 필드마다 비교하고, 모르는 필드가 있으면 실패합니다. 그 밖에 `test/vectors.local.json`(명세대로 만든 JS 인코더 `test/encoder.mjs`), 무작위 왕복 300개, 잘린 payload, base32 RFC 4648, 표기 규칙, 글꼴 조각(모든 페이지 글자가 a·b에 있는지).
- `test/cards.html` — 벡터마다 두 비율 × 네 바탕을 그려 보는 갤러리. `?looks=paper,night`, `?only=이름`, `?vectors=<URL>`, `?all`.
- `node tools/make-samples.mjs` — 데모 주행(`app/src/main/assets/drives/`)으로 샘플 payload를 만들어 `assets/samples.js`, `test/vectors.local.json`, `index.html`의 `data-sample` 링크를 갱신.
- `node tools/build-data.mjs` — `tools/regions/out/`에서 `data/regions.json`·`data/outline.json`을 만듦.
- `python3 web/tools/build-fonts.py` — 글꼴 조각과 `fonts/fonts.css`를 다시 만듦. **페이지 글을 고친 뒤에는 다시 돌리세요**(테스트가 알려 줍니다). fontTools가 필요하고, `brotli` 파이썬 패키지가 없으면 `tools/brotli-shim/`이 Node 내장 brotli를 씁니다. 원본은 `tools/fonts-src/`(Pretendard 공식 KS X 1001 subset woff2).

### 영상 · 화면

- `assets/demo.mp4` — 시연 영상(1920×1080 H.264 + AAC, 약 1분 56초), 포스터 `assets/demo-poster.jpg`. 파일이 없으면 포스터와 "곧 올릴게요"가 보입니다.
- `assets/shortform.mp4` — 세로 숏폼(1080×1920, 약 52초).
- `assets/screens/01.png`–`04.png` — Pleos Connect 에뮬레이터 앱 창(1682×1210) 그대로: 내 지도 · 밤 배색 · 가 본 곳 · QR 공유. 없으면 `placeholder.svg`.
- APK는 GitHub Releases의 최신 릴리스(`/releases/latest`)로 연결됩니다.

## 출처 표시

**지역 경계 데이터** (`data/regions.json`, `data/outline.json`, 카드의 지역 지도) — CC BY 4.0:

> 본 데이터는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)에서 공공누리 제1유형으로 개방한 행정동 경계를 가공한 것이며(가공: vuski/admdongkor, https://github.com/vuski/admdongkor), CC BY 4.0으로 배포됩니다. 시·군·구 단위 병합·단순화: 대동차지도.

> Derived from administrative-dong boundaries released by Statistics Korea SGIS (https://sgis.kostat.go.kr) under KOGL Type 1, modified by vuski/admdongkor (https://github.com/vuski/admdongkor), distributed under CC BY 4.0. Aggregated to si/gun/gu and simplified by Daedong Chajido.

같은 문구가 소개 페이지와 뷰어 아래에 있고, '가 본 곳' 카드 이미지에도 짧게(`지역 경계 © 통계청 SGIS · vuski/admdongkor CC BY 4.0`) 들어갑니다.

**글꼴** — [Pretendard](https://github.com/orioncactus/pretendard) © Kil Hyung-jin, SIL Open Font License 1.1 (`fonts/LICENSE.txt`). OFL은 고친 글꼴에 예약 이름 "Pretendard"를 쓰지 못하게 하므로, 조각으로 나눈 글꼴의 이름은 'Chajido Sans'입니다(저작권·라이선스 표기는 글꼴 안에 그대로 둠).
