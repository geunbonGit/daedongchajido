# 대동차지도 (大東車地圖) — 달린 길이 지도가 된다

> **자동차(車)로 대한민국(大東) 땅(地) 위에 그리는 지도(圖)**
> 대동여지도(大東輿地圖)의 輿(수레)를 車로 바꾼 이름입니다.

**시동만 걸면 내가 달린 길이 저절로 나만의 지도가 되고, 처음 달린 길과 처음 가 본 시·군·구를 세어 주는 Pleos Connect 드라이브 기록 앱.**
계기판에 쌓이기만 하던 주행거리를 한 장의 지도로 바꿉니다.

| | |
|---|---|
| 소개 페이지 · QR 공유 카드 뷰어 | https://geunbongit.github.io/daedongchajido/ |
| 시연 영상 (1분 56초) | [`web/assets/demo.mp4`](web/assets/demo.mp4) · 소개 페이지에서 바로 재생 |
| 숏폼 (세로 54초) | [`web/assets/shortform.mp4`](web/assets/shortform.mp4) |
| Release APK | [`apk/편재식_대동차지도_release.apk`](apk/) |
| 기획서 · 디자인 | [docs/proposal.md](docs/proposal.md) · [docs/design.md](docs/design.md) |

![대동차지도 첫 화면 — 지금까지 달린 내 지도](docs/screens/01-home-paper.png)

---

## 핵심 기능

