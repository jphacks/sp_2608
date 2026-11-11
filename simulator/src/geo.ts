export interface LatLon {
  lat: number
  lon: number
}

const EARTH_RADIUS_METERS = 6_371_008.8

const toRadians = (degrees: number) => (degrees * Math.PI) / 180
const toDegrees = (radians: number) => (radians * 180) / Math.PI

/** 北へ north メートル、東へ east メートル進んだ地点。数十メートル刻みで使うので平面とみなす。 */
export function offset(from: LatLon, northMeters: number, eastMeters: number): LatLon {
  const lat = from.lat + toDegrees(northMeters / EARTH_RADIUS_METERS)
  const lon = from.lon + toDegrees(eastMeters / (EARTH_RADIUS_METERS * Math.cos(toRadians(from.lat))))
  return { lat, lon }
}

/** 北を 0 とした時計回りの角度（0〜360）。 */
export function bearingDegrees(from: LatLon, to: LatLon): number {
  const fromLat = toRadians(from.lat)
  const toLat = toRadians(to.lat)
  const deltaLon = toRadians(to.lon - from.lon)
  const y = Math.sin(deltaLon) * Math.cos(toLat)
  const x = Math.cos(fromLat) * Math.sin(toLat) - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLon)
  return (toDegrees(Math.atan2(y, x)) + 360) % 360
}

export function distanceMeters(a: LatLon, b: LatLon): number {
  const deltaLat = toRadians(b.lat - a.lat)
  const deltaLon = toRadians(b.lon - a.lon)
  const h = Math.sin(deltaLat / 2) ** 2 +
    Math.cos(toRadians(a.lat)) * Math.cos(toRadians(b.lat)) * Math.sin(deltaLon / 2) ** 2
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h))
}
