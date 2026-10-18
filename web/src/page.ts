// 公開ページ（GET /t/{id}）の HTML をサーバ側で組み立てる。

import type { StoredTrip } from "./trip";
import {
  averageSpeedKmh,
  dateParts,
  formatClock,
  formatCoordinate,
  formatDistance,
  formatDuration,
  formatPlace,
  formatStickerDate,
} from "./format";
import { placeLabel, projectRoute, thinPoints, toPathData } from "./route-svg";
import { escapeHtml } from "./html";
import { barListHtml, moodChartPoints, moodChartSvg } from "./charts";
import { genreShares, livelyRanking, LIVELY_RATIO, type TripMusic } from "./music";

const MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
const MAPLIBRE_VERSION = "5.6.0";

// ステッカー上部の描画領域。見本画像（410×580、上 65%）に合わせた比率。
const ART_W = 700;
const ART_H = 650;

export { escapeHtml };

/** <script> 内に JSON を埋め込むときに </script> で抜けられないようにする。 */
function jsonForScript(v: unknown): string {
  return JSON.stringify(v).replaceAll("<", "\\u003c").replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029");
}

export function routeArtSvg(trip: StoredTrip): string {
  const { points, project } = projectRoute(trip.geometry, ART_W, ART_H, trip.photoLocation);
  const thinned = thinPoints(points, 2);
  const d = toPathData(thinned);
  let spot = "";
  if (trip.photoLocation) {
    const p = project(trip.photoLocation);
    const x = p.x.toFixed(1);
    const y = p.y.toFixed(1);
    // 経路線と重ならない向きにラベルを置く（間引く前の点で判定）
    const label = placeLabel(p, points, ART_W, ART_H);
    const lx = label.x.toFixed(1);
    // 星の塗りは写真の最多色。写真を読んだあとブラウザ側で fill を差し替える（写真が無ければ白のまま）
    spot =
      `<g class="spot" transform="translate(${x} ${y})"><path id="spot-star" d="M0 -14 L4 -4 L15 -4 L6 3 L9 14 L0 7 L-9 14 L-6 3 L-15 -4 L-4 -4 Z"/></g>` +
      `<text class="spot-label" x="${lx}" y="${label.y.toFixed(1)}" text-anchor="${label.anchor}">` +
      `<tspan x="${lx}">PHOTO</tspan><tspan x="${lx}" dy="24">SPOT</tspan></text>`;
  }
  return (
    `<svg class="route-art" viewBox="0 0 ${ART_W} ${ART_H}" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">` +
    (d ? `<path class="route-shadow" d="${d}"/><path class="route-line" d="${d}"/>` : "") +
    spot +
    `</svg>`
  );
}

function routeGeoJson(trip: StoredTrip) {
  return {
    type: "Feature",
    properties: {},
    geometry: { type: "LineString", coordinates: trip.geometry.map((c) => [c.longitude, c.latitude]) },
  };
}

export function musicSectionsHtml(music: TripMusic | null, trip: StoredTrip): string {
  if (!music || (music.tracks.length === 0 && music.mood.length === 0)) return "";
  const tz = trip.timeZoneId;
  let out = "";

  if (music.tracks.length > 0) {
    const genres = genreShares(music, trip.arrivedAtEpochMillis);
    out += `<section>
  <h2>Genres</h2>
  <p class="lead">旅のあいだに流れた曲のジャンルと再生時間</p>
  ${barListHtml(genres.map((g) => ({ label: g.genre, value: `${g.minutes} min`, share: g.share })))}
</section>`;
  }

  if (music.tracks.length > 0 && music.mood.length > 0) {
    const ranking = livelyRanking(music, trip.arrivedAtEpochMillis);
    const items = ranking.length
      ? ranking
          .map(
            (r, i) =>
              `<li><span class="rank">${i + 1}</span><span class="song"><span class="song-title">${escapeHtml(r.track.title)}</span>` +
              `<span class="song-artist">${escapeHtml(r.track.artist)}</span></span><span class="song-minutes"><span class="en">${r.livelyMinutes}</span> min</span></li>`,
          )
          .join("")
      : "";
    out += `<section>
  <h2>Lively Tracks</h2>
  <p class="lead">車内が盛り上がっていた時間に流れていた曲。盛り上がっていた分数の多い順</p>
  ${items ? `<ol class="ranking">${items}</ol>` : `<p class="muted">盛り上がりが ${Math.round(LIVELY_RATIO * 100)}% を超えた時間はありませんでした。</p>`}
</section>`;
  }

  if (music.mood.length > 0) {
    const rows = music.mood
      .map((m) => `<tr><td class="en">${formatClock(m.atEpochMillis, tz)}</td><td class="en">${Math.round(m.speechRatio * 100)}%</td></tr>`)
      .join("");
    out += `<section>
  <h2>Mood</h2>
  <p class="lead">車内で誰かが話していた時間の割合（1 分ごと）。線が ${Math.round(LIVELY_RATIO * 100)}% を超えると「盛り上がり」</p>
  <div class="chart-wrap" id="mood-wrap">
    ${moodChartSvg(music.mood, trip.startedAtEpochMillis, trip.arrivedAtEpochMillis, {
      start: formatClock(trip.startedAtEpochMillis, tz),
      end: formatClock(trip.arrivedAtEpochMillis, tz),
    })}
    <div class="tooltip" id="mood-tip" hidden></div>
  </div>
  <details class="table-view"><summary>表で見る</summary><table><thead><tr><th>時刻</th><th>発話の割合</th></tr></thead><tbody>${rows}</tbody></table></details>
</section>`;
  }
  return out;
}