| 기능 | 무엇을 하나 | 차량 특화 |
|---|---|---|
| **자동 주행 기록** | 기어 D(또는 차속)로 출발을 알아채 백그라운드에서 기록하고, P에 두면 한 번의 드라이브로 정리합니다. 앱을 켜고 끌 필요가 없습니다. | Vehicle SDK·AAOS `GEAR_SELECTION`으로 시작·끝 판단, FusedLocation SDK 위치, 포그라운드 서비스 |
| **나만의 도로 지도** | 달린 길을 한 장의 종이 지도에 쌓습니다. 같은 길은 한 줄로 합치고 자주 달린 길일수록 굵게(1~5단계) 그립니다. | 칼만 + RTS 다듬기 → 도로 그물(상하행·흔들림 합치기) → z14 타일. 차 속도·도로 폭에 맞춘 규칙(`CarProfile`) |
| **처음 달린 길** | "이번 79.3km 중 처음 달린 길 76.8km" — 이번 드라이브에서 새로 개척한 거리를 따로 셉니다. | 주행 중엔 큰 숫자 셋만, 도착하면 요약 |
| **시·군·구 정복** | 전국 230곳 시·군·구 경계를 앱에 내장해 처음 들어선 지역을 모읍니다. "가 본 곳 25/230" + 전국 지도. | 새 지역은 **음성으로만** 안내: "가평군, 처음 와 보는 곳이에요." |
| **안 가 본 동네로** | 지금 위치에서 가까운 미방문 시·군·구 5곳을 고르고, 누르면 Pleos 내비가 바로 길 안내를 시작합니다. | NaviHelper SDK `requestRoute` |
| **다시 보기** | 드라이브 하나 / 지역 하나 / 내 지도 전체가 날짜 순으로 다시 그려집니다. 새 지역에 들어서면 날짜 줄이 지역 이름으로 바뀝니다. | 정차 중에만 열림 |
| **QR 공유** | 공유 카드(이번 드라이브 · 내 지도 · 가 본 곳)를 QR 하나에 담습니다. 휴대폰 카메라로 찍으면 [웹 뷰어](https://geunbongit.github.io/daedongchajido/)가 카드를 그리고 인스타그램 스토리·카카오톡으로 바로 보냅니다. | **서버 없음** — 기록은 QR 주소의 `#` 뒤에만 담겨 휴대폰으로만 전해짐 |
| **밤 배색 · 꾸미기** | 차가 밤 모드면 지도·판이 '밤' 배색으로 바뀝니다. 배색 17종, 선 굵기, 실제 지도(OpenFreeMap) 겹치기. | `uiMode` night 연동, 종이 지도는 오프라인 동작 |

**안전 원칙.** 주행 중에는 화면을 쓰지 않습니다. 앱은 `distractionOptimized` 앱으로 선언하고, 주행 중엔 큰 숫자 셋(이번 달린 길 · 이번 처음 달린 길 · 지금 지역)과 지도만 보여 줍니다. 기록·꾸미기·공유·다시 보기는 정차(P) 후에 열리고(`CarUxRestrictionsManager`), 새 지역·도착 안내는 소리로만 합니다.

## 화면

Pleos Connect 에뮬레이터(2560×1440)의 앱 창(1682×1210)을 그대로 잘라낸 화면입니다.

| | |
|---|---|
| ![첫 화면(종이)](docs/screens/01-home-paper.png) 첫 화면 — 내 지도와 오늘 숫자 | ![첫 화면(밤)](docs/screens/02-home-night.png) 밤 모드 — 차가 밤이면 자동으로 '밤' 배색 |
| ![주행 중](docs/screens/06-driving.png) 주행 중 — 큰 숫자 셋과 지도만, 안내는 소리로 | ![도착](docs/screens/07-arrival.png) 도착 — 처음 달린 길 · 첫 방문 · 다시 보기 |
| ![기록](docs/screens/03-records.png) 기록 — 내 지도 · 이번 주 · 지역 · 지난 드라이브 | ![가 본 곳](docs/screens/04-regions.png) 가 본 곳 — 전국 230곳 시·군·구 지도 |
| ![안 가 본 동네로](docs/screens/08-unvisited.png) 안 가 본 동네로 — 가까운 미방문 지역 → Pleos 내비 | ![QR 공유](docs/screens/05-share.png) QR 공유 — 카드 미리보기 = 휴대폰에 그려질 그림 |
| ![다시 보기](docs/screens/09-replay.png) 다시 보기 — 내 지도 전체를 날짜 순으로 | ![휴대폰 뷰어](docs/screens/phone-viewer.png) QR을 찍은 휴대폰 — 카드 저장·스토리·공유 ([스토리 카드](docs/screens/card-story.png)) |

---

## 실행 방법 (심사용)

### 1. Pleos Connect 에뮬레이터 설치

1. Android Studio → **Settings → Languages & Frameworks → Android SDK → SDK Update Sites** 에서 `+`로 사이트를 추가합니다.
   - Apple Silicon(arm64): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Intel/AMD(x86_64): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 탭에서 *Show Package Details* → Android 14 (API 34) 아래 **Pleos Connect v2.1.0 APIs** 시스템 이미지를 설치합니다(라이선스 동의 필요).
3. **Device Manager → Create Virtual Device** → 기기 *Pleos Connect* (2560×1440, 160 dpi) → 시스템 이미지 *Pleos Connect v2.1.0 APIs (API 34)* →
   Advanced: **RAM 4 GB**, Boot option **Cold boot** 로 만듭니다. (이 저장소는 AVD 이름 `Pleos_Connect`로 검증했습니다.)
4. 에뮬레이터를 켜고 홈 화면이 뜰 때까지 기다립니다.

### 2. APK 설치와 실행

```sh
adb install -r apk/편재식_대동차지도_release.apk
```

앱 목록에서 **대동차지도**를 엽니다(또는 `adb shell am start --user 10 -n com.odomap.app/.ui.MainActivity` — Pleos Connect의 운전자 사용자는 10번).
처음 열 때 위치·알림 권한을 허용합니다. 대화상자를 건너뛰었다면:

```sh
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.POST_NOTIFICATIONS android.car.permission.CAR_SPEED; do
  adb shell pm grant --user 10 com.odomap.app $p; done
```

### 3. 시연 주행으로 살펴보기

에뮬레이터는 실제로 달리지 않으므로, 실제 도로를 따라 만든 **시연 주행 14개**(출퇴근 10 · 가평 왕복 · 강릉 여행 · 동해안, 총 약 600km)를 앱에 넣어 두었습니다.

1. 아래 판 머리의 **‘오늘’을 길게 누르면**(넓은 창은 왼쪽 판 맨 위) 시연 패널이 열립니다. **설정 › 시연 패널**로도 열 수 있습니다. 재생 중에는 판 머리의 드라이브 이름을 길게 눌러 패널(멈춤)을 엽니다.
2. **모두 차례로** — 14개를 날짜 순으로 60배속 재생합니다(약 15~20분). 한 줄을 누르면 그 드라이브 하나만 재생합니다(가평 드라이브 약 2분).
   재생 중에도 실제 주행과 똑같이 기록·저장·음성 안내가 일어납니다.
3. **시연 중 주행 화면 제한 풀기** — 켜 두면(기본) 재생 중에도 모든 화면을 볼 수 있고, 끄면 실제 차처럼 주행 중 제한 화면(큰 숫자 셋 + 지도)이 나옵니다.
4. 재생이 끝나면 도착 요약 → **다시 보기**, 판 아래 **기록 · 가 본 곳(전국 지도)**, 위쪽 **공유(QR)** 를 눌러 봅니다.
   QR은 휴대폰 카메라로 찍으면 https://geunbongit.github.io/daedongchajido/s/#… 에서 카드가 그려집니다.
5. **안 가 본 동네로** — 현재 위치 기준 추천이라, 에뮬레이터 위치를 서울로 옮긴 뒤 누르면 자연스럽습니다.
   ```sh
   adb emu geo fix 126.9246 37.5563     # 경도 위도 — 홍대입구 근처
   ```
   지역의 **길 안내**를 누르면 NaviHelper가 Pleos 내비를 열어 경로를 안내합니다.
6. 패널의 **전체 삭제**로 처음 상태로 돌아갑니다.

밤 배색 확인: `adb shell cmd uimode night yes` (되돌리기 `no`). 꾸미기에서 배색을 '자동'으로 두면 따라 바뀝니다.

## 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 검증한 기기 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14 / API 34, `android-automotive`, arm64, 2560×1440 160 dpi, RAM 4 GB), 운전자 사용자 10 |
| 앱 | `com.odomap.app` 0.1.0 (versionCode 1), minSdk 28, target/compile 35, ABI arm64-v8a · x86_64 |
| 빌드 도구 | Android Studio (번들 JBR 21) 또는 JDK 17+, Gradle 8.10.2(wrapper), AGP 8.8.2, Kotlin 2.0.21, Compose BOM 2025.01 |
| 권한 | 위치(`ACCESS_FINE/COARSE_LOCATION`), 포그라운드 서비스(`FOREGROUND_SERVICE_LOCATION`), 알림, 부팅 후 시작, Pleos `FUSED_LOCATION` · `CAR_SPEED` · `CAR_DRIVE` · `CAR_INFO` · `CAR_MILEAGE` · `NAVI_ROUTE` · `NAVI_ROUTE_SEARCH`, AAOS `CAR_POWERTRAIN` · `CAR_SPEED`, 인터넷(실제 지도 겹치기만 — 종이 지도·기록·공유는 오프라인) |
| 네트워크 | 필요 없음. 공유 QR을 휴대폰에서 열 때만 휴대폰이 GitHub Pages의 정적 뷰어를 받습니다. |

### 직접 빌드

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties     # 한 번만
./gradlew :core:test :app:testDebugUnitTest                      # 테스트
./gradlew :app:assembleDebug                                     # 디버그 APK
./gradlew :app:assembleRelease                                   # release APK (R8 + 리소스 줄이기)
```

Release 서명은 저장소 밖 `~/.odomap/keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)에서 읽습니다(`-PodomapKeystoreProps=경로`로 바꿀 수 있음).
파일이 없으면 서명하지 않은 release APK를 만들고 경고만 남깁니다. 키스토어와 비밀번호는 저장소에 넣지 않습니다.

