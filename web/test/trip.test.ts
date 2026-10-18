import { describe, expect, it } from "vitest";
import { parseTrip, ValidationError } from "../src/trip";
import { sampleTrip } from "./fixtures";

describe("parseTrip", () => {
  it("ブリーフのキー名の JSON を受け付ける", () => {
    const t = parseTrip(sampleTrip);
    expect(t.id).toBe(sampleTrip.id);
    expect(t.geometry).toHaveLength(4);
    expect(t.photoStyle).toBe("WATERCOLOR");
    expect(t.photoLocation).toEqual({ latitude: 42.5, longitude: 140.5 });
  });

  it("Android の toJson 形式（startedAt / destination / [lat, lon]）も受け付ける", () => {
    const androidShape = {
      id: sampleTrip.id,
      startedAt: sampleTrip.startedAtEpochMillis,
      arrivedAt: sampleTrip.arrivedAtEpochMillis,
      distanceMeters: 1200,
      durationSeconds: 300,
      destinationTitle: "函館駅",
      destination: [41.7737, 140.7262],
      placeName: null,
      geometry: [[41.8, 140.7], [41.7737, 140.7262]],
      members: [],
      photoStyle: null,
      photoLocation: null,
      timeZoneId: "Asia/Tokyo",
    };
    const t = parseTrip(androidShape);
    expect(t.startedAtEpochMillis).toBe(sampleTrip.startedAtEpochMillis);
    expect(t.destinationCoordinate).toEqual({ latitude: 41.7737, longitude: 140.7262 });
    expect(t.geometry[0]).toEqual({ latitude: 41.8, longitude: 140.7 });
    expect(t.placeName).toBeNull();
  });

  it("id は小文字に揃える", () => {
    expect(parseTrip({ ...sampleTrip, id: sampleTrip.id.toUpperCase() }).id).toBe(sampleTrip.id);
  });

  it("知らないタイムゾーンは UTC に倒し、長すぎる名前は 200 文字で切る", () => {
    const t = parseTrip({ ...sampleTrip, timeZoneId: "Mars/Olympus", destinationTitle: "あ".repeat(300), placeName: "い".repeat(300) });
    expect(t.timeZoneId).toBe("UTC");
    expect(t.destinationTitle).toHaveLength(200);
    expect(t.placeName).toHaveLength(200);
  });

  it("省略できる項目は既定値になる", () => {
    const { geometry, members, photoStyle, photoLocation, placeName, timeZoneId, ...min } = sampleTrip;
    const t = parseTrip(min);
    expect(t.geometry).toEqual([]);
    expect(t.members).toEqual([]);
    expect(t.photoStyle).toBeNull();
    expect(t.photoLocation).toBeNull();
    expect(t.placeName).toBeNull();
    expect(t.timeZoneId).toBe("UTC");
  });

  it.each([
    ["id が UUID でない", { ...sampleTrip, id: "abc" }],
    ["到着が出発より前", { ...sampleTrip, arrivedAtEpochMillis: sampleTrip.startedAtEpochMillis - 1 }],
    ["距離が負", { ...sampleTrip, distanceMeters: -1 }],
    ["距離が整数でない", { ...sampleTrip, distanceMeters: 1.5 }],
    ["到着地の名前が空", { ...sampleTrip, destinationTitle: " " }],
    ["緯度が範囲外", { ...sampleTrip, destinationCoordinate: { latitude: 91, longitude: 0 } }],
    ["経路の点が壊れている", { ...sampleTrip, geometry: [{ latitude: "x", longitude: 1 }] }],
    ["photoStyle が未知", { ...sampleTrip, photoStyle: "SEPIA" }],
    ["members に文字列以外", { ...sampleTrip, members: [1] }],
    ["オブジェクトでない", "nope"],
  ])("%s なら ValidationError", (_name, input) => {
    expect(() => parseTrip(input)).toThrow(ValidationError);
  });
});
