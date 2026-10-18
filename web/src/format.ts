// 表示文字列の整形。ステッカーの文言と揃える。

export type DateParts = { year: string; month: string; day: string; hour: string; minute: string; weekday: string };

export function dateParts(epochMillis: number, timeZoneId: string): DateParts {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: timeZoneId,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    weekday: "short",
  });
  const byType = new Map(fmt.formatToParts(epochMillis).map((p) => [p.type, p.value]));
  const get = (type: Intl.DateTimeFormatPartTypes) => byType.get(type) ?? "";
  return {
    year: get("year"),
    month: get("month"),
    day: get("day"),
    hour: get("hour"),
    minute: get("minute"),
    weekday: get("weekday").toLowerCase(),
  };
}

/** ステッカーの日付欄。例: { year: "2026", date: "10.17 sat." } */
export function formatStickerDate(epochMillis: number, timeZoneId: string): { year: string; date: string } {
  const p = dateParts(epochMillis, timeZoneId);
  return { year: p.year, date: `${p.month}.${p.day} ${p.weekday}.` };
}

/** 時刻 "08:05" */
export function formatClock(epochMillis: number, timeZoneId: string): string {
  const p = dateParts(epochMillis, timeZoneId);
  return `${p.hour}:${p.minute}`;
}

/** 距離。ステッカー（Android の distanceKm）と同じく四捨五入した整数 km。 */
export function formatDistance(meters: number): { value: string; unit: string } {
  return { value: String(Math.round(meters / 1000)), unit: "km" };
}

/** 所要時間。"4 h 30 min" のように h と min の組で返す。1 時間未満は min だけ。 */
export function formatDuration(seconds: number): { hours: number | null; minutes: number } {
  const total = Math.round(seconds / 60);
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  return { hours: hours > 0 ? hours : null, minutes };
}

/** 平均速度 km/h。時間が 0 なら null。 */
export function averageSpeedKmh(meters: number, seconds: number): number | null {
  if (seconds <= 0) return null;
  return Math.round((meters / 1000) / (seconds / 3600));
}

/** 地名は "北海道 八雲町" のようにスペース区切りで来るので "@北海道 八雲町" にする。無ければ到着地の名前。 */
export function formatPlace(placeName: string | null, destinationTitle: string): string {
  return `@${placeName ?? destinationTitle}`;
}

/** 座標の簡易表示 "42.2563, 140.2651" */
export function formatCoordinate(c: { latitude: number; longitude: number }): string {
  return `${c.latitude.toFixed(4)}, ${c.longitude.toFixed(4)}`;
}