## APK 위치

`apk/편재식_대동차지도_release.apk` — release 빌드(R8 난독화·축소 + 리소스 줄이기, APK Signature Scheme v2 서명, 21.3 MB, arm64-v8a · x86_64).

## 사용한 Pleos SDK · AAOS API

| SDK / API | 버전 | 어디에 |
|---|---|---|
| **Pleos FusedLocation** (`ai.pleos.playground:FusedLocation`) | 1.1.3 | `registerFusedLocationCallback` — 1초 간격 위치. 없으면 Android `LocationManager`로 대신 (`location/`) |
| **Pleos Vehicle** (`ai.pleos.playground:Vehicle`) | 2.3.2 | 기어 상태 `getGear().registerGearSelection` — 주행 시작·끝 (`drive/CarDriveStateSources.kt`) |
| **Pleos NaviHelper** (`ai.pleos.playground:NaviHelper`) | 3.0.5 | `requestRoute` — 안 가 본 동네로 길 안내, 오류 코드별 안내 문구 (`navi/Navi.kt`) |
| AAOS `CarPropertyManager` | `android.car` | `GEAR_SELECTION`(우선), `PERF_VEHICLE_SPEED` — Pleos 서비스가 없을 때도 같은 판단. 둘 다 없으면 속도 규칙(`SpeedDriveDetector`) |
| AAOS `CarUxRestrictionsManager` | `android.car` | 주행 중 제한(`distractionOptimized` 앱) — 목록·꾸미기·공유·다시 보기를 정차 후로 |
| Android `TextToSpeech` | — | 새 지역 첫 방문·도착 요약 음성 안내 |

