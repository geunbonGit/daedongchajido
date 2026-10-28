#!/usr/bin/env python3
"""시·군·구 경계 머리카락 선(지도 바탕) — app/src/main/assets/regions/region-lines.json.

입력: tools/regions/out/regions.json (coverage simplification 60 m — 이웃 지역이 경계 꼭짓점을 그대로 나눠 가진다).
출력: GeoJSON FeatureCollection, 두 MultiLineString:
  {"kind": "sgg"}  — 같은 시·도 안의 시·군·구 사이 경계(앱: 0.5pt ink@0.10)
  {"kind": "sido"} — 시·도 사이 경계 + 바다 쪽 바깥 경계(앱: 1.0pt ink@0.18)
이웃이 나눠 가진 경계는 한 번만 그린다(두 번 그리면 겹친 곳만 진해진다). 선은 이어 붙인 뒤 다시 단순화한다(지도에서 줌 7~12에만 보인다).

  python3 -I tools/app-assets/build_region_lines.py
"""

import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "tools", "regions", "out", "regions.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "regions", "region-lines.json")
TOLERANCE_M = 90.0


def rings_of(region):
    for polygon in region["polygons"]:
        for ring in polygon:
            pts = [(ring[i], ring[i + 1]) for i in range(0, len(ring), 2)]
            if len(pts) >= 3:
                yield pts


def simplify(points, tol_m):
    """Douglas–Peucker on (latE6, lngE6) points with a local equirectangular metric."""
    if len(points) <= 2:
        return points
    lat0 = sum(p[0] for p in points) / len(points) / 1e6
    kx = math.cos(math.radians(lat0)) * 0.111320
    ky = 0.110574

    def xy(p):
        return (p[1] * kx, p[0] * ky)

    pts = [xy(p) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        best, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            if length2 == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length2))
                d = math.hypot(px - ax - t * dx, py - ay - t * dy)
            if d > best:
                best, at = d, i
        if best > tol_m and at > 0:
            keep[at] = True
            stack.append((a, at))
            stack.append((at, b))
    return [p for p, k in zip(points, keep) if k]


def chain(edges):
    """undirected edges (a, b) → polylines through degree-2 vertices"""
    adj = {}
    for a, b in edges:
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)
    used = set()
    lines = []

    def key(a, b):
        return (a, b) if a <= b else (b, a)

    def walk(start, nxt):
        line = [start, nxt]
        used.add(key(start, nxt))
        prev, cur = start, nxt
        while len(adj[cur]) == 2:
            a, b = adj[cur]
            n = b if a == prev else a
            k = key(cur, n)
            if k in used:
                break
            used.add(k)
            line.append(n)
            prev, cur = cur, n
        return line

    # start at ends / junctions first, then the remaining closed loops
    for v, ns in adj.items():
        if len(ns) != 2:
            for n in ns:
                if key(v, n) not in used:
                    lines.append(walk(v, n))
    for v, ns in adj.items():
        for n in ns:
            if key(v, n) not in used:
                lines.append(walk(v, n))
    return lines


def main():
    data = json.load(open(SRC, encoding="utf-8"))
    owners = {}
    for region in data["regions"]:
        for ring in rings_of(region):
            for i in range(len(ring)):
                a = ring[i]
                b = ring[(i + 1) % len(ring)]
                if a == b:
                    continue
                k = (a, b) if a <= b else (b, a)
                owners.setdefault(k, []).append(region["sidoCode"])
    sgg, sido = [], []
    for k, codes in owners.items():
        if len(codes) >= 2 and len(set(codes)) == 1:
            sgg.append(k)
        else:
            sido.append(k)
    out = {"type": "FeatureCollection", "features": []}
    total = 0
    for kind, edges in (("sgg", sgg), ("sido", sido)):
        coords = []
        for line in chain(edges):
            simple = simplify(line, TOLERANCE_M)
            if len(simple) < 2:
                continue
            total += len(simple)
            coords.append([[round(p[1] / 1e6, 5), round(p[0] / 1e6, 5)] for p in simple])
        out["features"].append({"type": "Feature", "properties": {"kind": kind}, "geometry": {"type": "MultiLineString", "coordinates": coords}})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"{OUT}: sgg {len(sgg)} edges, sido {len(sido)} edges → {total} points, {os.path.getsize(OUT) // 1024} KB", file=sys.stderr)


if __name__ == "__main__":
    main()
