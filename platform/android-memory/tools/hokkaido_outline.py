# 市町村の多角形のうち、どこにも共有されない辺（＝海岸線）をつないで、間引いた輪郭を書き出す
# 元データ: 国土数値情報 行政区域データ 北海道 N03-20250101_01（CC BY 4.0）
#   https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N03-2025.html
# 使い方: python3 hokkaido_outline.py N03-20250101_01.geojson ../src/main/assets/hokkaido_outline.bin 0.0002
import json, sys, struct
from collections import Counter, defaultdict
src, out, tol = sys.argv[1], sys.argv[2], float(sys.argv[3])
g = json.load(open(src))
edges = Counter()
for f in g["features"]:
    geom = f["geometry"]
    polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
    for poly in polys:
        for ring in poly:
            pts = [(round(x, 7), round(y, 7)) for x, y in ring]
            for a, b in zip(pts, pts[1:]):
                if a != b: edges[frozenset((a, b))] += 1
outer = [tuple(e) for e, n in edges.items() if n == 1 and len(e) == 2]
# 頂点がずれて内側に残った境界を消す：辺の中点の両脇（約1m）がどちらも陸なら内側の辺
import numpy as np
from matplotlib.path import Path
rings = []
for f in g["features"]:
    geom = f["geometry"]
    for poly in ([geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]):
        rings.append([np.array(r) for r in poly])
E = np.array([[a[0], a[1], b[0], b[1]] for a, b in outer])
mid = (E[:, :2] + E[:, 2:]) / 2
d = E[:, 2:] - E[:, :2]; n = np.stack([-d[:, 1], d[:, 0]], 1)
n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
sides = [mid + n * 1e-5, mid - n * 1e-5]
land = [np.zeros(len(E), bool), np.zeros(len(E), bool)]
for poly in rings:
    o = poly[0]; lo, hi = o.min(0), o.max(0)
    for s_, l_ in zip(sides, land):
        m = np.all((s_ >= lo) & (s_ <= hi), 1) & ~l_
        if not m.any(): continue
        inside = Path(o).contains_points(s_[m])
        for h in poly[1:]: inside &= ~Path(h).contains_points(s_[m])
        l_[np.flatnonzero(m)[inside]] = True
outer = [e for e, a, b in zip(outer, *land) if not (a and b)]
print("coast edges", len(outer))
adj = defaultdict(list)
for a, b in outer: adj[a].append(b); adj[b].append(a)
used = set(); lines = []
for a, b in outer:
    if frozenset((a, b)) in used: continue
    used.add(frozenset((a, b))); line = [a, b]
    for end in (1, 0):  # 両側へ伸ばす
        while True:
            cur = line[-1] if end else line[0]
            nxt = next((n for n in adj[cur] if frozenset((cur, n)) not in used), None)
            if nxt is None: break
            used.add(frozenset((cur, nxt)))
            line.append(nxt) if end else line.insert(0, nxt)
    lines.append(line)
def dp(pts):  # Douglas–Peucker（度で、経度は cos 43° で縮める）
    k = 0.73
    keep = [False] * len(pts); keep[0] = keep[-1] = True; stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop(); (x1, y1), (x2, y2) = pts[i], pts[j]; best, idx = 0, -1
        dx, dy = (x2 - x1) * k, y2 - y1; L = (dx * dx + dy * dy) ** .5
        for m in range(i + 1, j):
            px, py = (pts[m][0] - x1) * k, pts[m][1] - y1
            d = abs(dx * py - dy * px) / L if L else (px * px + py * py) ** .5
            if d > best: best, idx = d, m
        if best > tol: keep[idx] = True; stack += [(i, idx), (idx, j)]
    return [p for p, kp in zip(pts, keep) if kp]
lines = [dp(l) for l in lines]
# 差し渡し 150m 未満の岩や小島は描かない
def extent(l): xs = [p[0] for p in l]; ys = [p[1] for p in l]; return max((max(xs) - min(xs)) * 0.73, max(ys) - min(ys))
lines = [l for l in lines if len(l) >= 2 and extent(l) >= 0.00135]
with open(out, "wb") as fo:
    fo.write(struct.pack(">i", len(lines)))
    for l in lines:
        fo.write(struct.pack(">i", len(l)))
        for x, y in l: fo.write(struct.pack(">ii", round(y * 1e5), round(x * 1e5)))
print(len(edges), "edges,", len(outer), "outer,", len(lines), "lines,", sum(map(len, lines)), "points")
