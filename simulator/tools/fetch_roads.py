"""デモの道の周りの道路網を OSM（Overpass API）から取り、simulator/public/roads/<id>.json に書く。

旅れこの経路サーバーで出発地から目的地までの経路を引き、その経路から 300m 以内の車道を取る。
シミュレータの車はこの道路網の上だけを走る。

    VALHALLA_URL=https://… ROUTING_TOKEN=… python3 simulator/tools/fetch_roads.py

データは © OpenStreetMap contributors（ODbL）。
"""
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

ROUTES = {
    # id: (出発地, 目的地)。src/routes.ts と同じ地点
    "hakodate-onuma": ((41.7737, 140.7266), (41.9716, 140.6687)),
    "sapporo-jozankei": ((42.9420, 141.3197), (42.9675, 141.1638)),
    "biei-aoiike": ((43.5884, 142.4664), (43.4931, 142.6139)),
    "ogon-road": ((42.2869, 143.3170), (41.9255, 143.2486)),
}
# 車で走る道だけ。私道・農道・歩道は外す
HIGHWAYS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
CORRIDOR_METERS = 300
OVERPASS = "https://overpass-api.de/api/interpreter"
OUT = os.path.join(os.path.dirname(__file__), "..", "public", "roads")


def decode(shape, precision=1e6):
    index = lat = lon = 0
    points = []
    while index < len(shape):
        for k in range(2):
            result = shift = 0
            while True:
                b = ord(shape[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if k == 0:
                lat += delta
            else:
                lon += delta
        points.append((lat / precision, lon / precision))
    return points


def distance(a, b):
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


def route(start, end):
    body = json.dumps({"locations": [{"lat": start[0], "lon": start[1]}, {"lat": end[0], "lon": end[1]}], "costing": "auto"}).encode()
    request = urllib.request.Request(os.environ["VALHALLA_URL"].rstrip("/") + "/route", data=body, headers={
        "Authorization": "Bearer " + os.environ["ROUTING_TOKEN"], "Content-Type": "application/json"})
    return decode(json.load(urllib.request.urlopen(request, timeout=60))["trip"]["legs"][0]["shape"])


def thin(points, step=150):
    """Overpass に渡す点を 150m おきに間引く（around は点を結んだ線から測る）。"""
    kept = [points[0]]
    for p in points[1:]:
        if distance(kept[-1], p) >= step:
            kept.append(p)
    if kept[-1] != points[-1]:
        kept.append(points[-1])
    return kept


def roads_around(points):
    # 線からの距離（around に点を並べる形）は、経路によって何も返さないことがあった（2026-11-05、美瑛と函館）。
    # 150m おきの点のまわり 300m を足し合わせれば、線から 300m の帯をほぼ覆える
    circles = "".join(f'way(around:{CORRIDOR_METERS},{lat:.6f},{lon:.6f})[highway~"^({HIGHWAYS})$"];' for lat, lon in points)
    query = f"[out:json][timeout:180];({circles});(._;>;);out body;"
    request = urllib.request.Request(OVERPASS, data=urllib.parse.urlencode({"data": query}).encode(),
                                     headers={"User-Agent": "tabireco-simulator (jphacks/sp_2608)"})
    # 公開の Overpass は混むと 502・504 を返す。少し待って取り直す
    for attempt in range(4):
        try:
            return json.load(urllib.request.urlopen(request, timeout=240))["elements"]
        except urllib.error.HTTPError as error:
            if error.code not in (429, 502, 503, 504) or attempt == 3:
                raise
            time.sleep(30 * (attempt + 1))


def oneway(tags):
    value = tags.get("oneway")
    if value in ("yes", "true", "1"):
        return 1
    if value == "-1":
        return -1
    # 高速道路とその出入口は、書いていなくても一方通行
    if tags.get("highway") in ("motorway", "motorway_link") and value != "no":
        return 1
    return 0


def compact(elements):
    nodes = {e["id"]: (e["lat"], e["lon"]) for e in elements if e["type"] == "node"}
    index, out_nodes, out_ways = {}, [], []
    for e in elements:
        if e["type"] != "way":
            continue
        tags = e.get("tags", {})
        ids = []
        for node in e["nodes"]:
            if node not in nodes:
                continue
            if node not in index:
                index[node] = len(out_nodes)
                out_nodes.append([round(nodes[node][0], 6), round(nodes[node][1], 6)])
            ids.append(index[node])
        if len(ids) >= 2:
            way = {"name": tags.get("name", ""), "ref": tags.get("ref", ""), "highway": tags["highway"],
                   "oneway": oneway(tags), "nodes": ids}
            # 橋の上の道。真下の道の写真と見分けるのに使う
            if tags.get("bridge", "no") != "no":
                way["bridge"] = 1
            out_ways.append(way)
    return {"attribution": "© OpenStreetMap contributors (ODbL)", "fetched": date.today().isoformat(),
            "nodes": out_nodes, "ways": out_ways}


def main():
    os.makedirs(OUT, exist_ok=True)
    only = sys.argv[1:]
    for name, (start, end) in ROUTES.items():
        if only and name not in only:
            continue
        points = thin(route(start, end))
        data = compact(roads_around(points))
        path = os.path.join(OUT, f"{name}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        print(f"{name}: 道 {len(data['ways'])} 本、点 {len(data['nodes'])} 個、{os.path.getsize(path) // 1024} KB")
        # Overpass と経路サーバーの回数の上限に当たらないよう、少し空ける
        time.sleep(8)


if __name__ == "__main__":
    main()
