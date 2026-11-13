"""走り比べる2本の道（ふつうの道と、旅れこのおすすめの道）を取り、シミュレータ用のデータを書く。

    VALHALLA_URL=https://… ROUTING_TOKEN=… python3 simulator/tools/fetch_compare.py [道の id …]

- おすすめの道：旅れこの景観ルート推薦（POST /route-recommendations）の「景観と時間のバランス」の案。
- ふつうの道：GOOGLE_ROUTES_API_KEY があれば Google の Routes API（Google マップの案内と同じ経路計算）。
  無いか使えなければ、旅れこの経路サーバーの基準の経路（寄り道なしの最短時間）で代用し、source に valhalla と書く。
- simulator/public/compare/<id>.json に2本の道の形・距離・時間・景観の内訳を書く。
- simulator/public/roads/<id>.json を、2本の道の両方から 300m 以内の車道で作り直す。

道路のデータは © OpenStreetMap contributors（ODbL）。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

from fetch_roads import OUT as ROADS_OUT, compact, decode, distance, roads_around, thin

# id: (出発地, 目的地, 寄り道に使ってよい時間の秒[, おすすめの道に選ぶ案の役割])。出発地と目的地は src/routes.ts と同じ地点。
# 役割を省くと「景観と時間のバランス」。その案が無いか、寄り道になっていなければ、寄り道のある案のうち点数の高いもの
ROUTES = {
    "hakodate-onuma": ((41.773436, 140.726639), (41.9808, 140.6701), 1800),
    "sapporo-jozankei": ((42.943609, 141.317415), (42.97, 141.1664), 1800),
    "biei-aoiike": ((43.588398, 142.466396), (43.4936, 142.6143), 1800),
    "ogon-road": ((42.286832, 143.316841), (41.9244, 143.2494), 1800),
    # 短い道（5〜15分）。寄り道の時間も短くする
    "onuma-lake": ((41.9716, 140.6687), (42.0007, 140.7118), 600),
    # 橋へ上がる道と下の道が分かれる所の少し手前から出る
    "hakodate-tomoe": ((41.79004, 140.728635), (41.773436, 140.726639), 300, "自然を満喫"),
}
# 景色の好み。アプリの初期の好みに近い、偏りの少ない値
PREFERENCES = {"sea": 0.3, "forest": 0.2, "lake": 0.3, "river": 0.1, "variety": 0.5, "passenger_view": 0.5, "certified_road": 0.5}
DEPARTURE = "2026-11-14T10:00:00+09:00"
PREFERRED_ROLE = "景観と時間のバランス"
COMPARE_OUT = os.path.join(os.path.dirname(__file__), "..", "public", "compare")
# Overpass に1回で渡す円の数。多すぎると時間切れになる
CIRCLES_PER_QUERY = 120


def call(path, body=None):
    request = urllib.request.Request(os.environ["VALHALLA_URL"].rstrip("/") + path,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + os.environ["ROUTING_TOKEN"], "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request, timeout=60))


def recommend(start, end, extra_seconds):
    job = call("/route-recommendations", {
        "origin": {"lat": start[0], "lon": start[1]}, "destination": {"lat": end[0], "lon": end[1]},
        "mode": "point_to_point", "departure_time": DEPARTURE,
        "budget": {"max_extra_driving_seconds": extra_seconds}, "preferences": PREFERENCES,
        "constraints": {"allow_tolls": True, "allow_highways": True, "allow_ferries": False, "allow_unpaved": True},
        "night_correction": True, "max_routes": 6, "seed": 42})
    for _ in range(90):
        result = call(f"/route-recommendations/{job['request_id']}?view=status")
        if result["status"] in ("complete", "limited", "failed", "infeasible"):
            return result
        time.sleep(2)
    raise RuntimeError("推薦が時間内に終わりませんでした")


def simplify(points, step=8):
    """道の形を step メートルおきに間引く。"""
    kept = [points[0]]
    for p in points[1:]:
        if distance(kept[-1], p) >= step:
            kept.append(p)
    if kept[-1] != points[-1]:
        kept.append(points[-1])
    return [[round(lat, 6), round(lon, 6)] for lat, lon in kept]


def describe(route, role, source):
    shape = [p for leg in route["route"]["trip"]["legs"] for p in decode(leg["shape"])]
    return {
        "source": source, "role": role,
        "km": round(route["distance_m"] / 1000, 1),
        "minutes": round(route["timing"]["driving_seconds"] / 60),
        "shape": simplify(shape),
        # 道のどこからどこまでが、どの景色か（メートル）。地図からの推定
        "timeline": [[round(t["start_m"]), round(t["end_m"]), t["category"]] for t in route["timeline"]],
        "reasons": [r["text"] for r in route["reasons"]],
    }


def google_route(start, end):
    """Google の Routes API の経路。キーが無い・使えないときは None。"""
    key = os.environ.get("GOOGLE_ROUTES_API_KEY")
    if not key:
        return None
    point = lambda p: {"location": {"latLng": {"latitude": p[0], "longitude": p[1]}}}
    request = urllib.request.Request(
        "https://routes.googleapis.com/directions/v2:computeRoutes",
        data=json.dumps({"origin": point(start), "destination": point(end), "travelMode": "DRIVE"}).encode(),
        headers={"X-Goog-Api-Key": key, "Content-Type": "application/json",
                 "X-Goog-FieldMask": "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline"})
    try:
        route = json.load(urllib.request.urlopen(request, timeout=60))["routes"][0]
    except urllib.error.HTTPError as error:
        print(f"  Google の Routes API が使えません（HTTP {error.code}）。旅れこの基準の経路で代用します")
        return None
    return {
        "source": "google", "role": "Google マップの案内",
        "km": round(route["distanceMeters"] / 1000, 1),
        "minutes": round(int(route["duration"].rstrip("s")) / 60),
        "shape": simplify(decode(route["polyline"]["encodedPolyline"], 1e5)),
        "timeline": [], "reasons": [],
    }


def pick_scenic(result, preferred_role=PREFERRED_ROLE):
    """preferred_role の案。無ければ、寄り道のある案のうち点数の高いもの。"""
    roles = result["portfolio_roles"]
    # 基準の道と同じ道を選ばないよう、少しでも寄り道になっている案だけを見る
    detours = [r for r in result["routes"] if r["timing"]["extra_driving_seconds"] > 30]
    if not detours:
        return None
    for route in detours:
        if roles.get(route["route_id"]) == preferred_role:
            return route, preferred_role
    best = max(detours, key=lambda r: r["score"])
    return best, roles.get(best["route_id"], "")


def corridor(shapes):
    """2本の道の両方を覆う、150m おきの点。重なる区間の点は1回だけ。"""
    kept = []
    for shape in shapes:
        for p in thin([tuple(q) for q in shape]):
            if all(distance(p, q) >= 100 for q in kept):
                kept.append(p)
    return kept


def main():
    os.makedirs(COMPARE_OUT, exist_ok=True)
    only = sys.argv[1:]
    for name, (start, end, extra_seconds, *role) in ROUTES.items():
        if only and name not in only:
            continue
        print(name)
        result = recommend(start, end, extra_seconds)
        if result["status"] not in ("complete", "limited") or not result.get("routes"):
            print(f"  推薦が出ませんでした（{result['status']}：{result.get('error')}）。この道は走り比べにしません")
            continue
        picked = pick_scenic(result, *role)
        if not picked:
            print("  寄り道のあるおすすめの道が出ませんでした。この道は走り比べにしません")
            continue
        normal = google_route(start, end) or describe(result["baseline"], "寄り道なしの最短", "valhalla")
        scenic = describe(picked[0], picked[1], "tabireco")
        data = {"fetched": time.strftime("%Y-%m-%d"), "preferences": PREFERENCES, "extraSeconds": extra_seconds,
                "normal": normal, "scenic": scenic}
        with open(os.path.join(COMPARE_OUT, f"{name}.json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        print(f"  ふつうの道（{normal['source']}）{normal['km']}km・{normal['minutes']}分 ／ おすすめの道（{scenic['role']}）{scenic['km']}km・{scenic['minutes']}分")

        points = corridor([normal["shape"], scenic["shape"]])
        elements = {}
        for i in range(0, len(points), CIRCLES_PER_QUERY):
            for e in roads_around(points[i:i + CIRCLES_PER_QUERY]):
                elements[(e["type"], e["id"])] = e
            time.sleep(8)
        roads = compact(list(elements.values()))
        path = os.path.join(ROADS_OUT, f"{name}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(roads, f, ensure_ascii=False, separators=(",", ":"))
        print(f"  道 {len(roads['ways'])} 本、点 {len(roads['nodes'])} 個、{os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    main()