export function renderTripPage(trip: StoredTrip, photoUrl: string | null): string {
  const date = formatStickerDate(trip.arrivedAtEpochMillis, trip.timeZoneId);
  const distance = formatDistance(trip.distanceMeters);
  const duration = formatDuration(trip.durationSeconds);
  const speed = averageSpeedKmh(trip.distanceMeters, trip.durationSeconds);
  const place = formatPlace(trip.placeName, trip.destinationTitle);
  const start = trip.geometry[0] ?? null;
  const arrived = dateParts(trip.arrivedAtEpochMillis, trip.timeZoneId);
  const title = `${trip.placeName ?? trip.destinationTitle} ${arrived.year}.${arrived.month}.${arrived.day}`;

  const mapData = {
    route: routeGeoJson(trip),
    start: start ? [start.longitude, start.latitude] : null,
    goal: [trip.destinationCoordinate.longitude, trip.destinationCoordinate.latitude],
    photo: trip.photoLocation ? [trip.photoLocation.longitude, trip.photoLocation.latitude] : null,
  };

  const membersHtml = trip.members.length
    ? trip.members.map((m) => `<li>${escapeHtml(m)}</li>`).join("")
    : `<li class="muted">—</li>`;

  const artBackground = photoUrl
    ? `<img class="art-photo" src="${escapeHtml(photoUrl)}" alt="">`
    : `<div class="art-photo art-placeholder"></div>`;

  const timeHtml =
    duration.hours === null
      ? `<span class="num">${duration.minutes}</span><span class="unit">min</span>`
      : `<span class="num">${duration.hours}</span><span class="unit">h</span><span class="num">${duration.minutes}</span><span class="unit">min</span>`;

  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<meta name="robots" content="noindex">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Jost:wght@400;500;700&family=Noto+Sans+JP:wght@400;500;700&display=swap">
<link rel="stylesheet" href="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.css">
<style>
:root{
  --sky:#75B1A9;--sunglow:#D9B44A;--sunset:#EB6B40;--mountains:#4F6457;--mist:#ACD0C0;
  --paper:#F5F3EC;--ink:#1E2A24;--muted:#5C6B63;
  --jp:"Noto Sans JP",system-ui,sans-serif;--en:"Jost","Noto Sans JP",system-ui,sans-serif;
}
*{box-sizing:border-box}
html{background:var(--paper)}
body{margin:0;color:var(--ink);font-family:var(--jp);background:var(--paper);-webkit-font-smoothing:antialiased}
main{max-width:480px;margin:0 auto;padding:24px 16px 48px}
.brand{font-family:var(--en);font-weight:500;letter-spacing:.08em;font-size:13px;color:var(--muted);margin:0 0 16px}
.sticker{aspect-ratio:7/10;background:#fff;border-radius:6px;box-shadow:0 8px 28px rgba(30,42,36,.14);overflow:hidden;display:flex;flex-direction:column}
.art{position:relative;flex:0 0 65%;background:var(--mist);overflow:hidden}
.art-photo{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;display:block}
.art-placeholder{background:linear-gradient(180deg,var(--sunglow) 0%,var(--sunset) 45%,var(--sky) 46%,var(--mountains) 100%)}
.route-art{position:absolute;inset:0;width:100%;height:100%}
.route-shadow{fill:none;stroke:rgba(30,42,36,.18);stroke-width:18;stroke-linecap:round;stroke-linejoin:round;transform:translate(2px,3px)}
.route-line{fill:none;stroke:#fff;stroke-width:14;stroke-linecap:round;stroke-linejoin:round}
.spot path{fill:#fff;stroke:#fff;stroke-width:3;stroke-linejoin:round;paint-order:stroke}
.spot-label{fill:#fff;font-family:var(--en);font-weight:700;font-size:24px;letter-spacing:.04em}
.meta{flex:1 1 35%;padding:5% 5% 4%;display:grid;grid-template-columns:1fr auto;grid-template-rows:auto auto 1fr;gap:0 16px;font-family:var(--en)}
.date{grid-row:1;grid-column:1;font-weight:500;font-size:clamp(14px,4.2vw,20px);line-height:1.2;border-bottom:1.5px solid var(--ink);padding-bottom:6px;align-self:start}
.date span{display:block}
.place{grid-row:2;grid-column:1;font-family:var(--jp);font-weight:500;font-size:clamp(13px,3.8vw,18px);margin-top:8px}
.members{grid-row:1/4;grid-column:2;text-align:right;font-size:clamp(11px,3.1vw,15px);line-height:1.25}
.members h2{font-size:clamp(11px,3.2vw,15px);font-weight:700;letter-spacing:.04em;margin:0 0 6px}
.members ul{list-style:none;margin:0;padding:0}
.stats{grid-row:3;grid-column:1;display:flex;gap:9%;align-items:end}
.stat .label{font-size:clamp(10px,2.9vw,13px);letter-spacing:.06em;font-weight:500;margin-bottom:2px}
.stat .num{font-weight:700;font-size:clamp(26px,8.5vw,42px);line-height:1}
.stat .unit{font-weight:700;font-size:clamp(11px,3.4vw,16px);margin:0 6px 0 3px}
.muted{color:var(--muted)}
section{margin-top:28px}
section h2{font-family:var(--en);font-size:13px;font-weight:500;letter-spacing:.1em;color:var(--muted);margin:0 0 10px;text-transform:uppercase}
dl{display:grid;grid-template-columns:auto 1fr;gap:10px 16px;margin:0;font-size:15px}
dt{color:var(--muted);font-weight:400}
dd{margin:0;font-weight:500;word-break:break-all}
dd .en{font-family:var(--en);font-weight:500}
#map{height:320px;border-radius:6px;overflow:hidden;border:1px solid rgba(30,42,36,.12);background:var(--mist)}
.legend{display:flex;gap:14px;margin:10px 0 0;padding:0;list-style:none;font-size:13px;color:var(--muted)}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.legend .start i{background:var(--sky)}
.legend .goal i{background:var(--sunset)}
.legend .photo i{background:var(--sunglow)}
.lead{font-size:13px;color:var(--muted);margin:0 0 12px;line-height:1.6}
.bars{list-style:none;margin:0;padding:0;display:grid;gap:8px}
.bars li{display:grid;grid-template-columns:minmax(72px,auto) 1fr auto;align-items:center;gap:10px;font-size:14px}
.bar-label{font-weight:500}
.bar-track{height:10px;background:rgba(30,42,36,.08);border-radius:4px;overflow:hidden}
.bar-fill{display:block;height:100%;background:var(--sunset);border-radius:4px}
.bar-value{font-family:var(--en);font-weight:500;color:var(--muted);white-space:nowrap}
.ranking{list-style:none;margin:0;padding:0;display:grid;gap:10px}
.ranking li{display:grid;grid-template-columns:28px 1fr auto;align-items:center;gap:10px}
.rank{font-family:var(--en);font-weight:700;font-size:20px;color:var(--sunset)}
.song{display:flex;flex-direction:column;min-width:0}
.song-title{font-weight:500;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.song-artist{font-size:12px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.song-minutes{font-size:13px;color:var(--muted);white-space:nowrap}
.song-minutes .en{font-size:16px;color:var(--ink)}
.chart-wrap{position:relative}
.mood-chart{width:100%;height:auto;display:block;touch-action:pan-y}
.mood-chart .grid{stroke:rgba(30,42,36,.1);stroke-width:1}
.mood-chart .tick{font-family:var(--en);font-size:11px;fill:var(--muted)}
.mood-chart .lively{stroke:var(--muted);stroke-width:1;stroke-dasharray:4 4}
.mood-chart .lively-label{font-family:var(--jp);font-size:11px}
.mood-chart .area{fill:var(--sunset);opacity:.12}
.mood-chart .line{fill:none;stroke:var(--sunset);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}
.mood-chart .cursor{stroke:var(--ink);stroke-width:1;opacity:.4}
.mood-chart .dot{fill:var(--sunset);stroke:var(--paper);stroke-width:2}
.tooltip{position:absolute;top:8px;left:8px;background:#fff;border:1px solid rgba(30,42,36,.12);border-radius:6px;padding:6px 10px;font-size:12px;font-family:var(--en);color:var(--ink);pointer-events:none;box-shadow:0 2px 8px rgba(30,42,36,.1)}
.table-view{margin-top:10px;font-size:13px}
.table-view summary{color:var(--muted);cursor:pointer}
.table-view table{border-collapse:collapse;margin-top:8px;width:100%;max-height:240px}
.table-view th,.table-view td{text-align:left;padding:4px 8px;border-bottom:1px solid rgba(30,42,36,.08)}
.table-view th{color:var(--muted);font-weight:500}
footer{margin-top:40px;font-size:12px;color:var(--muted)}
footer a{color:inherit}
</style>
</head>
<body>
<main>
<p class="brand">TABIRECO</p>

<article class="sticker" aria-label="旅のステッカー">
  <div class="art">
    ${artBackground}
    ${routeArtSvg(trip)}
  </div>
  <div class="meta">
    <div class="date"><span>${escapeHtml(date.year)}</span><span>${escapeHtml(date.date)}</span></div>
    <div class="place">${escapeHtml(place)}</div>
    <div class="members"><h2>MEMBERS</h2><ul>${membersHtml}</ul></div>
    <div class="stats">
      <div class="stat"><div class="label">DISTANCE</div><span class="num">${escapeHtml(distance.value)}</span><span class="unit">${distance.unit}</span></div>
      <div class="stat"><div class="label">TIME</div>${timeHtml}</div>
    </div>
  </div>
</article>

<section>
  <h2>Trip</h2>
  <dl>
    <dt>出発</dt><dd><span class="en">${formatClock(trip.startedAtEpochMillis, trip.timeZoneId)}</span></dd>
    <dt>到着</dt><dd><span class="en">${formatClock(trip.arrivedAtEpochMillis, trip.timeZoneId)}</span></dd>
    <dt>平均速度</dt><dd>${speed === null ? "—" : `<span class="en">${speed}</span> km/h`}</dd>
    <dt>出発地</dt><dd>${start ? `<span class="en">${formatCoordinate(start)}</span>` : "—"}</dd>
    <dt>到着地</dt><dd>${escapeHtml(trip.destinationTitle)}${trip.placeName ? `<br><span class="muted">${escapeHtml(trip.placeName)}</span>` : ""}</dd>
    ${trip.photoLocation ? `<dt>写真の撮影地点</dt><dd><span class="en">${formatCoordinate(trip.photoLocation)}</span></dd>` : ""}
  </dl>
</section>

${musicSectionsHtml(trip.music, trip)}

<section>
  <h2>Route</h2>
  <div id="map" role="img" aria-label="経路の地図"></div>
  <ul class="legend">
    <li class="start"><i></i>出発</li>
    <li class="goal"><i></i>到着</li>
    ${trip.photoLocation ? `<li class="photo"><i></i>写真</li>` : ""}
  </ul>
</section>

<footer>
  地図: <a href="https://openfreemap.org" rel="noopener">OpenFreeMap</a> / <a href="https://www.openstreetmap.org/copyright" rel="noopener">OpenStreetMap</a> contributors
</footer>
</main>

<script>
(function(){
  // 星の塗りを写真で一番多く使われている色にする。色を 32 段階に丸めて数え、最多の区分の平均色を使う。
  var img = document.querySelector(".art-photo");
  var star = document.getElementById("spot-star");
  if (!img || !star || img.tagName !== "IMG") return;
  function apply() {
    try {
      var w = 64, h = Math.max(1, Math.round(64 * img.naturalHeight / img.naturalWidth));
      var canvas = document.createElement("canvas");
      canvas.width = w; canvas.height = h;
      var ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, w, h);
      var px = ctx.getImageData(0, 0, w, h).data;
      var bins = {};
      for (var i = 0; i < px.length; i += 4) {
        if (px[i + 3] < 128) continue;
        var key = (px[i] >> 5) + "," + (px[i + 1] >> 5) + "," + (px[i + 2] >> 5);
        var b = bins[key] || (bins[key] = [0, 0, 0, 0]);
        b[0] += px[i]; b[1] += px[i + 1]; b[2] += px[i + 2]; b[3]++;
      }
      var best = null;
      for (var k in bins) if (!best || bins[k][3] > best[3]) best = bins[k];
      if (!best) return;
      var r = Math.round(best[0] / best[3]), g = Math.round(best[1] / best[3]), bl = Math.round(best[2] / best[3]);
      star.style.fill = "rgb(" + r + "," + g + "," + bl + ")";
    } catch (e) { /* 読めなければ白のまま */ }
  }
  if (img.complete && img.naturalWidth > 0) apply(); else img.addEventListener("load", apply);
})();
</script>
<script>
(function(){
  // 盛り上がりの折れ線に、指やマウスの位置の時刻と割合を出す
  var wrap = document.getElementById("mood-wrap");
  var svg = wrap && wrap.querySelector("svg");
  var cursor = document.getElementById("mood-cursor");
  var dot = document.getElementById("mood-dot");
  var tip = document.getElementById("mood-tip");
  if (!svg || !cursor || !dot || !tip) return;
  var pts = ${jsonForScript(moodPointsForHover(trip))};
  var vb = svg.viewBox.baseVal;
  function show(clientX) {
    var rect = svg.getBoundingClientRect();
    var x = (clientX - rect.left) / rect.width * vb.width;
    var best = pts[0];
    for (var i = 1; i < pts.length; i++) if (Math.abs(pts[i].x - x) < Math.abs(best.x - x)) best = pts[i];
    cursor.setAttribute("x1", best.x); cursor.setAttribute("x2", best.x); cursor.style.display = "";
    dot.setAttribute("cx", best.x); dot.setAttribute("cy", best.y); dot.style.display = "";
    tip.textContent = best.t + "  " + best.p + "%";
    tip.hidden = false;
    var left = best.x / vb.width * rect.width;
    tip.style.left = Math.min(Math.max(left - tip.offsetWidth / 2, 0), rect.width - tip.offsetWidth) + "px";
  }
  function hide() { cursor.style.display = "none"; dot.style.display = "none"; tip.hidden = true; }
  svg.addEventListener("mousemove", function(e){ show(e.clientX); });
  svg.addEventListener("mouseleave", hide);
  svg.addEventListener("touchstart", function(e){ show(e.touches[0].clientX); }, { passive: true });
  svg.addEventListener("touchmove", function(e){ show(e.touches[0].clientX); }, { passive: true });
  svg.addEventListener("touchend", hide);
})();
</script>
<script src="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.js"></script>
<script>
(function(){
  var data = ${jsonForScript(mapData)};
  if (!window.maplibregl) return;
  var coords = data.route.geometry.coordinates;
  var map = new maplibregl.Map({ container: "map", style: ${JSON.stringify(MAP_STYLE_URL)}, center: data.goal, zoom: 11, attributionControl: false });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.addControl(new maplibregl.AttributionControl({ compact: true }));
  function dot(lngLat, color) {
    var el = document.createElement("div");
    el.style.cssText = "width:14px;height:14px;border-radius:50%;border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35);background:" + color;
    new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map);
  }
  map.on("load", function(){
    if (coords.length > 1) {
      map.addSource("route", { type: "geojson", data: data.route });
      map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#ffffff", "line-width": 8 } });
      map.addLayer({ id: "route-line", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#EB6B40", "line-width": 4 } });
    }
    var all = coords.concat([data.goal], data.start ? [data.start] : [], data.photo ? [data.photo] : []);
    var b = all.reduce(function(acc, c){ return acc.extend(c); }, new maplibregl.LngLatBounds(all[0], all[0]));
    map.fitBounds(b, { padding: 36, duration: 0, maxZoom: 14 });
    if (data.start) dot(data.start, "#75B1A9");
    dot(data.goal, "#EB6B40");
    if (data.photo) dot(data.photo, "#D9B44A");
  });
})();
</script>
</body>
</html>`;
}

function moodPointsForHover(trip: StoredTrip): { x: number; y: number; t: string; p: number }[] {
  if (!trip.music) return [];
  return moodChartPoints(trip.music.mood, trip.startedAtEpochMillis, trip.arrivedAtEpochMillis).map((p) => ({
    x: Math.round(p.x * 10) / 10,
    y: Math.round(p.y * 10) / 10,
    t: formatClock(p.atEpochMillis, trip.timeZoneId),
    p: Math.round(p.ratio * 100),
  }));
}

export function renderNotFoundPage(): string {
  return `<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>見つかりません</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Jost:wght@500&family=Noto+Sans+JP:wght@400;500&display=swap">
<style>html,body{margin:0;background:#F5F3EC;color:#1E2A24;font-family:"Noto Sans JP",system-ui,sans-serif}
main{max-width:480px;margin:0 auto;padding:48px 16px}.brand{font-family:Jost,sans-serif;letter-spacing:.08em;font-size:13px;color:#5C6B63;margin:0 0 24px}
p{font-size:15px;line-height:1.8}</style></head>
<body><main><p class="brand">TABIRECO</p><p>この旅の記録は見つかりませんでした。QR コードを読み直してください。</p></main></body></html>`;
}
