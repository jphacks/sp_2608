import { describe, expect, it } from "vitest";
import { placeLabel, projectRoute, thinPoints, toPathData } from "../src/route-svg";

// 700×650 の箱で余白 12% → 上下左右 78px。内側は x 78..622、y 78..572
const W = 700;
const H = 650;
const PAD = 78;

describe("projectRoute", () => {
  it("点がすべて余白の内側に収まり、縦横比が保たれる", () => {
    const geometry = [
      { latitude: 43.0, longitude: 141.0 },
      { latitude: 42.0, longitude: 141.0 },
      { latitude: 42.0, longitude: 140.0 },
    ];
    const { points } = projectRoute(geometry, W, H);
    for (const p of points) {
      expect(p.x).toBeGreaterThanOrEqual(PAD - 1e-6);
      expect(p.x).toBeLessThanOrEqual(W - PAD + 1e-6);
      expect(p.y).toBeGreaterThanOrEqual(PAD - 1e-6);
      expect(p.y).toBeLessThanOrEqual(H - PAD + 1e-6);
    }
    // 北が上
    expect(points[0].y).toBeLessThan(points[1].y);
    // 緯度 1 度の縦の長さは、経度 1 度の横の長さより長い（cos 42.5° ≒ 0.74）
    const dy = points[1].y - points[0].y;
    const dx = points[1].x - points[2].x;
    expect(dx / dy).toBeCloseTo(Math.cos((42.5 * Math.PI) / 180), 2);
  });

  it("描いた経路は箱の中央に寄り、余白は高さの 12%", () => {
    const geometry = [
      { latitude: 42.0, longitude: 140.0 },
      { latitude: 42.1, longitude: 140.0 },
    ];
    const { points } = projectRoute(geometry, W, H);
    expect(points[0].x).toBeCloseTo(W / 2, 5);
    expect(points[1].x).toBeCloseTo(W / 2, 5);
    expect(points[1].y).toBeCloseTo(PAD, 5);
    expect(points[0].y).toBeCloseTo(H - PAD, 5);
  });

  it("1 点だけ・同じ点だけなら箱の中央に置く", () => {
    const { points, project } = projectRoute([{ latitude: 42, longitude: 140 }], W, H);
    expect(points[0].x).toBeCloseTo(W / 2, 5);
    expect(points[0].y).toBeCloseTo(H / 2, 5);
    expect(project({ latitude: 42, longitude: 140 })).toEqual(points[0]);
    // 経路なし・写真の位置だけでも中央
    const only = projectRoute([], W, H, { latitude: 42, longitude: 140 });
    expect(only.project({ latitude: 42, longitude: 140 })).toEqual({ x: W / 2, y: H / 2 });
  });

  it("空なら空", () => {
    expect(projectRoute([], W, H).points).toEqual([]);
  });

  it("写真の位置も同じ投影で描ける", () => {
    const geometry = [
      { latitude: 43.0, longitude: 141.0 },
      { latitude: 42.0, longitude: 140.0 },
    ];
    const { points, project } = projectRoute(geometry, W, H, geometry[1]);
    expect(project(geometry[1])).toEqual(points[1]);
  });

  it("経路から離れた写真の位置も外接矩形に含めて箱の中に入れる", () => {
    const geometry = [
      { latitude: 43.0, longitude: 141.0 },
      { latitude: 42.5, longitude: 140.5 },
    ];
    const photo = { latitude: 41.0, longitude: 139.0 }; // 経路の南西に大きく外れた地点
    const without = projectRoute(geometry, W, H);
    const outside = without.project(photo);
    expect(outside.x < 0 || outside.y > H).toBe(true);

    const { points, project } = projectRoute(geometry, W, H, photo);
    const spot = project(photo);
    expect(spot.x).toBeGreaterThanOrEqual(PAD - 1e-6);
    expect(spot.x).toBeLessThanOrEqual(W - PAD + 1e-6);
    expect(spot.y).toBeGreaterThanOrEqual(PAD - 1e-6);
    expect(spot.y).toBeLessThanOrEqual(H - PAD + 1e-6);
    // 写真が南西端なので、経路の点はすべて写真より北東にある
    for (const p of points) {
      expect(p.x).toBeGreaterThan(spot.x);
      expect(p.y).toBeLessThan(spot.y);
    }
    // 経路の点数は変わらない（写真の位置は points に混ざらない）
    expect(points).toHaveLength(2);
  });
});

describe("placeLabel", () => {
  const W = 700;
  const H = 650;
  const spot = { x: 350, y: 325 };

  it("経路が無ければ左下に置く", () => {
    expect(placeLabel(spot, [], W, H)).toEqual({ x: 328, y: 339, anchor: "end" });
  });

  it("左下に経路が通っていれば右下に逃げる", () => {
    const route = [{ x: 300, y: 340 }, { x: 290, y: 360 }, { x: 280, y: 380 }];
    expect(placeLabel(spot, route, W, H).anchor).toBe("start");
    expect(placeLabel(spot, route, W, H).y).toBe(339);
  });

  it("左下と右下に経路が通っていれば上に逃げる", () => {
    const route = [{ x: 200, y: 350 }, { x: 500, y: 350 }];
    const placed = placeLabel(spot, route, W, H);
    expect(placed.y).toBeLessThan(spot.y);
  });

  it("頂点が無くても線分の途中が枠にかかれば重なりとみなす", () => {
    // 星の左下を斜めに横切る 1 本の長い線分（頂点は枠の外）
    const route = [{ x: 100, y: 450 }, { x: 350, y: 325 }];
    const placed = placeLabel(spot, route, W, H);
    expect(placed.anchor).toBe("start");
  });

  it("箱の端ではみ出す候補は選ばない", () => {
    const nearLeft = { x: 30, y: 325 };
    expect(placeLabel(nearLeft, [], W, H).anchor).toBe("start");
    const nearBottom = { x: 350, y: 640 };
    expect(placeLabel(nearBottom, [], W, H).y).toBeLessThan(nearBottom.y);
  });
});

describe("toPathData / thinPoints", () => {
  it("M と L で path を作る", () => {
    expect(toPathData([{ x: 1.26, y: 2 }, { x: 3, y: 4.44 }])).toBe("M1.3 2.0 L3.0 4.4");
    expect(toPathData([])).toBe("");
  });

  it("近すぎる点を間引き、始点と終点は残す", () => {
    const pts = [
      { x: 0, y: 0 },
      { x: 0.5, y: 0 },
      { x: 1, y: 0 },
      { x: 5, y: 0 },
      { x: 5.2, y: 0 },
    ];
    expect(thinPoints(pts, 2)).toEqual([{ x: 0, y: 0 }, { x: 5, y: 0 }, { x: 5.2, y: 0 }]);
    expect(thinPoints(pts.slice(0, 2), 2)).toHaveLength(2);
  });
});
