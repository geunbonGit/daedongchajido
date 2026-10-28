#!/usr/bin/env python3
"""Splits the Pretendard KS X 1001 subsets into small unicode-range chunks so a page only downloads the glyphs it shows.

    python3 -I web/tools/build-fonts.py          (needs fontTools; brotli from pip, or Node via tools/brotli-shim)

Why: the share card waits for its fonts before drawing (canvas text does not swap fonts later). The two official subsets
it used are 265 KB each, so on a phone the card appeared ~1 s after the page. The card's own text is a handful of
fixed words + digits + the drive title, so chunk "a" (ASCII + card words) is enough for weight 500, and a title almost
always lives in chunk "b" (frequent syllables, region names, every word the pages use). Rare syllables come from
codepoint-ordered "c" chunks only when some text actually uses them (CSS unicode-range + document.fonts.load(text)).

Input : web/tools/fonts-src/Pretendard-{Medium,Bold,ExtraBold}.subset.woff2 (official Pretendard 1.3.9 web subsets)
Output: web/fonts/chajido-sans-<weight>-<chunk>.woff2 and web/fonts/fonts.css (@font-face rules, family 'Chajido Sans').

License: Pretendard is SIL OFL 1.1 with Reserved Font Name "Pretendard". These chunks are a Modified Version, so their
internal names say "Chajido Sans" (derived from Pretendard), and fonts/LICENSE.txt ships next to them.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.dirname(HERE)
try:
    import brotli  # noqa: F401
except ImportError:
    sys.path.insert(0, os.path.join(HERE, "brotli-shim"))

from fontTools import subset  # noqa: E402
from fontTools.ttLib import TTFont  # noqa: E402

FAMILY = "Chajido Sans"
SLUG = "chajido-sans"
SRC = os.path.join(HERE, "fonts-src")
OUT = os.path.join(WEB, "fonts")
WEIGHTS = {500: ("Pretendard-Medium.subset.woff2", "Medium"), 700: ("Pretendard-Bold.subset.woff2", "Bold"), 800: ("Pretendard-ExtraBold.subset.woff2", "ExtraBold")}
C_CHUNK = 330  # Hangul syllables per "c" chunk (≈ 30 KB)

# chunk a — what every card draws: ASCII, the punctuation the card and pages use, and the Hangul of the fixed card words
PUNCT = " ·×–—‘’“”…→←©›‹"
CARD_WORDS = "대동차지도 일월화수목금토 처음 달린 길 내 지도 가 본 시·군·구 곳 드라이브 번 첫 방문 시간 분 미만 오전 오후 출근 퇴근 여행"

# chunk b — frequent syllables (everyday Korean + drive vocabulary); region names and page text are added below
FREQUENT = (
    "가각간갈감갑강개객거건걸검것게겨격견결경계고곡곤골곳공과관광교구국군굴궁권귀규균그극근글금급기긴길김까깨꼬꽃꾸꿈끄끝"
    "나낙난날남납낭내냉너널네녀년념녕노녹논놀농높뇌누눈느늘능니다단달담답당대댁더덕던데도독돈돌동돼되된두둘뒤드득든들등디따때떠또뜻"
    "라락란람랑래랭량러럼렇레려력련렬령례로록론롭료루류륙률르른를름릉리린림립마막만많말망매맥맞머먹먼멀메며면명모목몰몸못몽무묵문물뭐므미민밀및"
    "바박밖반받발밝밤방배백버번벌범법벗베벼변별병보복본볼봄봉부북분불붙비빈빌빛빠빨뻐뿐사산살삼상새색생서석선설섬성세셋셔소속손솔송수숙순술숨쉬스슬습승시식신실싫심십싸쌍쓰씨"
    "아악안않알암압앗앙앞애액야약양어억언얼엄업없었에여역연열염엽영예오옥온올옷와완왕외요욕용우운울움웃원월위유육윤으은을음응의이익인일임입있잊"
    "자작잔잘잠잡장재쟁저적전절젊점접정제져조족존졸종좋좌주죽준줄중즉즐증지직진질집짓징짜째쪽찍차착찬찰참창찾채책처천철첫청체초촌총최추축춘출춤충취측층치친칙칠침"
    "카커컴케코콘쿠크큰키타탁탄탈탐탑태택터테토통퇴투특튼트틀티파판팔패퍼페펴편평폐포폭표푸품풍프피필하학한할함합항해핵행향허험혁현혈협형혜호혹혼홍화확환활황회획효후훈휘휴흐흑흔흘흥희흰히힘"
    "휴게소터널고개해변바닷가숲계곡폭포온천축제야경일출몰단풍벚꽃캠핑낚시등산산책주말휴가여름가을겨울새벽아침저녁점심오늘어제내일집회사학교병원역공항카페맛집마트"
    "엄마아빠가족친구우리함께좋은행복즐거운멋진드디어마지막기념생일결혼데이트출장이사연습짧은긴먼가까운동네한바퀴천천히맑은흐린안개"
)

PAGE_FILES = ["index.html", "s/index.html", "s/viewer.js", "assets/format.js", "assets/tokens.js", "assets/card.js"]


def hangul(text):
    return {ord(c) for c in text if 0xAC00 <= ord(c) <= 0xD7A3}


def page_text():
    out = set()
    for rel in PAGE_FILES:
        path = os.path.join(WEB, rel)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                out |= hangul(f.read())
    with open(os.path.join(WEB, "data", "regions.json"), encoding="utf-8") as f:
        for r in json.load(f)["regions"]:
            out |= hangul(r.get("label", "") + r.get("name", "") + r.get("sido", ""))
    return out


def ranges(cps):
    """sorted codepoints → CSS unicode-range list"""
    cps = sorted(cps)
    parts = []
    i = 0
    while i < len(cps):
        j = i
        while j + 1 < len(cps) and cps[j + 1] == cps[j] + 1:
            j += 1
        parts.append(f"U+{cps[i]:X}" if i == j else f"U+{cps[i]:X}-{cps[j]:X}")
        i = j + 1
    return ", ".join(parts)


def plan(cmap_cps):
    have = set(cmap_cps)
    a = (set(range(0x20, 0x7F)) | {ord(c) for c in PUNCT} | hangul(CARD_WORDS)) & have
    b = ((hangul(FREQUENT) | page_text()) & have) - a
    rest_h = sorted(c for c in have if 0xAC00 <= c <= 0xD7A3 and c not in a and c not in b)
    cs = [set(rest_h[i : i + C_CHUNK]) for i in range(0, len(rest_h), C_CHUNK)]
    x = have - a - b - set(rest_h)
    chunks = [("a", a), ("b", b)] + [(f"c{i}", c) for i, c in enumerate(cs)] + [("x", x)]
    return [(n, c) for n, c in chunks if c]


def rename(font, style):
    full = f"{FAMILY} {style}"
    ps = f"{FAMILY.replace(' ', '')}-{style}"
    table = font["name"]
    for rec in list(table.names):
        if rec.nameID in (1, 16):
            rec.string = FAMILY
        elif rec.nameID == 3:
            rec.string = f"{ps};derived from Pretendard"
        elif rec.nameID in (4,):
            rec.string = full
        elif rec.nameID == 6:
            rec.string = ps
    # the original copyright (nameID 0), license (13/14) stay as they are


def main():
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        if old.startswith(SLUG) and old.endswith(".woff2"):
            os.remove(os.path.join(OUT, old))
    css = [
        "/* Generated by web/tools/build-fonts.py — do not edit. 'Chajido Sans' = Pretendard (SIL OFL 1.1, fonts/LICENSE.txt),",
        "   split into unicode-range chunks; renamed because the OFL reserves the name \"Pretendard\" for unmodified fonts. */",
    ]
    report = {}
    for weight, (src_name, style) in WEIGHTS.items():
        src = os.path.join(SRC, src_name)
        base = TTFont(src)
        chunks = plan(base.getBestCmap().keys())
        faces = []
        for name, cps in chunks:
            font = TTFont(src)
            opts = subset.Options()
            opts.flavor = "woff2"
            opts.layout_features = ["kern", "ccmp", "locl", "liga", "mark", "mkmk", "tnum"]  # kerning + composition; calt/case alternates would double chunk "a"
            opts.name_IDs = ["*"]
            opts.name_languages = ["*"]
            opts.notdef_outline = True
            opts.glyph_names = False
            opts.hinting = False
            opts.desubroutinize = True
            sub = subset.Subsetter(opts)
            sub.populate(unicodes=cps)
            sub.subset(font)
            rename(font, style)
            file = f"{SLUG}-{weight}-{name}.woff2"
            font.flavor = "woff2"
            font.save(os.path.join(OUT, file))
            size = os.path.getsize(os.path.join(OUT, file))
            report[file] = {"glyphs": len(cps), "kb": round(size / 1024, 1)}
            # exact ranges, never overlapping: Chrome starts downloading EVERY face whose unicode-range covers a
            # character that is not loaded yet, so a wide "c" span would pull rare chunks for common text.
            span = ranges(cps)
            faces.append((name, "@font-face {\n"
                f"  font-family: '{FAMILY}';\n  font-weight: {weight};\n  font-style: normal;\n  font-display: swap;\n"
                f"  src: url('{file}') format('woff2');\n  unicode-range: {span};\n}}"))
        order = lambda n: 0 if n == "x" else 1 if n.startswith("c") else 2 if n == "b" else 3  # noqa: E731  (a last = tried first)
        css.extend(rule for _, rule in sorted(faces, key=lambda f: order(f[0])))
    with open(os.path.join(OUT, "fonts.css"), "w", encoding="utf-8") as f:
        f.write("\n".join(css) + "\n")
    for k, v in report.items():
        print(f"{k:32s} {v['glyphs']:5d} glyphs {v['kb']:7.1f} KB")
    print("total", round(sum(v["kb"] for v in report.values()), 1), "KB")


if __name__ == "__main__":
    main()
