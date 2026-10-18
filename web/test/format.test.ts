import { describe, expect, it } from "vitest";
import {
  averageSpeedKmh,
  formatClock,
  formatDistance,
  formatDuration,
  formatPlace,
  formatStickerDate,
} from "../src/format";
import { sampleTrip } from "./fixtures";

describe("format", () => {
  it("ステッカーの日付は 2026 / 10.17 sat. になる", () => {
    expect(formatStickerDate(sampleTrip.arrivedAtEpochMillis, "Asia/Tokyo")).toEqual({ year: "2026", date: "10.17 sat." });
  });

  it("日付はタイムゾーンで変わる", () => {
    // UTC では 10/17 03:30 → 同日だが、前日夜の出発は UTC では 10/16
    expect(formatStickerDate(sampleTrip.startedAtEpochMillis, "UTC").date).toBe("10.16 fri.");
    expect(formatStickerDate(sampleTrip.startedAtEpochMillis, "Asia/Tokyo").date).toBe("10.17 sat.");
  });

  it("時刻は 24 時間表記", () => {
    expect(formatClock(sampleTrip.startedAtEpochMillis, "Asia/Tokyo")).toBe("08:00");
    expect(formatClock(sampleTrip.arrivedAtEpochMillis, "Asia/Tokyo")).toBe("12:30");
  });

  it("距離はステッカーと同じ整数 km", () => {
    expect(formatDistance(358_000)).toEqual({ value: "358", unit: "km" });
    expect(formatDistance(358_400)).toEqual({ value: "358", unit: "km" });
    expect(formatDistance(4_560)).toEqual({ value: "5", unit: "km" });
    expect(formatDistance(800)).toEqual({ value: "1", unit: "km" });
    expect(formatDistance(400)).toEqual({ value: "0", unit: "km" });
  });

  it("所要時間", () => {
    expect(formatDuration(4 * 3600 + 30 * 60)).toEqual({ hours: 4, minutes: 30 });
    expect(formatDuration(25 * 60 + 40)).toEqual({ hours: null, minutes: 26 });
    expect(formatDuration(0)).toEqual({ hours: null, minutes: 0 });
  });

  it("平均速度", () => {
    expect(averageSpeedKmh(358_000, 4.5 * 3600)).toBe(80);
    expect(averageSpeedKmh(1000, 0)).toBeNull();
  });

  it("地名は @ 付き。無ければ到着地の名前", () => {
    expect(formatPlace("北海道 八雲町", "八雲町役場")).toBe("@北海道 八雲町");
    expect(formatPlace(null, "八雲町役場")).toBe("@八雲町役場");
  });
});
