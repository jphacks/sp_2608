// ライブ表示のページ（GET /live/{code}）。サーバーは枠だけを返し、中身はブラウザが /api/live/{code} を
// 1秒ごとに読んで描き替える。曲名などの値は textContent で入れ、HTML として解釈させない。
//
// いちばん上で「① この先の景色 → ② 欲しい曲調 → ③ 選んだ曲」を大きく並べ、選んだ曲の曲調を欲しい曲調に
// 重ねて描く。景色に合わせて曲を選んでいることが、ひと目で分かるようにするため。

const MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
const MAPLIBRE_VERSION = "5.6.0";

/** 景色のカテゴリの呼び名と色。景観エンジンのカテゴリ名に合わせる。 */
export const CATEGORY_STYLE: Record<string, { label: string; color: string }> = {
  sea: { label: "海", color: "#2F86C0" },
  forest: { label: "森", color: "#3F7A47" },
  open_nature: { label: "自然", color: "#8DB35B" },
  farmland: { label: "田園", color: "#CDB24F" },
  townscape: { label: "街並み", color: "#9A8F86" },
  urban: { label: "市街地", color: "#7F8590" },
  inland_water: { label: "水辺", color: "#4FAACB" },
  lake: { label: "湖", color: "#3F9FC7" },
  river: { label: "川", color: "#56B2CF" },
  valley: { label: "渓谷", color: "#5F8A6B" },
  unclassified: { label: "特になし", color: "#BFC4BC" },
  unknown: { label: "未評価", color: "#DDDBD2" },
};

export const AXIS_LABEL: Record<string, { name: string; low: string; high: string }> = {
  energy: { name: "激しさ", low: "静か", high: "激しい" },
  valence: { name: "明るさ", low: "切ない", high: "明るい" },
  tempo: { name: "テンポ", low: "遅い", high: "速い" },
  acoustic: { name: "生音", low: "電子音", high: "生楽器" },
  spacious: { name: "広がり", low: "密", high: "余白" },
  vocal: { name: "歌声", low: "インスト", high: "歌が前" },
};

export const THEME_LABEL: Record<string, string> = {
  sea: "海", summer: "夏", night: "夜", city: "街", drive_travel: "ドライブや旅", nature: "自然", sky_wind: "空や風",
  river_lake: "川や湖", hometown: "故郷や帰り道", morning: "朝", rain: "雨",
};

/** 歌詞の題材を、景色の色で塗る（海の題材は海の色）。景色と結び付かない題材は灰色。 */
export const THEME_COLOR: Record<string, string> = {
  sea: "#2F86C0", summer: "#E39B2F", nature: "#3F7A47", sky_wind: "#5FA8B0", river_lake: "#3F9FC7", city: "#7F8590",
  drive_travel: "#C46A3C", night: "#4B4F7A", morning: "#D9A93A", hometown: "#9A7B5A", rain: "#6C8394",
};

/** 車内の状態の呼び名。resting は「寝ている」とは出さない（bgm-api の取り決め）。 */
export const CABIN_LABEL: Record<string, string> = { lively: "にぎやか", normal: "ふつう", quiet: "静か", resting: "落ち着いた時間" };

/** <script> 内に JSON を埋め込むときに </script> で抜けられないようにする。 */
function jsonForScript(v: unknown): string {
  return JSON.stringify(v).replaceAll("<", "\\u003c").replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029");
}

