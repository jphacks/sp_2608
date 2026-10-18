import { describe, expect, it } from "vitest";
import { genreShares, livelyRanking, musicInPeriod, parseTripMusic, trackEnd } from "../src/music";
import { ValidationError } from "../src/trip";
import { sampleMusic, sampleTrip } from "./fixtures";

const arrived = sampleTrip.arrivedAtEpochMillis;

describe("musicInPeriod", () => {
  const track = (id: string, start: number, end: number | null) => ({
    trackId: id, title: id, artist: "Artist", genre: null, startedAtEpochMillis: start, endedAtEpochMillis: end,
  });

  it("期間外を除き、境界に重なる曲は再生時間を切り詰める", () => {
    const source = { tracks: [track("before", 0, 100), track("enter", 50, 150), track("leave", 150, 250), track("after", 200, 300)],
      mood: [99, 100, 150, 200, 201].map((atEpochMillis) => ({ atEpochMillis, speechRatio: .5 })) };
    const snapshot = structuredClone(source);
    const result = musicInPeriod(source, 100, 200);
    expect(result.tracks.map((t) => [t.trackId, t.startedAtEpochMillis, t.endedAtEpochMillis])).toEqual([
      ["enter", 100, 150], ["leave", 150, 200],
    ]);
    expect(result.mood.map((m) => m.atEpochMillis)).toEqual([100, 150]);
    expect(source).toEqual(snapshot);
  });

  it("終了不明の曲は時刻順の次曲か期間末までとし、期間外の次曲も考慮する", () => {
    const result = musicInPeriod({ tracks: [track("next", 250, null), track("first", 50, null), track("middle", 150, null)], mood: [] }, 100, 200);
    expect(result.tracks.map((t) => [t.trackId, t.startedAtEpochMillis, t.endedAtEpochMillis])).toEqual([
      ["first", 100, 150], ["middle", 150, 200],
    ]);
    expect(musicInPeriod({ tracks: [track("last", 50, null)], mood: [] }, 100, 200).tracks[0].endedAtEpochMillis).toBe(200);
    expect(musicInPeriod({ tracks: [track("last", 50, null)], mood: [] }, 100, 200, 150).tracks[0].endedAtEpochMillis).toBe(150);
    expect(musicInPeriod({ tracks: [track("first", 50, null), track("next", 150, 180)], mood: [] }, 100, 200, 120).tracks)
      .toEqual([track("first", 100, 120)]);
  });

  it("長さ0の旅・再生区間は残さない", () => {
    const source = { tracks: [track("zero", 150, 150)], mood: [{ atEpochMillis: 150, speechRatio: .5 }] };
    expect(musicInPeriod(source, 100, 200).tracks).toEqual([]);
    expect(musicInPeriod(source, 150, 150)).toEqual({ tracks: [], mood: [] });
  });
});

describe("parseTripMusic", () => {
  it("曲と観測を時刻順に並べて受け付ける", () => {
    const m = parseTripMusic(sampleMusic)!;
    expect(m.tracks.map((t) => t.title)).toEqual(["海沿いの道", "朝のコーヒー", "峠を越えて", "夕暮れドライブ"]);
    expect(m.mood[0].atEpochMillis).toBeLessThan(m.mood[1].atEpochMillis);
  });

  it("無ければ null", () => {
    expect(parseTripMusic(undefined)).toBeNull();
    expect(parseTripMusic(null)).toBeNull();
  });

  it.each([
    ["オブジェクトでない", "x"],
    ["曲の題名が空", { tracks: [{ ...sampleMusic.tracks[0], title: "" }] }],
    ["終わりが始まりより前", { tracks: [{ ...sampleMusic.tracks[0], endedAtEpochMillis: sampleMusic.tracks[0].startedAtEpochMillis - 1 }] }],
    ["発話割合が範囲外", { mood: [{ atEpochMillis: 1, speechRatio: 1.5 }] }],
  ])("%s なら ValidationError", (_n, input) => {
    expect(() => parseTripMusic(input)).toThrow(ValidationError);
  });
});

describe("集計", () => {
  const m = parseTripMusic(sampleMusic)!;

  it("曲の終わりは、無ければ次の曲の始まり、最後は到着時刻", () => {
    expect(trackEnd(m.tracks, 0, arrived)).toBe(m.tracks[0].endedAtEpochMillis);
    expect(trackEnd(m.tracks, 2, arrived)).toBe(m.tracks[3].startedAtEpochMillis);
    expect(trackEnd(m.tracks, 3, arrived)).toBe(m.tracks[3].endedAtEpochMillis);
    expect(trackEnd([{ ...m.tracks[3], endedAtEpochMillis: null }], 0, arrived)).toBe(arrived);
  });

  it("ジャンルは再生時間の長い順で、割合の合計は 1", () => {
    const g = genreShares(m, arrived);
    expect(g[0].genre).toBe("Rock");
    expect(g[0].minutes).toBe(6);
    expect(g.map((x) => x.genre)).toContain("不明");
    expect(g.reduce((a, x) => a + x.share, 0)).toBeCloseTo(1, 6);
    for (let i = 1; i < g.length; i++) expect(g[i - 1].minutes).toBeGreaterThanOrEqual(g[i].minutes);
  });

  it("盛り上がりの順位は lively の分数の多い順。盛り上がりの無い曲は入らない", () => {
    const r = livelyRanking(m, arrived);
    expect(r[0].track.title).toBe("峠を越えて");
    expect(r[0].livelyMinutes).toBe(3);
    expect(r.map((x) => x.track.title)).not.toContain("朝のコーヒー");
    for (let i = 1; i < r.length; i++) expect(r[i - 1].livelyMinutes).toBeGreaterThanOrEqual(r[i].livelyMinutes);
  });

  it("観測が無ければ順位は空", () => {
    expect(livelyRanking({ tracks: m.tracks, mood: [] }, arrived)).toEqual([]);
  });
});
