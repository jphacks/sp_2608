export const sampleTrip = {
  id: "7b1c2b4e-3f7a-4d7e-9e3a-2b1f0c9d8e7f",
  startedAtEpochMillis: Date.UTC(2026, 9, 16, 23, 0, 0), // 2026-10-17 08:00 JST
  arrivedAtEpochMillis: Date.UTC(2026, 9, 17, 3, 30, 0), // 2026-10-17 12:30 JST
  timeZoneId: "Asia/Tokyo",
  distanceMeters: 358_000,
  durationSeconds: 4 * 3600 + 30 * 60,
  destinationTitle: "八雲町役場",
  destinationCoordinate: { latitude: 42.2563, longitude: 140.2651 },
  placeName: "北海道 八雲町",
  geometry: [
    { latitude: 43.0618, longitude: 141.3545 },
    { latitude: 42.9, longitude: 141.0 },
    { latitude: 42.5, longitude: 140.5 },
    { latitude: 42.2563, longitude: 140.2651 },
  ],
  members: ["Honoka", "Kokoro", "Masa"],
  photoStyle: "WATERCOLOR",
  photoLocation: { latitude: 42.5, longitude: 140.5 },
};

const start = sampleTrip.startedAtEpochMillis;
const minute = 60_000;

/** 旅中の音楽。4 曲、ジャンル 3 種（1 曲は不明）。観測は 1 分ごと 270 分ぶんで、峠の曲のあいだに盛り上がる。 */
export const sampleMusic = {
  tracks: [
    { trackId: "t2", title: "朝のコーヒー", artist: "Morning Trio", genre: "Jazz", startedAtEpochMillis: start + 5 * minute, endedAtEpochMillis: start + 9 * minute },
    { trackId: "t1", title: "海沿いの道", artist: "Blue Coast", genre: "J-POP", startedAtEpochMillis: start + 0 * minute, endedAtEpochMillis: start + 5 * minute },
    { trackId: "t3", title: "峠を越えて", artist: "Ridge Riders", genre: "Rock", startedAtEpochMillis: start + 9 * minute, endedAtEpochMillis: null },
    { trackId: "t4", title: "夕暮れドライブ", artist: "Blue Coast", genre: null, startedAtEpochMillis: start + 15 * minute, endedAtEpochMillis: start + 18 * minute },
  ],
  mood: Array.from({ length: 270 }, (_, i) => ({
    atEpochMillis: start + i * minute,
    // 9〜12 分は盛り上がり（0.5 以上）、それ以外は 0.1〜0.3 を往復
    speechRatio: i >= 9 && i < 12 ? 0.5 + (i - 9) * 0.1 : 0.1 + ((i % 5) * 0.05),
  })),
};
