#!/usr/bin/env python3
"""공모전 접수양식(docx)을 docs/proposal.md 원고로 채운다.

채우는 것: 01 참가신청서의 참가 단위·부문·성명·앱명·역할·인원, 구성원 표의 성명·역할,
02 작품 소개 1~8번 칸, 04 서명 표의 성명·앱명.
비워 두는 것(본인이 직접): 이메일, 휴대폰 번호, 작성일, 동의 여부, 서명, 서명일자.

사용: python3 -P tools/form/fill_form.py <양식.docx> <원고.md> <출력.docx>
python-docx 필요.
"""
import re
import sys

import docx

NAME = "편재식"
APP = "대동차지도 (大東車地圖) · Odomap"
ROLE = "기획·개발·디자인"


def fill_line(p, value):
    """밑줄(____) 칸 첫 조각에 값을 넣고 나머지 밑줄 조각은 비운다."""
    first = True
    for r in p.runs:
        if "_" not in r.text:
            continue
        if first:
            r.text = re.sub(r"_+", " " + value + " ", r.text, count=1).replace("_", "")
            first = False
        else:
            r.text = r.text.replace("_", "")


def check(p, word):
    """'□ 개인'의 □를 ■로."""
    for i, r in enumerate(p.runs):
        if word in r.text and "□" in r.text:
            r.text = r.text.replace("□ " + word, "■ " + word, 1)
            return
        if word in r.text and i > 0 and "□" in p.runs[i - 1].text:
            p.runs[i - 1].text = p.runs[i - 1].text.replace("□", "■", 1)
            return


def sections(md):
    """'## 1. …' ~ '## 8. …' 본문을 칸에 넣을 줄 목록으로."""
    out = {}
    for m in re.finditer(r"^## (\d)\. [^\n]*\n(.*?)(?=^## \d\. |\Z)", md, re.S | re.M):
        lines = []
        rows = []
        for raw in m.group(2).strip().splitlines():
            line = raw.strip()
            if not line:
                if lines and lines[-1] != "":
                    lines.append("")
                continue
            if line.startswith("|"):
                cells = [c.strip() for c in line.strip("|").split("|")]
                if all(re.fullmatch(r":?-+:?", c) for c in cells):
                    continue
                rows.append(cells)
                continue
            if rows:
                lines.extend(table_lines(rows))
                rows = []
            lines.append(clean(line))
        if rows:
            lines.extend(table_lines(rows))
        while lines and lines[-1] == "":
            lines.pop()
        out[int(m.group(1))] = lines
    return out


def table_lines(rows):
    head, body = rows[0], rows[1:]
    lines = []
    for r in body:
        first = clean(r[0])
        rest = [f"{clean(h)}: {clean(c)}" for h, c in zip(head[1:], r[1:]) if clean(c)]
        lines.append(f"• {first} — " + " / ".join(rest))
    lines.append("")
    return lines


def clean(s):
    s = re.sub(r"\*\*(.+?)\*\*", r"\1", s)
    s = re.sub(r"`([^`]*)`", r"\1", s)
    s = re.sub(r"^- ", "• ", s)
    return s


def put(cell, lines):
    paras = cell.paragraphs
    base = paras[0]
    base.text = lines[0] if lines else ""
    for extra in paras[1:]:
        extra._element.getparent().remove(extra._element)
    for line in lines[1:]:
        cell.add_paragraph(line, base.style)


def main(form, md_path, out):
    d = docx.Document(form)
    md = open(md_path, encoding="utf-8").read()
    for p in d.paragraphs:
        t = p.text
        if t.startswith("참가 단위"):
            check(p, "개인")
        elif t.startswith("참가 부문"):
            check(p, "일반")
        elif t.startswith("팀명(개인 참가 시 성명)"):
            fill_line(p, NAME)
        elif t.startswith("앱명") and "_" in t:
            fill_line(p, APP)
        elif t.startswith("대표자 성명"):
            # 성명 칸과 역할 칸이 한 줄에 있다
            runs = [r for r in p.runs if "_" in r.text]
            role_at = next(i for i, r in enumerate(runs) if "역할" in r.text)
            for i, r in enumerate(runs):
                if i == 0:
                    r.text = re.sub(r"_+", f" {NAME} ", r.text, count=1).replace("_", "")
                elif i == role_at:
                    r.text = re.sub(r"_+", f" {ROLE} ", r.text, count=1).replace("_", "")
                else:
                    r.text = r.text.replace("_", "")
        elif t.startswith("참가 인원"):
            fill_line(p, "1")
    members = d.tables[0]
    members.rows[1].cells[0].text = NAME
    members.rows[1].cells[4].text = ROLE
    secs = sections(md)
    # 아직 정하지 않은 칸('[선택 — 미정]')은 비워 둔다
    secs = {n: [l for l in ls if not l.startswith("[")] for n, ls in secs.items()}
    for n in range(1, 9):
        put(d.tables[n].rows[0].cells[0], secs.get(n, [""]))
    d.tables[9].rows[1].cells[1].text = NAME
    d.save(out)


if __name__ == "__main__":
    main(*sys.argv[1:4])