SDK 저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/` (`settings.gradle.kts`). `android.car`는 `required=false`라 일반 Android 기기·에뮬레이터에서도 앱이 돕니다.

---

## 구조와 기술

```
:core  순수 Kotlin/JVM 엔진 (android.* 없음)     :app  Android 앱 (Compose + MapLibre)
 ├ geo     거리, JS와 같은 hypot/round           ├ location  Pleos FusedLocation → Android 대체, 시연 재생
 ├ run     칼만 필터 + RTS 다듬기, 도플러 검사     ├ drive     기어(AAOS → Pleos) → 속도 규칙으로 주행 상태
 ├ explore 도로 그물, 처음 달린 길, z14 타일       ├ record    포그라운드 서비스 + DriveRecorder(엔진 스레드 하나)
 ├ car     CarProfile — 차에 맞춘 규칙 묶음        ├ store     기기 안 저장: z14 타일 JSON + 드라이브 폴더
 ├ region  시·군·구 230곳 판정·진입 추적·추천      ├ ui        홈 · 주행 · 도착 · 기록 · 드라이브 · 전국 지도 · 공유 · 다시 보기 · 꾸미기 · 시연 패널
 └ share   QR 공유 카드 인코더(qrcodegen)          └ navi, tts, ux(주행 제한)