/** demo のときは車から受け取らず、/live-demo.json（scripts/live-replay.ts --record で作る）をブラウザで再生する。 */
export function renderLivePage(code: string, options: { demo?: boolean } = {}): string {
  const demo = options.demo === true;
  const config = {
    code,
    demo,
    styleUrl: MAP_STYLE_URL,
    categories: CATEGORY_STYLE,
    axes: AXIS_LABEL,
    themes: THEME_LABEL,
    themeColors: THEME_COLOR,
    cabin: CABIN_LABEL,
  };
  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>旅れこ ライブ</title>
<meta name="robots" content="noindex">
<meta name="referrer" content="no-referrer">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Jost:wght@400;500;700&family=Noto+Sans+JP:wght@400;500;700;900&display=swap">
<link rel="stylesheet" href="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.css">
<style>
:root{
  --sky:#75B1A9;--sunglow:#D9B44A;--sunset:#EB6B40;--mountains:#4F6457;--mist:#ACD0C0;
  --paper:#F3F1EA;--card:#FFFFFF;--ink:#1E2A24;--muted:#5C6B63;--line:rgba(30,42,36,.12);--soft:rgba(30,42,36,.06);
  --scene:#2F86C0;
  --jp:"Noto Sans JP",system-ui,sans-serif;--en:"Jost","Noto Sans JP",system-ui,sans-serif;
}
*{box-sizing:border-box}
html{background:var(--paper)}
body{margin:0;color:var(--ink);font-family:var(--jp);background:var(--paper);-webkit-font-smoothing:antialiased}
main{max-width:1280px;margin:0 auto;padding:20px 16px 56px}
header{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 14px;margin-bottom:16px}
.brand{font-family:var(--en);font-weight:500;letter-spacing:.08em;font-size:13px;color:var(--muted);margin:0}
h1{font-size:22px;margin:0;font-weight:700}
.status{margin-left:auto;font-size:13px;color:var(--muted);display:flex;align-items:center;gap:6px}
.status i{width:8px;height:8px;border-radius:50%;background:#B9BDB7;display:inline-block}
.status.on i{background:#3BA55C;animation:pulse 1.6s infinite}
.status.stale i{background:var(--sunglow)}
@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(59,165,92,.45)}70%{box-shadow:0 0 0 8px rgba(59,165,92,0)}100%{box-shadow:0 0 0 0 rgba(59,165,92,0)}}
.card{background:var(--card);border-radius:10px;box-shadow:0 1px 2px rgba(30,42,36,.06),0 8px 24px rgba(30,42,36,.07);padding:18px;min-width:0}
.card h2{font-family:var(--en);font-size:12px;font-weight:500;letter-spacing:.12em;color:var(--muted);margin:0 0 4px;text-transform:uppercase}
.card .lead{font-size:13px;color:var(--muted);margin:0 0 12px;line-height:1.6}
.en{font-family:var(--en)}
.muted{color:var(--muted)}
.empty{font-size:14px;color:var(--muted);padding:8px 0}

/* ① → ② → ③ */
.hero{display:grid;gap:14px;grid-template-columns:minmax(0,1fr);margin-bottom:16px}
@media (min-width:1000px){.hero{grid-template-columns:minmax(0,1fr) 36px minmax(0,1.05fr) 36px minmax(0,1.15fr);align-items:stretch}}
.step{position:relative;border-radius:14px;padding:20px;background:var(--card);box-shadow:0 1px 2px rgba(30,42,36,.06),0 10px 28px rgba(30,42,36,.08);min-width:0}
.step-head{display:flex;align-items:center;gap:10px;margin-bottom:14px}
.step-n{width:30px;height:30px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-family:var(--en);font-weight:700;color:#fff;background:var(--ink);flex:none}
.step-head h3{margin:0;font-size:19px;font-weight:700}
.step-head .sub{margin-left:auto;font-size:13px;color:var(--muted);white-space:nowrap}
.flow{display:flex;align-items:center;justify-content:center;color:var(--muted);font-size:28px;font-weight:700}
.flow::before{content:"↓"}
@media (min-width:1000px){.flow::before{content:"→"}}
.scene-step{background:linear-gradient(160deg,color-mix(in srgb,var(--scene) 16%,#fff) 0%,#fff 70%)}
.scene-step .step-n{background:var(--scene)}
.scene-big{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;margin:2px 0 12px}
.scene-big b{font-size:44px;line-height:1;font-weight:900;color:var(--scene)}
.scene-big span{font-size:16px;color:var(--muted)}
.scene-rows{display:grid;gap:8px}
.scene-row{display:grid;grid-template-columns:70px 1fr 48px;gap:10px;align-items:center;font-size:15px}
.scene-row .bar{height:14px;border-radius:7px;background:var(--soft);overflow:hidden}
.scene-row .bar i{display:block;height:100%;border-radius:7px;transition:width .8s ease}
.scene-row .pct{font-family:var(--en);font-weight:700;text-align:right}
.because{margin-top:16px;font-size:14px;color:var(--muted)}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
.chip{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:5px 12px;font-size:14px;font-weight:500;background:var(--soft)}
.chip.strong{background:var(--ink);color:#fff}
.chip.theme{color:#fff}
.mood-step .step-n{background:var(--sunset)}
.radar{width:100%;max-width:340px;display:block;margin:0 auto}
.radar .ring{fill:none;stroke:rgba(30,42,36,.1)}
.radar .spoke{stroke:rgba(30,42,36,.1)}
.radar .target{fill:rgba(235,107,64,.22);stroke:var(--sunset);stroke-width:2.5;stroke-linejoin:round;transition:all .6s}
.radar .song{fill:none;stroke:var(--ink);stroke-width:2;stroke-dasharray:5 4;stroke-linejoin:round}
.radar .lbl{font-size:13px;fill:var(--ink);font-weight:700}
.radar .val{font-size:12px;fill:var(--muted);font-family:var(--en)}
.radar-legend{display:flex;justify-content:center;gap:16px;font-size:13px;color:var(--muted);margin-top:6px;flex-wrap:wrap}
.radar-legend i{display:inline-block;width:16px;height:0;border-top:3px solid var(--sunset);margin-right:6px;vertical-align:4px}
.radar-legend i.song{border-top:2px dashed var(--ink)}
.pick-step{background:linear-gradient(160deg,#FFF1EA 0%,#fff 65%)}
.pick-step .step-n{background:var(--ink)}
.pick-badge{display:inline-block;font-size:13px;font-weight:700;border-radius:999px;padding:4px 12px;background:var(--mountains);color:#fff}
.pick-badge.decided{background:var(--sunset)}
.pick-title{font-size:32px;font-weight:900;line-height:1.25;margin:12px 0 2px;word-break:break-word;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.pick-artist{font-size:16px;color:var(--muted)}
.pick-msg{margin-top:16px;padding:14px 16px;border-radius:10px;background:var(--card);border-left:5px solid var(--scene);font-size:17px;font-weight:700;line-height:1.6}
.pick-why{display:grid;gap:10px;margin-top:16px}
.why-row{display:grid;grid-template-columns:96px 1fr auto;gap:10px;align-items:center;font-size:14px}
.why-row .bar{height:10px;border-radius:5px;background:var(--soft);overflow:hidden}
.why-row .bar i{display:block;height:100%;border-radius:5px;background:var(--sunset);transition:width .8s ease}
.why-row b{font-family:var(--en);font-size:16px}
.pick-foot{margin-top:14px;font-size:13px;color:var(--muted)}

/* 景色と流した曲 */
.journey{margin-bottom:16px}
.journey-track{position:relative;margin:30px 0 8px}
.journey-songs{position:relative;height:34px}
.journey-songs .pin{position:absolute;bottom:0;transform:translateX(-50%);display:flex;flex-direction:column;align-items:center}
.journey-songs .pin span{width:26px;height:26px;border-radius:50%;background:var(--ink);color:#fff;font-family:var(--en);font-weight:700;font-size:13px;display:flex;align-items:center;justify-content:center}
.journey-songs .pin::after{content:"";width:2px;height:8px;background:var(--ink)}
.journey-band{position:relative;height:30px;border-radius:6px;overflow:hidden;background:var(--soft)}
.journey-band .seg{position:absolute;top:0;bottom:0}
.journey-band .done{position:absolute;top:0;bottom:0;left:0;background:rgba(255,255,255,.45);border-right:3px solid var(--sunset)}
.journey-band .win{position:absolute;top:0;bottom:0;border:3px solid var(--ink);border-radius:6px}
.journey-ends{display:flex;justify-content:space-between;font-size:12px;color:var(--muted);margin-top:4px}
.journey-list{list-style:none;margin:14px 0 0;padding:0;display:grid;gap:10px}
@media (min-width:900px){.journey-list{grid-template-columns:repeat(2,minmax(0,1fr))}}
.journey-list li{display:grid;grid-template-columns:26px 74px minmax(0,1fr);gap:10px;align-items:start;padding:10px;border-radius:10px;background:#FAF9F5}
.journey-list .no{width:26px;height:26px;border-radius:50%;background:var(--ink);color:#fff;font-family:var(--en);font-weight:700;font-size:13px;display:flex;align-items:center;justify-content:center}
.journey-list .where{border-radius:6px;color:#fff;font-weight:700;font-size:14px;text-align:center;padding:4px 0}
.journey-list .t{font-weight:700;font-size:15px}
.journey-list .a{font-size:13px;color:var(--muted)}
.journey-list .m{font-size:13px;margin-top:4px;line-height:1.5}
.journey-list .now-tag{display:inline-block;font-size:11px;font-weight:700;color:#fff;background:var(--sunset);border-radius:3px;padding:1px 6px;margin-left:6px;vertical-align:2px}

/* 下の段 */
.grid{display:grid;gap:16px;grid-template-columns:minmax(0,1fr)}
@media (min-width:900px){.grid{grid-template-columns:minmax(0,5fr) minmax(0,6fr)}}
.col{display:grid;gap:16px;align-content:start;min-width:0}
#map{height:340px;border-radius:8px;overflow:hidden;background:var(--mist)}
.facts{display:flex;flex-wrap:wrap;gap:6px 18px;margin-top:12px;font-size:14px}
.facts b{font-family:var(--en);font-size:22px;font-weight:700;margin-right:2px}
.cabin{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
.cabin .chip.lively{background:#FCE3D8;color:#9A3A15}.cabin .chip.quiet{background:#E1EEF1;color:#2D5D6B}.cabin .chip.resting{background:#E7E3F1;color:#4B3F78}
.talk{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:var(--muted)}
.talk i{width:9px;height:9px;border-radius:50%;background:#C9CCC4}
.talk.on i{background:var(--sunset);animation:pulse 1s infinite}
.meter{flex:1 1 180px;min-width:150px}
.meter .bar{height:10px;border-radius:5px;background:var(--soft);overflow:hidden;position:relative}
.meter .fill{position:absolute;inset:0 auto 0 0;background:var(--sunset);border-radius:5px;transition:width .6s ease}
.meter .mark{position:absolute;top:-3px;bottom:-3px;width:2px;background:var(--ink);opacity:.35}
.meter .cap{display:flex;justify-content:space-between;gap:8px;font-size:12px;color:var(--muted);margin-top:4px}
.now-title{font-size:19px;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.now-artist{font-size:14px;color:var(--muted)}
.progress{position:relative;height:5px;background:var(--soft);border-radius:3px;margin:14px 0 6px}
.progress .fill{position:absolute;inset:0 auto 0 0;background:var(--ink);border-radius:3px}
.progress .decide{position:absolute;top:-4px;bottom:-4px;width:2px;background:var(--sunset)}
.times{display:flex;justify-content:space-between;font-size:12px;color:var(--muted);font-family:var(--en)}
.countdown{margin-top:10px;font-size:14px}
.countdown b{font-family:var(--en);font-size:18px}
.tag{display:inline-block;font-size:11px;border-radius:3px;padding:1px 6px;background:var(--soft);color:var(--muted);margin-left:6px;vertical-align:3px}
.eval-head{display:flex;flex-wrap:wrap;align-items:center;gap:6px 10px;margin-bottom:8px;font-size:13px}
.badge{font-size:12px;font-weight:700;border-radius:999px;padding:3px 10px;background:var(--soft)}
.badge.decided{background:var(--sunset);color:#fff}
.cands{list-style:none;margin:0;padding:0;display:grid;gap:2px}
.cand{display:grid;grid-template-columns:24px minmax(0,1fr) 84px 66px;gap:4px 10px;align-items:center;padding:8px 6px;border-radius:8px}
.cand.pick{background:#FFF1EA}
.cand .rank{font-family:var(--en);font-weight:700;color:var(--muted);text-align:center}
.cand.pick .rank{color:var(--sunset)}
.cand .t{font-size:15px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cand .a{font-size:12px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cand .spark{width:84px;height:28px}
.cand .spark polyline{fill:none;stroke:var(--sky);stroke-width:1.6;stroke-linejoin:round}
.cand.pick .spark polyline{stroke:var(--sunset)}
.cand .score{text-align:right;font-family:var(--en);font-variant-numeric:tabular-nums;font-size:15px;font-weight:500}
.cand .delta{display:block;font-size:11px;color:var(--muted)}
.cand .delta.up{color:#2F8F4E}.cand .delta.down{color:#B5462A}
.cand .why{grid-column:2/5;display:flex;flex-wrap:wrap;gap:4px 6px;align-items:center}
.cand .why span{font-size:11px;border-radius:3px;padding:1px 6px;background:var(--soft);color:var(--muted)}
.cand .why span.th{color:#fff}
.cand .why span.minus{background:#FBE7E0;color:#9A3A15}
.pick-tag{background:var(--sunset)!important;color:#fff!important}
.formula{font-size:12px;color:var(--muted);margin-top:12px;line-height:1.7}
.ended{font-size:16px;line-height:1.8;padding:20px;margin-bottom:16px}
.ended a{display:inline-block;margin-top:8px;color:var(--sunset);font-weight:700}
.demo{margin-bottom:16px}
.demo p{font-size:15px;line-height:1.8;margin:6px 0 12px}
.demo-actions{display:flex;flex-wrap:wrap;gap:8px}
.demo-actions a{display:inline-flex;align-items:center;min-height:46px;padding:0 20px;border-radius:999px;background:var(--soft);color:var(--ink);font-weight:700;text-decoration:none;font-size:15px}
.demo-actions a.main{background:var(--sunset);color:#fff}
footer{margin-top:28px;font-size:12px;color:var(--muted)}

/* モニターに映しっぱなしにする横長の画面：1920×1080 の1枚に組み、画面の大きさに合わせて拡大・縮小する（スクロールさせない） */
html.fit,html.fit body{height:100%;overflow:hidden}
html.fit main{position:absolute;left:0;top:0;width:1920px;height:1080px;max-width:none;margin:0;padding:22px 30px;transform-origin:0 0;
  display:grid;gap:16px 18px;grid-template-columns:500px minmax(0,1fr) 620px;grid-template-rows:auto 450px minmax(0,1fr) auto auto;
  grid-template-areas:"head head head" "hero hero hero" "map journey cands" "now journey cands" "cabin journey cands"}
html.fit header{grid-area:head;margin:0}
html.fit .hero{grid-area:hero;margin:0;grid-template-columns:minmax(0,1fr) 40px minmax(0,1fr) 40px minmax(0,1.2fr);align-items:stretch;min-height:0}
html.fit .flow::before{content:"→"}
html.fit .step{overflow:hidden;padding:18px 22px}
html.fit .radar{max-width:262px}
html.fit .radar .lbl{font-size:15px}
html.fit .step-head{margin-bottom:10px}
html.fit .step-head h3{font-size:24px}
html.fit .step-head .sub{font-size:15px}
html.fit .step-n{width:34px;height:34px;font-size:18px}
html.fit .scene-big b{font-size:76px}
html.fit .scene-big span{font-size:22px}
html.fit .scene-row{font-size:20px;grid-template-columns:90px 1fr 64px}
html.fit .scene-row .bar{height:18px;border-radius:9px}
html.fit .because{font-size:16px;margin-top:14px}
html.fit .chip{font-size:18px;padding:6px 16px}
html.fit .radar-legend{font-size:15px;margin-top:0}
html.fit .pick-badge{font-size:16px;padding:5px 14px}
html.fit .pick-title{font-size:46px;-webkit-line-clamp:2}
html.fit .pick-artist{font-size:20px}
html.fit .pick-msg{font-size:23px;padding:14px 18px}
html.fit .pick-why{gap:8px;margin-top:12px}
html.fit .why-row{font-size:18px;grid-template-columns:150px 1fr auto}
html.fit .why-row .bar{height:14px;border-radius:7px}
html.fit .why-row b{font-size:22px}
html.fit .pick-foot{display:none}
html.fit .grid,html.fit .col{display:contents}
html.fit .card{margin:0;padding:14px 18px;overflow:hidden}
html.fit .c-journey{grid-area:journey}
html.fit .c-map{grid-area:map;display:flex;flex-direction:column;min-height:0}
html.fit #map{height:auto;flex:1;min-height:0}
html.fit .facts{margin-top:8px}
html.fit .c-now{grid-area:now}
html.fit .c-cabin{grid-area:cabin}
html.fit .c-cabin .lead,html.fit .c-cands .lead,html.fit .formula{display:none}
html.fit .c-cands{grid-area:cands}
html.fit .cands li:nth-child(n+6){display:none}
html.fit .journey-list{grid-template-columns:repeat(2,minmax(0,1fr))}
html.fit .progress{margin:8px 0 4px}
html.fit .c-now h2,html.fit .c-cabin h2{margin-bottom:2px}
html.fit .now-title{font-size:18px}
html.fit .countdown{margin-top:6px}
html.fit footer{display:none}
html.fit #ended{position:absolute;left:50%;top:14px;transform:translateX(-50%);z-index:5;padding:8px 22px;font-size:17px;box-shadow:0 6px 24px rgba(30,42,36,.18)}
html.fit #ended br{display:none}
html.fit #ended a{margin:0 0 0 14px}
html.fit #demo{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:760px;z-index:5;box-shadow:0 20px 60px rgba(30,42,36,.25)}
footer a{color:inherit}
</style>
</head>
<body>
<main>
<header>
  <p class="brand">TABIRECO LIVE</p>
  <h1 id="title">景観BGMの選曲ライブ</h1>
  <div class="status" id="status"><i></i><span>接続しています…</span></div>
</header>

<div id="ended" class="card ended" hidden></div>
${demo ? `<section id="demo" class="card demo">
  <h2>Demo</h2>
  <p>鎌倉から海沿いを通って茅ヶ崎まで、約15kmのデモの走行を再生します。曲の候補と点数は、旅れこの推薦エンジンと全曲カタログで計算したものです（経路・景色の区間・車内の話し声はデモ用に作ったもの）。</p>
  <div class="demo-actions"><a href="/live/demo?speed=1">等倍で開始（約24分）</a><a class="main" href="/live/demo?speed=2">2倍速で開始</a><a href="/live/demo?speed=4">4倍速で開始</a></div>
</section>` : ""}

<div class="hero" id="hero">
  <section class="step scene-step" aria-labelledby="h-scene">
    <div class="step-head"><span class="step-n">1</span><h3 id="h-scene">この先の景色</h3><span class="sub" id="scene-sub"></span></div>
    <div id="scene"><p class="empty">まだ評価していません</p></div>
  </section>
  <div class="flow" aria-hidden="true"></div>
  <section class="step mood-step" aria-labelledby="h-mood">
    <div class="step-head"><span class="step-n">2</span><h3 id="h-mood">欲しい曲調</h3><span class="sub" id="mood-sub"></span></div>
    <div id="mood"><p class="empty">まだ評価していません</p></div>
  </section>
  <div class="flow" aria-hidden="true"></div>
  <section class="step pick-step" aria-labelledby="h-pick">
    <div class="step-head"><span class="step-n">3</span><h3 id="h-pick">選んだ曲</h3></div>
    <div id="pick"><p class="empty">まだ評価していません</p></div>
  </section>
</div>

<section class="card journey c-journey">
  <h2>Scenery &amp; Songs</h2>
  <p class="lead">経路全体の景色と、どこでどの曲を流したか。番号は下の曲の番号です。枠は次の曲が流れる区間です。</p>
  <div class="journey-track">
    <div class="journey-songs" id="journey-songs"></div>
    <div class="journey-band" id="journey-band"></div>
  </div>
  <div class="journey-ends"><span>出発</span><span id="journey-goal">到着</span></div>
  <ol class="journey-list" id="journey-list"></ol>
</section>

<div class="grid">
  <div class="col">
    <section class="card c-map">
      <h2>Now Driving</h2>
      <div id="map" role="img" aria-label="車の位置と経路の地図"></div>
      <div class="facts" id="facts"></div>
    </section>
    <section class="card c-now">
      <h2>Now Playing</h2>
      <div id="now"><span class="empty">まだ曲が流れていません</span></div>
    </section>
    <section class="card c-cabin">
      <h2>In the Car</h2>
      <p class="lead">マイクで「誰かが話していたか」だけを1秒ごとに見ています。音声は送っていません。</p>
      <div class="cabin" id="cabin"><span class="empty">まだ届いていません</span></div>
    </section>
  </div>
  <div class="col">
    <section class="card c-cands">
      <h2>Candidates</h2>
      <p class="lead">欲しい曲調に近い曲ほど点が高く、点の近い曲の中からくじ引きで1曲を選びます。線は表示を開いてからの点の動きです。</p>
      <div id="eval-head" class="eval-head"></div>
      <ol class="cands" id="cands"><li class="empty">まだ評価していません</li></ol>
      <p class="formula">点 ＝ −曲調の遠さ ＋ 題材・知名度・好みの加点 − 同じアーティストの連続などの減点</p>
    </section>
  </div>
</div>

<footer>
  地図: <a href="https://openfreemap.org" rel="noopener">OpenFreeMap</a> / <a href="https://www.openstreetmap.org/copyright" rel="noopener">OpenStreetMap</a> contributors
</footer>
</main>

<script src="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.js"></script>
<script>
(function(){
  "use strict";
  var C = ${jsonForScript(config)};
  var params = new URLSearchParams(location.search);

  // 横長の大きな画面（モニター）では、1920×1080 の1枚に組んで画面に合わせて拡大・縮小する。
  // ?screen=1 で常に、?screen=0 で使わない
  var fitting = false;
  function applyFit(){
    var forced = params.get("screen");
    var on = forced === "1" || (forced !== "0" && innerWidth >= 1000 && innerWidth / innerHeight >= 1.3);
    var main = document.querySelector("main");
    document.documentElement.classList.toggle("fit", on);
    if (on) {
      var s = Math.min(innerWidth / 1920, innerHeight / 1080);
      main.style.transform = "translate(" + ((innerWidth - 1920 * s) / 2) + "px," + ((innerHeight - 1080 * s) / 2) + "px) scale(" + s + ")";
    } else main.style.transform = "";
    if (on !== fitting) { fitting = on; if (snap) renderJourney(); }
    if (typeof map !== "undefined" && map) map.resize();
  }
  applyFit();
  addEventListener("resize", applyFit);
  // 映しっぱなしにするので、できれば画面を消さない（対応していないブラウザでは何もしない）
  function keepAwake(){ try { if (fitting && navigator.wakeLock && document.visibilityState === "visible") navigator.wakeLock.request("screen").catch(function(){}); } catch (e) {} }
  keepAwake();
  document.addEventListener("visibilitychange", keepAwake);
  var POLL_MS = 1000, STALE_MS = 15000, DECIDE_BEFORE_MS = 30000, HISTORY = 90;
  var AXES = ["energy", "valence", "tempo", "acoustic", "spacious", "vocal"];

  var $ = function(id){ return document.getElementById(id); };
  function el(tag, cls, text){ var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function clear(e){ while (e.firstChild) e.removeChild(e.firstChild); }
  function clamp(v, a, b){ return Math.max(a, Math.min(b, v)); }
  function mmss(ms){ ms = Math.max(0, ms); var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2); }
  function km(m){ return m >= 10000 ? Math.round(m / 1000) + " km" : (Math.round(m / 100) / 10) + " km"; }
  function cat(c){ return C.categories[c] || { label: c, color: "#B9BDB7" }; }
  function themeColor(t){ return C.themeColors[t] || "#7F8590"; }
  function svgEl(tag, attrs){ var e = document.createElementNS("http://www.w3.org/2000/svg", tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }
  function keyOf(c){ return c.title + "|" + c.artist; }
  /** 曲調の遠さ（0〜おおむね0.5）を、近さの割合にする */
  function closeness(d){ return clamp(1 - d / 0.5, 0, 1); }
  /** その曲を流している間（流し終えていなければ曲の長さぶん先まで）に一番長かった景色 */
  function sceneDuring(p){
    var r = snap && snap.route; if (!r) return null;
    var end = p.endM;
    if (end == null) {
      var n = snap.nowPlaying, v = snap.car ? Math.max(snap.car.speedKmh, 20) / 3.6 : 11;
      end = p.startM + (n && n.durationMs ? n.durationMs / 1000 * v : 3000);
    }
    var best = null, bestLen = 0;
    var lens = {};
    r.segments.forEach(function(g){
      var len = Math.min(g.endM, end) - Math.max(g.startM, p.startM);
      if (len > 0) { lens[g.category] = (lens[g.category] || 0) + len; if (lens[g.category] > bestLen) { bestLen = lens[g.category]; best = g.category; } }
    });
    return best;
  }
  function categoryAt(m){
    var r = snap && snap.route; if (!r) return null;
    for (var i = 0; i < r.segments.length; i++) if (r.segments[i].startM <= m && m < r.segments[i].endM) return r.segments[i].category;
    return null;
  }

  var snap = null, receivedAt = 0, serverOffset = 0, routeKey = null, geom = null;
  var lastEvalAt = 0, prevEval = null, history = {};
  var timer = null, rate = 1;

  // ---- 地図 ----
  var map = null, mapReady = false, carMarker = null, fitted = false;
  try { if (window.maplibregl) {
    map = new maplibregl.Map({ container: "map", style: C.styleUrl, center: C.demo ? [139.48, 35.315] : [139.69, 35.69], zoom: C.demo ? 11 : 9, attributionControl: false });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.addControl(new maplibregl.AttributionControl({ compact: true }));
    map.on("load", function(){
      var empty = { type: "FeatureCollection", features: [] };
      map.addSource("segments", { type: "geojson", data: empty });
      map.addSource("window", { type: "geojson", data: empty });
      map.addLayer({ id: "window", type: "line", source: "window", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#1E2A24", "line-width": 16, "line-opacity": .22 } });
      map.addLayer({ id: "casing", type: "line", source: "segments", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": "#fff", "line-width": 9 } });
      var match = ["match", ["get", "category"]];
      Object.keys(C.categories).forEach(function(k){ match.push(k, C.categories[k].color); });
      match.push("#B9BDB7");
      map.addLayer({ id: "segments", type: "line", source: "segments", layout: { "line-cap": "round", "line-join": "round" }, paint: { "line-color": match, "line-width": 5 } });
      var car = el("div");
      car.style.cssText = "width:20px;height:20px;border-radius:50%;background:#EB6B40;border:3px solid #fff;box-shadow:0 1px 6px rgba(0,0,0,.4)";
      carMarker = new maplibregl.Marker({ element: car });
      mapReady = true;
      // 枠の大きさが決まる前に作ると、地図の画像が一部にしか描かれないので、描き直させる
      map.resize(); setTimeout(function(){ map.resize(); }, 300);
      drawRoute();
    });
  } } catch (err) {
    map = null;
    $("map").textContent = "この端末では地図を表示できません";
    $("map").style.cssText += ";display:flex;align-items:center;justify-content:center;font-size:13px;color:#5C6B63";
  }

  // 経路の形に、始点からの距離を付ける。景色の区間（距離で表される）を地図の線に切り出すのに使う
  function measure(coords){
    var out = [0], R = 6371008.8;
    for (var i = 1; i < coords.length; i++) {
      var a = coords[i - 1], b = coords[i];
      var p1 = a[1] * Math.PI / 180, p2 = b[1] * Math.PI / 180, dp = p2 - p1, dl = (b[0] - a[0]) * Math.PI / 180;
      var h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
      out.push(out[i - 1] + 2 * R * Math.asin(Math.min(1, Math.sqrt(h))));
    }
    return out;
  }
  function pointAt(m){
    var d = geom.dist, c = geom.coords, i = 1;
    while (i < d.length - 1 && d[i] < m) i++;
    var t = d[i] === d[i - 1] ? 0 : clamp((m - d[i - 1]) / (d[i] - d[i - 1]), 0, 1);
    return [c[i - 1][0] + (c[i][0] - c[i - 1][0]) * t, c[i - 1][1] + (c[i][1] - c[i - 1][1]) * t];
  }
  function slice(from, to){
    // 景観エンジンの距離と、形から測った距離は少しずれるので、全長の比で合わせる
    var scale = snap && snap.route && snap.route.totalM > 0 ? geom.dist[geom.dist.length - 1] / snap.route.totalM : 1;
    from *= scale; to *= scale;
    var pts = [pointAt(from)];
    for (var i = 0; i < geom.dist.length; i++) if (geom.dist[i] > from && geom.dist[i] < to) pts.push(geom.coords[i]);
    pts.push(pointAt(to));
    return pts;
  }
  function drawRoute(){
    if (!mapReady || !geom || !snap || !snap.route) return;
    var segs = snap.route.segments.length ? snap.route.segments : [{ startM: 0, endM: snap.route.totalM, category: "unknown" }];
    map.getSource("segments").setData({ type: "FeatureCollection", features: segs.map(function(s){
      return { type: "Feature", properties: { category: s.category }, geometry: { type: "LineString", coordinates: slice(s.startM, s.endM) } };
    }) });
    if (!fitted) {
      var b = geom.coords.reduce(function(acc, c){ return acc.extend(c); }, new maplibregl.LngLatBounds(geom.coords[0], geom.coords[0]));
      map.fitBounds(b, { padding: 30, duration: 0, maxZoom: 14 });
      fitted = true;
    }
    drawWindow();
  }
  function drawWindow(){
    if (!mapReady || !geom) return;
    var w = snap && snap.evaluation && snap.evaluation.window;
    map.getSource("window").setData(w ? { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: slice(w.startM, w.endM) } } : { type: "FeatureCollection", features: [] });
  }
  function loadGeometry(){
    var key = snap.route && snap.route.key;
    if (!key || key === routeKey) return;
    routeKey = key;
    fetch("/api/live/" + C.code + "/route", { cache: "no-store" }).then(function(r){ return r.ok ? r.json() : null; }).then(function(g){
      if (!g || g.routeKey !== routeKey || !g.geometry || g.geometry.length < 2) { if (g === null) routeKey = null; return; }
      geom = { coords: g.geometry, dist: measure(g.geometry) };
      fitted = false;
      drawRoute();
    }).catch(function(){ routeKey = null; });
  }

  // ---- 受け取り ----
  function poll(){
    clearTimeout(timer);
    fetch("/api/live/" + C.code, { cache: "no-store" }).then(function(r){ return r.json(); }).then(function(d){
      serverOffset = d.serverNow - Date.now();
      if (!d.open) showEnded("このライブ表示は終わったか、まだ始まっていません。車でライブ表示をオンにすると、ここに出ます。");
      else receive(d.snapshot);
      timer = setTimeout(poll, POLL_MS);
    }).catch(function(){
      setStatus("", "つながりません。再接続しています…");
      timer = setTimeout(poll, POLL_MS * 3);
    });
  }
  document.addEventListener("visibilitychange", function(){ if (C.demo) return; if (!document.hidden) poll(); else clearTimeout(timer); });

  function showEnded(text){
    $("ended").hidden = false; $("ended").textContent = text;
    setStatus("", "止まっています");
  }

  function receive(s){
    var first = !snap;
    snap = s; receivedAt = Date.now();
    $("ended").hidden = s.status !== "arrived";
    if (s.status === "arrived") {
      $("ended").textContent = "目的地に着きました。景観BGMはここまでです。";
      if (C.demo) { $("ended").appendChild(el("br")); var again = el("a", null, "もう一度再生する"); again.href = "/live/demo?speed=" + rate; $("ended").appendChild(again); }
    }
    $("title").textContent = s.destination ? s.destination + " へ" : "景観BGMの選曲ライブ";
    loadGeometry();
    if (first) drawRoute();
    var e = s.evaluation;
    if (e && e.at !== lastEvalAt) {
      e.candidates.forEach(function(c){
        var k = keyOf(c);
        (history[k] = history[k] || []).push(c.score);
        if (history[k].length > HISTORY) history[k].shift();
      });
      renderHero(e);
      renderCandidates(e);
      prevEval = e;
      lastEvalAt = e.at;
      drawWindow();
    }
    renderCabin(); renderJourney(); tick();
  }

  // ---- 描画 ----
  function setStatus(kind, text){
    var s = $("status"); s.className = "status " + kind;
    s.lastChild.textContent = text;
  }

  function tick(){
    if (!snap) return;
    var now = Date.now() + serverOffset, age = now - snap.at;
    if (C.demo) setStatus(snap.status === "arrived" ? "" : "on", snap.status === "arrived" ? "デモの走行が終わりました" : "デモを再生中 · " + rate + "倍速");
    else if (snap.status === "arrived") setStatus("", "到着しました");
    else if (age > STALE_MS) setStatus("stale", "車からの更新が " + Math.round(age / 1000) + " 秒止まっています");
    else setStatus("on", "ライブ · " + Math.max(0, Math.round(age / 1000)) + " 秒前の様子");
    var since = Math.min(Date.now() - receivedAt, 5000) * rate;
    if (snap.car && carMarker && mapReady) carMarker.setLngLat([snap.car.longitude, snap.car.latitude]).addTo(map);
    renderNow(since);
    renderFacts(since);
  }
  setInterval(tick, 250);

  function renderFacts(since){
    var f = $("facts"); clear(f);
    var r = snap.route, car = snap.car;
    if (car) { var s = el("span"); s.appendChild(el("b", null, String(Math.round(car.speedKmh)))); s.appendChild(document.createTextNode("km/h")); f.appendChild(s); }
    if (r) {
      var pos = r.positionM + (car && snap.status === "driving" ? car.speedKmh / 3.6 * since / 1000 : 0);
      var s2 = el("span"); s2.appendChild(document.createTextNode("目的地まで ")); s2.appendChild(el("b", null, km(Math.max(0, r.totalM - pos)).replace(" km", ""))); s2.appendChild(document.createTextNode("km")); f.appendChild(s2);
      var here = categoryAt(pos);
      if (here) { var s3 = el("span"); s3.appendChild(document.createTextNode("いまは ")); var b = el("b", null, cat(here).label); b.style.color = cat(here).color; s3.appendChild(b); f.appendChild(s3); }
    }
  }

  // ① この先の景色 → ② 欲しい曲調 → ③ 選んだ曲
  function renderHero(e){
    // ①
    var shares = Object.keys(e.shares).filter(function(k){ return e.shares[k] > 0.005; }).sort(function(a, b){ return e.shares[b] - e.shares[a]; });
    var top = shares[0];
    document.documentElement.style.setProperty("--scene", top ? cat(top).color : "#7F8590");
    // 区間は経路の終点より先まで伸びることがある（曲の途中で着く）ので、終点で止めて出す
    var winEnd = e.window ? Math.min(e.window.endM, snap.route ? snap.route.totalM : e.window.endM) : 0;
    $("scene-sub").textContent = e.window ? "次の曲が流れる " + km(Math.max(0, winEnd - e.window.startM)) : "";
    var scene = $("scene"); clear(scene);
    if (top) {
      var big = el("div", "scene-big"); big.appendChild(el("b", null, cat(top).label)); big.appendChild(el("span", null, "が " + Math.round(e.shares[top] * 100) + "%"));
      scene.appendChild(big);
      var rows = el("div", "scene-rows");
      shares.forEach(function(k){
        var row = el("div", "scene-row"), bar = el("div", "bar"), fill = el("i");
        row.appendChild(el("span", null, cat(k).label));
        fill.style.width = Math.round(e.shares[k] * 100) + "%"; fill.style.background = cat(k).color; bar.appendChild(fill); row.appendChild(bar);
        row.appendChild(el("span", "pct", Math.round(e.shares[k] * 100) + "%"));
        rows.appendChild(row);
      });
      scene.appendChild(rows);
    } else scene.appendChild(el("p", "empty", "この先の景色はまだ分かっていません"));
    if (e.reasons.length) {
      scene.appendChild(el("div", "because", "だから"));
      var chips = el("div", "chips");
      e.reasons.forEach(function(r){ chips.appendChild(el("span", "chip strong", r)); });
      scene.appendChild(chips);
    }

    // ②
    var chosen = e.chosen ? e.candidates.filter(function(c){ return c.title === e.chosen.title && c.artist === e.chosen.artist; })[0] : null;
    var mood = $("mood"); clear(mood);
    mood.appendChild(radar(e.target, chosen && chosen.axes));
    var legend = el("div", "radar-legend");
    var l1 = el("span"); l1.appendChild(el("i")); l1.appendChild(document.createTextNode("景色から決めた曲調")); legend.appendChild(l1);
    if (chosen && chosen.axes) { var l2 = el("span"); l2.appendChild(el("i", "song")); l2.appendChild(document.createTextNode("選んだ曲")); legend.appendChild(l2); }
    mood.appendChild(legend);
    var themes = Object.keys(e.themes).sort(function(a, b){ return e.themes[b] - e.themes[a]; }).filter(function(k){ return e.themes[k] >= 0.3; }).slice(0, 4);
    if (themes.length) {
      mood.appendChild(el("div", "because", "歌詞の題材なら"));
      var tchips = el("div", "chips");
      themes.forEach(function(k){ var c = el("span", "chip theme", C.themes[k] || k); c.style.background = themeColor(k); tchips.appendChild(c); });
      mood.appendChild(tchips);
    }
    $("mood-sub").textContent = "";

    // ③
    var pick = $("pick"); clear(pick);
    if (!e.chosen) { pick.appendChild(el("p", "empty", "選べる曲がありません")); return; }
    var decided = e.kind === "decided";
    pick.appendChild(el("span", "pick-badge" + (decided ? " decided" : ""), decided ? "次の曲に決定" : "今決めるならこの曲"));
    pick.appendChild(el("div", "pick-title", e.chosen.title));
    pick.appendChild(el("div", "pick-artist", e.chosen.artist));
    if (e.chosen.message) pick.appendChild(el("div", "pick-msg", e.chosen.message));
    if (chosen) {
      var why = el("div", "pick-why");
      why.appendChild(whyRow("曲調の近さ", closeness(chosen.distance), Math.round(closeness(chosen.distance) * 100) + "%", "#EB6B40"));
      if (chosen.theme && chosen.themeMatch > 0) why.appendChild(whyRow("題材「" + (C.themes[chosen.theme] || chosen.theme) + "」", chosen.themeMatch, Math.round(chosen.themeMatch * 100) + "%", themeColor(chosen.theme)));
      if (chosen.popularity > 0) why.appendChild(whyRow("知名度", chosen.popularity, Math.round(chosen.popularity * 100) + "%", "#D9B44A"));
      pick.appendChild(why);
    }
    pick.appendChild(el("div", "pick-foot", decided ? "曲の終わりで、この曲に切り替わります" : "曲の終わりの30秒前に決めます。それまでは景色に合わせて入れ替わります"));
  }

  function whyRow(label, value, text, color){
    var row = el("div", "why-row"), bar = el("div", "bar"), fill = el("i");
    row.appendChild(el("span", null, label));
    fill.style.width = Math.round(clamp(value, 0, 1) * 100) + "%"; fill.style.background = color; bar.appendChild(fill); row.appendChild(bar);
    row.appendChild(el("b", null, text));
    return row;
  }

  // 6軸のレーダー。景色から決めた曲調を塗り、選んだ曲を点線で重ねる
  function radar(target, song){
    var W = 340, H = 330, cx = 170, cy = 160, R = 100;
    var svg = svgEl("svg", { "class": "radar", viewBox: "0 0 " + W + " " + H, role: "img", "aria-label": "欲しい曲調と選んだ曲の6軸" });
    function pt(i, v){ var a = (-90 + i * 60) * Math.PI / 180; return [cx + Math.cos(a) * R * v, cy + Math.sin(a) * R * v]; }
    [.25, .5, .75, 1].forEach(function(r){ svg.appendChild(svgEl("polygon", { "class": "ring", points: AXES.map(function(_, i){ return pt(i, r).join(","); }).join(" ") })); });
    AXES.forEach(function(k, i){
      var p = pt(i, 1); svg.appendChild(svgEl("line", { "class": "spoke", x1: cx, y1: cy, x2: p[0], y2: p[1] }));
      var q = pt(i, 1.24), anchor = Math.abs(q[0] - cx) < 4 ? "middle" : q[0] > cx ? "start" : "end";
      var y = q[1] + (q[1] < cy - 20 ? -4 : q[1] > cy + 20 ? 12 : 4);
      var t = svgEl("text", { "class": "lbl", x: q[0], y: y, "text-anchor": anchor }); t.textContent = C.axes[k].name; svg.appendChild(t);
      var v = svgEl("text", { "class": "val", x: q[0], y: y + 15, "text-anchor": anchor }); v.textContent = target[k].toFixed(2); svg.appendChild(v);
    });
    svg.appendChild(svgEl("polygon", { "class": "target", points: AXES.map(function(k, i){ return pt(i, target[k]).join(","); }).join(" ") }));
    if (song) svg.appendChild(svgEl("polygon", { "class": "song", points: AXES.map(function(k, i){ return pt(i, song[k]).join(","); }).join(" ") }));
    return svg;
  }

  // 経路全体の景色の帯に、曲を流し始めた地点を番号で立てる
  function renderJourney(){
    var r = snap.route, songs = $("journey-songs"), band = $("journey-band"), list = $("journey-list");
    clear(songs); clear(band); clear(list);
    if (!r || !r.totalM) return;
    $("journey-goal").textContent = "到着（" + km(r.totalM) + "）";
    var total = r.totalM;
    r.segments.forEach(function(g){
      var d = el("div", "seg"); d.style.left = (g.startM / total * 100) + "%"; d.style.width = ((g.endM - g.startM) / total * 100) + "%";
      d.style.background = cat(g.category).color; d.title = cat(g.category).label; band.appendChild(d);
    });
    var done = el("div", "done"); done.style.width = clamp(r.positionM / total * 100, 0, 100) + "%"; band.appendChild(done);
    var w = snap.evaluation && snap.evaluation.window;
    if (w && snap.status !== "arrived") { var box = el("div", "win"); box.style.left = (w.startM / total * 100) + "%"; box.style.width = Math.max((w.endM - w.startM) / total * 100, 1) + "%"; band.appendChild(box); }
    var played = snap.played.filter(function(p){ return p.startM != null; }).slice().reverse();
    if (!played.length) { list.appendChild(el("li", "empty", "まだ曲を流していません")); return; }
    var firstShown = fitting ? Math.max(0, played.length - 4) : 0;
    played.forEach(function(p, i){
      var pin = el("div", "pin"); pin.style.left = (p.startM / total * 100) + "%"; pin.appendChild(el("span", null, String(i + 1))); songs.appendChild(pin);
      if (i < firstShown) return;
      var li = el("li"), where = p.scene || sceneDuring(p);
      li.appendChild(el("span", "no", String(i + 1)));
      var w2 = el("span", "where", where ? cat(where).label : "—"); w2.style.background = where ? cat(where).color : "#9AA09A"; li.appendChild(w2);
      var body = el("div"), t = el("div", "t", p.title);
      if (i === played.length - 1 && snap.status !== "arrived") t.appendChild(el("span", "now-tag", "再生中"));
      body.appendChild(t); body.appendChild(el("div", "a", p.artist));
      if (p.message) body.appendChild(el("div", "m", p.message));
      li.appendChild(body); list.appendChild(li);
    });
  }

  function renderCabin(){
    var box = $("cabin"); clear(box);
    var c = snap.cabin;
    if (!c) { box.appendChild(el("span", "empty", "マイクが使えないので、車内は「ふつう」として選んでいます")); return; }
    box.appendChild(el("span", "chip " + c.state, C.cabin[c.state] || c.state));
    var talk = el("span", "talk" + (c.speaking ? " on" : "")); talk.appendChild(el("i")); talk.appendChild(document.createTextNode(c.speaking ? "話し声あり" : "話し声なし")); box.appendChild(talk);
    var meter = el("div", "meter"), bar = el("div", "bar"), fill = el("div", "fill"), mark = el("div", "mark");
    fill.style.width = Math.round(c.speechRatio * 100) + "%"; mark.style.left = "40%";
    bar.appendChild(fill); bar.appendChild(mark); meter.appendChild(bar);
    var cap = el("div", "cap"); cap.appendChild(el("span", null, "直近1分で話していた割合 " + Math.round(c.speechRatio * 100) + "%")); cap.appendChild(el("span", null, "40%〜 にぎやか"));
    meter.appendChild(cap); box.appendChild(meter);
  }

  function renderNow(since){
    var box = $("now"), n = snap.nowPlaying; clear(box);
    if (!n) { box.appendChild(el("span", "empty", "まだ曲が流れていません")); return; }
    var title = el("div", "now-title", n.title);
    title.appendChild(el("span", "tag", n.kind === "bgm" ? "景観BGM" : n.kind === "request" ? "リクエスト" : "そのほか"));
    box.appendChild(title); box.appendChild(el("div", "now-artist", n.artist));
    var pos = clamp(n.positionMs + (n.playing ? since : 0), 0, n.durationMs || 0);
    var p = el("div", "progress"), fill = el("div", "fill");
    fill.style.width = (n.durationMs ? pos / n.durationMs * 100 : 0) + "%"; p.appendChild(fill);
    if (n.durationMs > DECIDE_BEFORE_MS) { var d = el("div", "decide"); d.style.left = ((n.durationMs - DECIDE_BEFORE_MS) / n.durationMs * 100) + "%"; p.appendChild(d); }
    box.appendChild(p);
    var t = el("div", "times"); t.appendChild(el("span", null, mmss(pos))); t.appendChild(el("span", null, mmss(n.durationMs))); box.appendChild(t);
    var left = n.durationMs - pos - DECIDE_BEFORE_MS, cd = el("div", "countdown");
    if (snap.status === "arrived") cd.textContent = "着いたので、この曲で終わります";
    else if (n.kind !== "bgm") cd.textContent = "この曲のあとに景観BGMに戻ります";
    else if (snap.evaluation && snap.evaluation.kind === "decided") cd.textContent = "次の曲は決まりました";
    else if (left > 0) { cd.appendChild(document.createTextNode("あと ")); cd.appendChild(el("b", null, mmss(left))); cd.appendChild(document.createTextNode(" で次の曲を決めます")); }
    else cd.textContent = "次の曲を決めています…";
    box.appendChild(cd);
  }

  function renderCandidates(e){
    var head = $("eval-head"); clear(head);
    head.appendChild(el("span", "badge" + (e.kind === "decided" ? " decided" : ""), e.kind === "decided" ? "決定" : "途中の評価"));
    head.appendChild(el("span", "muted", e.slot === "theme" ? "題材の枠（場面の題材を歌った曲から選ぶ回）" : "通常の枠（点の近い曲からくじ引き）"));
    var list = $("cands"); clear(list);
    if (!e.candidates.length) { list.appendChild(el("li", "empty", "選べる曲がありません")); return; }
    // エンジンは選んだ曲を先頭に返す（題材の枠では点が低くても選ばれる）。ここでは点の順に並べ、選んだ曲に印を付ける
    var cands = e.candidates.slice().sort(function(a, b){ return b.score - a.score; });
    var prevScores = {};
    if (prevEval) prevEval.candidates.forEach(function(c){ prevScores[keyOf(c)] = c.score; });
    var all = []; cands.forEach(function(c){ all = all.concat(history[keyOf(c)] || []); });
    var lo = Math.min.apply(null, all), hi = Math.max.apply(null, all); if (hi - lo < 0.02) { lo -= 0.01; hi += 0.01; }
    cands.forEach(function(c, i){
      var key = keyOf(c);
      var chosen = e.chosen && c.title === e.chosen.title && c.artist === e.chosen.artist;
      var li = el("li", "cand" + (chosen ? " pick" : ""));
      li.appendChild(el("span", "rank", String(i + 1)));
      var song = el("div", "song"), t = el("div", "t", c.title);
      if (chosen) t.appendChild(el("span", "tag pick-tag", e.kind === "decided" ? "次の曲" : "今ならこれ"));
      song.appendChild(t); song.appendChild(el("div", "a", c.artist)); li.appendChild(song);
      var svg = svgEl("svg", { "class": "spark", viewBox: "0 0 84 28", preserveAspectRatio: "none" });
      var h = history[key] || [];
      if (h.length > 1) svg.appendChild(svgEl("polyline", { points: h.map(function(v, j){ return (j / (HISTORY - 1) * 84).toFixed(1) + "," + (26 - (v - lo) / (hi - lo) * 24).toFixed(1); }).join(" ") }));
      li.appendChild(svg);
      var sc = el("div", "score", c.score.toFixed(3)), prev = prevScores[key];
      if (prev != null) { var dv = c.score - prev; sc.appendChild(el("span", "delta" + (dv > 0.0005 ? " up" : dv < -0.0005 ? " down" : ""), (dv > 0.0005 ? "▲" : dv < -0.0005 ? "▼" : "±") + Math.abs(dv).toFixed(3))); }
      else sc.appendChild(el("span", "delta", "new"));
      li.appendChild(sc);
      var why = el("div", "why");
      why.appendChild(el("span", null, "曲調の近さ " + Math.round(closeness(c.distance) * 100) + "%"));
      if (c.theme && c.themeMatch > 0) { var th = el("span", "th", "題材 " + (C.themes[c.theme] || c.theme) + " " + Math.round(c.themeMatch * 100) + "%"); th.style.background = themeColor(c.theme); why.appendChild(th); }
      if (c.pref === "track") why.appendChild(el("span", null, "プレイリストの曲"));
      if (c.pref === "artist") why.appendChild(el("span", null, "好きなアーティスト"));
      if (c.popularity > 0) why.appendChild(el("span", null, "知名度 " + Math.round(c.popularity * 100) + "%"));
      if (c.sameArtist) why.appendChild(el("span", "minus", "直前と同じアーティスト"));
      if (c.confPenalty > 0) why.appendChild(el("span", "minus", "注釈が不確か"));
      why.appendChild(el("span", null, "選ばれる確率 " + Math.round(c.prob * 1000) / 10 + "%"));
      li.appendChild(why);
      list.appendChild(li);
    });
  }

  // ---- デモの再生 ----
  // 1秒ごとの様子を、開始してからの経過時間 × 倍率の位置から取り出して、車から届いたものと同じ道筋で描く
  function playDemo(speed){
    rate = speed;
    $("demo").hidden = true;
    setStatus("", "デモを読み込んでいます…");
    fetch("/live-demo.json").then(function(r){ return r.json(); }).then(function(d){
      geom = { coords: d.geometry, dist: measure(d.geometry) };
      routeKey = d.route.key;
      var startedAt = Date.now(), shown = -1;
      function frame(){
        var i = Math.min(d.frames.length - 1, Math.floor((Date.now() - startedAt) / 1000 * speed));
        if (i !== shown) {
          shown = i;
          var f = d.frames[i], e = f.e >= 0 ? d.evaluations[f.e] : null;
          receive({
            at: Date.now(), status: f.status, destination: d.destination, car: f.car,
            route: { key: d.route.key, positionM: f.route.positionM, totalM: d.route.totalM, segments: d.route.segments },
            cabin: f.cabin, nowPlaying: f.nowPlaying, played: f.played,
            // 評価の時刻は、同じ評価かどうかの見分けにだけ使う
            evaluation: e ? Object.assign({}, e, { at: startedAt + e.at * 1000 }) : null
          });
        }
        if (i < d.frames.length - 1) timer = setTimeout(frame, 200);
        // ?loop=1 なら、着いてしばらくしたら最初から再生し直す（展示で映しっぱなしにするため）
        else if (params.get("loop") === "1") timer = setTimeout(function(){ location.reload(); }, 20000);
      }
      frame();
    }).catch(function(){ setStatus("", "デモを読み込めませんでした"); });
  }

  if (C.demo) {
    var speed = Number(params.get("speed"));
    if ([1, 2, 4].indexOf(speed) >= 0) playDemo(speed);
    else setStatus("", "開始を押すと再生します");
  } else poll();
})();
</script>
</body>
</html>`;
}
