import { describe, expect, it } from "vitest";
import { barListHtml, moodChartPoints, moodChartSvg } from "../src/charts";

describe("moodChartPoints", () => {
  it("出発が左端、到着が右端、割合 1 が上、0 が下", () => {
    const pts = moodChartPoints(
      [
        { atEpochMillis: 0, speechRatio: 0 },
        { atEpochMillis: 100, speechRatio: 1 },
      ],
      0,
      100,
    );
    expect(pts[0].x).toBeLessThan(pts[1].x);
    expect(pts[0].y).toBeGreaterThan(pts[1].y);
  });

  it("旅の時間の外の観測は捨てる", () => {
    const pts = moodChartPoints([{ atEpochMillis: -1, speechRatio: 0.5 }, { atEpochMillis: 50, speechRatio: 0.5 }], 0, 100);
    expect(pts).toHaveLength(1);
  });
});

describe("moodChartSvg / barListHtml", () => {
  it("線としきい値と軸ラベルが入る。観測が無ければ線は無い", () => {
    const svg = moodChartSvg([{ atEpochMillis: 10, speechRatio: 0.2 }, { atEpochMillis: 20, speechRatio: 0.6 }], 0, 100, { start: "08:00", end: "12:30" });
    expect(svg).toContain('class="line"');
    expect(svg).toContain(">40%<");
    expect(svg).toContain('class="lively"');
    expect(svg).toContain("08:00");
    expect(svg).toContain("12:30");
    expect(moodChartSvg([], 0, 100, { start: "a", end: "b" })).not.toContain('class="line"');
  });

  it("棒はラベルをエスケープし、幅は 0〜100% に収める", () => {
    const html = barListHtml([{ label: "<b>", value: "3 min", share: 1.5 }]);
    expect(html).toContain("&lt;b&gt;");
    expect(html).toContain("width:100.0%");
  });
});