web/   GitHub Pages 소개 페이지 + 서버 없는 QR 카드 뷰어 (빌드 단계·외부 요청 없음)
tools/ 골든 데이터 생성기(원본 TypeScript), 시·군·구 경계 가공, 시연 경로 생성, 글꼴
```

- **검증된 엔진을 비트 단위로 이식.** 휴대폰 앱 '발자취'에서 실제 사용자와 운영 중인 걷기 경로 엔진(칼만 + RTS, 도로 그물, 처음 걸은 길, 타일)을 Kotlin으로 옮기고,
  원본 TypeScript를 그대로 돌려 만든 **골든 데이터 79개**와 숫자 619,226개를 비교해 **모두 비트까지 같음**(`GoldenParityTest`, 최대 |Δ| = 0)을 확인했습니다.
- **차에 맞춘 규칙.** 같은 엔진에 `CarProfile`을 끼워 씁니다 — 20 m 간격 선, 스냅 18–32 m(분리 차로 10–30 m는 한 줄, 40 m 떨어진 길은 따로), 터널·끊긴 위치는 3 km·5분까지 잇기, 걸음 판별 끔, 차에 맞춘 칼만 잡음. 기본값은 원본 그대로라 골든 비교는 깨지지 않습니다.
- **테스트.** `:core` 172개 · `:app` 6개(시연 주행을 레코더로 재생해 Compose 화면을 Robolectric으로 그려 봄, QR을 zxing으로 다시 읽기) · `web/` 15개(Kotlin 인코더가 만든 벡터를 JS 디코더로 필드마다 비교). 모두 통과.
- **지역 판정.** 시·군·구 경계 230곳(약 2 MB)을 내장, 한 점 판정 약 0.3 µs. 30초 머물러야 진입으로 보고 바다 위 위치는 직전 지역을 유지합니다. 14개 시연 주행의 진입 순서를 파이썬 기준 구현과 하나하나 맞춥니다(`RegionTest`).

### 빌드 · 테스트 상세

앱 JVM 테스트: `./gradlew :app:testDebugUnitTest -PodomapRenderDir=/tmp/odomap` 는 시연 주행을 레코더로 재생해 PNG를 씁니다
(엔진 렌더 + Compose 화면의 Robolectric 스크린샷. JVM에서는 MapLibre GL이 돌지 않아 지도는 `CanvasMap`이 그림).
골든 비교 요약은 `GoldenParityTest`가 출력합니다(`[golden] N fixtures, … not bit-identical …`).

골든 데이터 다시 만들기:

```sh
tools/sync-ts.sh --check        # tools/ts/* 가 휴대폰 앱 원본과 아직 같은지 (tools/sync-ts.sh 는 다시 복사)
cd tools && npm install && npm run golden
```

### Core APIs beyond the port

- **Engine** (`explore`, no effect on results — golden parity stays bit-exact): `ExploreWalkSummary.drivenM` (counted distance; car name of `onFootM`)
  and `walkPathT` (time of each `walkPath` token); `ExploreRecorder.liveUpdate(sinceRevision)` → settled numbers + only the edges created/passed
  since that revision; `ExploreGraph.dirtyTileKeys()`, `exploreTileKeysOf`, `exploreEdgeTile`, `edgeByKey`; `exploreLinesByTile(edges, tiles, options)`
  for redrawing single z14 tiles; `DriveReplay.of(graph, summary)` → ordered edge moves (first-time/known, level, distance, time) and `pieces()`.
- **Regions** (`region`): `RegionIndex.load(regions.json)` (`tools/regions/out`), `regionAt(lat, lng)` — same answer as
  `tools/regions/validate_regions.py` for every point, ~0.3 µs; `RegionTracker` (30 s dwell, sea fixes keep the region) → `Entered` events,
  visited / passed / new; `nearestUnvisited(lat, lng, visited)` for "안 가 본 동네로". `RegionTest` replays the 14 demo drives and matches
  `tools/regions/out/demo-region-sequence.json` entry for entry (code, time, fix, start, first visit).
- **Share** (`share`, [docs/share-format.md](docs/share-format.md)): `ShareCards.drive / atlas / regions` build cards from engine data (300 m end
  trim along the path, home/work hiding; `hideRadiusM = SHARE_TRIM_M` also drops edges within 300 m straight-line of the start/end — the
  along-path rule alone leaves some drives' lines within ~15–50 m of an end), `ShareEncoder.encode(card)` fits the QR (version ≤ 20) →
  `ShareQr` (URL, module matrix; ECC M, raised to Q/H by qrcodegen only when the same version has room), `ShareCodec` encodes/decodes exactly.
  Region numbers on the wire = `Region.index`. Test vectors for the web viewer: `core/src/test/resources/share/vectors.json`
  (rewrite with `./gradlew :core:test -PodomapWriteVectors`; the test fails when the file is stale).

### Tuning hooks (for the car rule set)

- `ExploreRules` (net: step / snap / band / pass rules) — passed to `ExploreGraph(rules = …)`.
- `OnFootRules` and `ExplorePipelineConfig(runFilter, maxSpeedMps, onFootRules, onFootFilter)` — passed to `computeExploreWalk` / `ExploreRecorder`.
  `onFootFilter = false` stops dropping non-walking stretches (needed for driving).
- `RunFilter` (Kalman filter constants).
All defaults are the phone app's values, so the defaults reproduce the original bit for bit.

## 개인정보

- **모든 기록은 차 안에만** 저장됩니다(앱 내부 저장소의 드라이브 폴더와 z14 타일). 서버도, 계정도, 분석 도구도 없습니다. `allowBackup=false`.
- **공유는 서버 없이.** 공유 카드는 QR 주소의 `#` 뒤(fragment)에만 담깁니다. 브라우저는 fragment를 서버로 보내지 않으므로 GitHub Pages는 정적 파일 요청만 받고, 카드는 휴대폰 안에서 그려집니다. 뷰어는 `default-src 'self'` CSP로 외부 요청을 막습니다.
- 공유 카드는 기본으로 **출발·도착 근처를 가립니다**(드라이브 카드는 출발·도착 300 m, 내 지도 카드는 자주 가는 곳 500 m — 집·회사 위치 보호). 형식: [docs/share-format.md](docs/share-format.md).
- 시연 패널의 **전체 삭제**로 모든 드라이브와 지도를 지울 수 있습니다. 드라이브 화면에서 하나만 지우면 남은 기록으로 지도와 숫자를 다시 셉니다.

## 프로젝트 구조

| 경로 | 내용 |
|---|---|
| `app/` | Android 앱 `com.odomap.app` — `src/main/java/com/odomap/app/` (위 구조도), `assets/drives`(시연 주행 14개), `assets/regions`(시·군·구 경계), `assets/map`(실제 지도 스타일), `res/font`(Pretendard) |
| `core/` | 순수 Kotlin 엔진 + 테스트(`src/test/resources/golden` 골든 데이터, `share/vectors.json`, `drives`, `regions`) |
| `web/` | GitHub Pages: 소개 페이지 `index.html`, QR 카드 뷰어 `s/`, 시연 영상 `assets/demo.mp4`, 화면 `assets/screens/` — [web/README.md](web/README.md) |
| `tools/` | `ts/`(원본 TypeScript), `gen-golden.ts`, `regions/`(경계 가공·검증 — [README](tools/regions/README.md)), `routes/`(시연 경로 생성), `fonts/` |
| `docs/` | [기획서](docs/proposal.md) · [디자인](docs/design.md) · [발자취 디자인 값](docs/design-footprint-ref.md) · [공유 형식](docs/share-format.md) · `screens/`(README 화면) |
| `apk/` | 제출용 release APK |

## 출처와 라이선스

- **지역 경계 데이터** (`app/src/main/assets/regions`, `web/data`) — CC BY 4.0:
  > 본 데이터는 통계청 통계지리정보서비스(SGIS, https://sgis.kostat.go.kr)에서 공공누리 제1유형으로 개방한 행정동 경계를 가공한 것이며(가공: vuski/admdongkor, https://github.com/vuski/admdongkor), CC BY 4.0으로 배포됩니다. 시·군·구 단위 병합·단순화: 대동차지도.
- **글꼴** — [Pretendard](https://github.com/orioncactus/pretendard) © Kil Hyung-jin, SIL Open Font License 1.1 (`app/src/main/assets/licenses/Pretendard-OFL.txt`, `web/fonts/LICENSE.txt`; 웹용 조각 글꼴은 OFL 예약 이름 규칙에 따라 'Chajido Sans').
- **지도** — [MapLibre Native](https://github.com/maplibre/maplibre-native) (BSD 2-Clause). 선택형 실제 지도 타일: [OpenFreeMap](https://openfreemap.org) · © OpenMapTiles · © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL).
- **시연 주행 경로** — [OSRM](https://project-osrm.org) 공개 데모 서버로 OpenStreetMap 도로(© OpenStreetMap contributors, ODbL)를 따라 만든 가상 GPS 기록(`tools/routes`).
- **QR 코드** — [Nayuki QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) (`io.nayuki:qrcodegen`, MIT).
- **Pleos Playground SDK** (FusedLocation · Vehicle · NaviHelper) — 42dot Inc., Pleos Connect SDK 라이선스.
- AndroidX · Jetpack Compose · Kotlin coroutines (Apache 2.0). 테스트: JUnit, Robolectric, Roborazzi, ZXing (Apache 2.0).

---

## English summary

**Daedong Chajido (大東車地圖, 대동차지도)** — *the roads you drive become your map.* The name swaps the 輿 (carriage) of the 1861 Daedong Yeojido map for 車 (car): "a map drawn with a car over the land of Korea".
It is a Pleos Connect (Android Automotive) app for the Hyundai Motor Group in-car app contest. It records every drive automatically
(gear D → P via the Pleos Vehicle SDK / AAOS `GEAR_SELECTION`, positions from the Pleos FusedLocation SDK), merges the roads you drive into one
paper-style map (frequent roads drawn thicker), counts first-time roads and the 230 si/gun/gu districts you have visited (announced by voice only
while driving), routes you to the nearest unvisited district through the Pleos NaviHelper SDK, replays your map, and shares cards to a phone
through a serverless QR code (the record lives only in the URL fragment). The core engine is a bit-exact Kotlin port of a production walking-path
engine (79 golden fixtures, 619,226 numbers identical), tuned for cars with `CarProfile`. Everything stays on the device.
Install `apk/편재식_대동차지도_release.apk` on the Pleos Connect emulator (API 34, v2.1.0) and long-press the sheet header '오늘' (or Settings › 시연 패널) to replay the 14 bundled demo drives.
