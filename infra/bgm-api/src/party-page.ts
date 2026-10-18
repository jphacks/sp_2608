// 同乗者が QR コードから開くリクエスト画面。ログインもアプリも要らない、1枚のページ。
// 外部のスクリプトや計測は読み込まない（CSP で自分の配信元と Spotify のジャケット画像だけを許す）。

export function partyPageHtml(): string {
  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<meta name="color-scheme" content="light dark">
<title>ドライブのリクエスト</title>
<link rel="stylesheet" href="app.css">
<script src="app.js" defer></script>
</head>
<body>
<main>
  <header>
    <p class="eyebrow">旅れこ</p>
    <h1>ドライブのリクエスト</h1>
    <p class="lead">曲を探して、次に流すか今すぐ流すかを選べます。</p>
  </header>

  <section id="closed" class="card notice" hidden>
    <h2>このドライブのリクエストは終わりました</h2>
    <p>新しいドライブでは、車の画面に出る QR コードを読み取ってください。</p>
  </section>

  <div id="open">
    <section class="card" id="now">
      <h2>いま流れている曲</h2>
      <p class="track-title" id="now-title">—</p>
      <p class="track-artist" id="now-artist"></p>
      <p class="badge" id="now-by" hidden></p>
    </section>

    <section class="card">
      <h2>このあと流れる曲</h2>
      <ol id="queue" class="queue"></ol>
      <p id="queue-empty" class="muted">予約はまだありません。予約がなくなると、景色に合う曲に戻ります。</p>
    </section>

    <section class="card">
      <label for="name">あなたの名前</label>
      <input id="name" maxlength="20" autocomplete="nickname" placeholder="例：たろう">
      <p class="muted small">車の画面に「〇〇さんのリクエスト」と出ます。</p>
    </section>

    <section class="card">
      <form id="search-form" role="search">
        <label for="q">曲名・アーティストで探す</label>
        <div class="row">
          <input id="q" type="search" maxlength="100" enterkeyhint="search" placeholder="例：夏の終わり" required>
          <button type="submit" id="search-button">検索</button>
        </div>
      </form>
      <p id="search-status" class="muted" aria-live="polite"></p>
      <ul id="results" class="results"></ul>
    </section>
  </div>
</main>
<div id="toast" class="toast" role="status" aria-live="polite" hidden></div>
</body>
</html>`;
}

export const PARTY_PAGE_CSS = `
:root {
  --bg: #f4f1ea; --card: #ffffff; --ink: #1d2433; --muted: #5d6573; --line: #e3ddd1;
  --accent: #1f6f5c; --accent-ink: #ffffff; --sun: #f2b84b; --danger: #b3261e;
  font-family: system-ui, -apple-system, "Hiragino Sans", "Noto Sans JP", sans-serif;
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #11151c; --card: #1b212b; --ink: #edf0f5; --muted: #a3abb8; --line: #2c3340; --accent: #4fb89a; --accent-ink: #0d1a16; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--ink); line-height: 1.5; }
main { max-width: 560px; margin: 0 auto; padding: 20px 16px calc(32px + env(safe-area-inset-bottom)); }
header { margin: 4px 0 16px; }
.eyebrow { margin: 0; color: var(--accent); font-weight: 700; letter-spacing: .08em; font-size: 13px; }
h1 { margin: 2px 0 4px; font-size: 24px; }
h2 { margin: 0 0 8px; font-size: 15px; color: var(--muted); font-weight: 600; }
.lead, .muted { color: var(--muted); margin: 0; }
.small { font-size: 13px; margin-top: 6px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 16px; margin-bottom: 12px; }
.notice { border-color: var(--sun); }
.track-title { font-size: 19px; font-weight: 700; margin: 0; overflow-wrap: anywhere; }
.track-artist { margin: 0; color: var(--muted); overflow-wrap: anywhere; }
.badge { display: inline-block; margin: 8px 0 0; padding: 2px 10px; border-radius: 999px; background: var(--sun); color: #2a1d00; font-size: 13px; font-weight: 600; }
label { display: block; font-weight: 600; margin-bottom: 6px; }
input { width: 100%; font: inherit; padding: 12px; border-radius: 12px; border: 1px solid var(--line); background: var(--bg); color: var(--ink); min-height: 48px; }
.row { display: flex; gap: 8px; }
.row input { flex: 1; min-width: 0; }
button { font: inherit; font-weight: 600; border: 0; border-radius: 12px; padding: 0 16px; min-height: 48px; cursor: pointer; background: var(--accent); color: var(--accent-ink); }
button.secondary { background: transparent; color: var(--accent); border: 1px solid var(--accent); }
button:disabled { opacity: .5; cursor: default; }
.queue { margin: 0; padding-left: 22px; }
.queue li { padding: 6px 0; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.queue li:last-child { border-bottom: 0; }
.queue .by { color: var(--muted); font-size: 13px; }
.results { list-style: none; margin: 12px 0 0; padding: 0; }
.results li { display: grid; grid-template-columns: 56px 1fr; gap: 12px; padding: 12px 0; border-top: 1px solid var(--line); }
.results img, .results .noimg { width: 56px; height: 56px; border-radius: 8px; object-fit: cover; background: var(--line); }
.results .meta { min-width: 0; }
.results .title { font-weight: 700; overflow-wrap: anywhere; }
.results .artist { color: var(--muted); font-size: 14px; overflow-wrap: anywhere; }
.results .actions { grid-column: 1 / -1; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.toast { position: fixed; left: 16px; right: 16px; bottom: calc(16px + env(safe-area-inset-bottom)); max-width: 528px; margin: 0 auto; padding: 14px 16px; border-radius: 14px; background: var(--ink); color: var(--bg); font-weight: 600; box-shadow: 0 8px 24px rgba(0,0,0,.25); }
.toast.error { background: var(--danger); color: #fff; }
`;

export const PARTY_PAGE_JS = `
'use strict';
(() => {
  const base = location.pathname.replace(/\\/+$/, '');
  const $ = (id) => document.getElementById(id);
  const store = {
    get(key) { try { return localStorage.getItem(key); } catch { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch {} },
  };
  let guestId = store.get('theacar-guest');
  if (!guestId || !/^[A-Za-z0-9_-]{8,64}$/.test(guestId)) {
    const bytes = crypto.getRandomValues(new Uint8Array(12));
    guestId = btoa(String.fromCharCode(...bytes)).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
    store.set('theacar-guest', guestId);
  }
  const name = $('name');
  name.value = store.get('theacar-name') || '';
  name.addEventListener('input', () => store.set('theacar-name', name.value.trim()));

  let toastTimer = null;
  function toast(text, error) {
    const el = $('toast');
    el.textContent = text;
    el.className = error ? 'toast error' : 'toast';
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 3500);
  }

  function setClosed(closed) {
    $('closed').hidden = !closed;
    $('open').hidden = closed;
  }

  function byLabel(item) {
    if (!item) return '';
    if (item.kind === 'request') return item.by ? item.by + 'さんのリクエスト' : 'リクエスト';
    return '景色に合わせた曲';
  }

  async function refresh() {
    try {
      const res = await fetch(base + '/state', { cache: 'no-store' });
      const data = await res.json();
      if (!data.open) { setClosed(true); return; }
      setClosed(false);
      const now = data.now_playing;
      $('now-title').textContent = now ? now.title : '—';
      $('now-artist').textContent = now ? now.artist : '';
      $('now-by').hidden = !now;
      $('now-by').textContent = byLabel(now);
      const queue = $('queue');
      queue.replaceChildren(...(data.queue || []).map((item) => {
        const li = document.createElement('li');
        const title = document.createElement('div');
        title.textContent = item.title + (item.artist ? ' · ' + item.artist : '');
        const by = document.createElement('div');
        by.className = 'by';
        by.textContent = byLabel(item);
        li.append(title, by);
        return li;
      }));
      $('queue-empty').hidden = (data.queue || []).length > 0;
    } catch {
      // 電波が弱いときは次の更新を待つ
    }
  }

  async function search(event) {
    event.preventDefault();
    const q = $('q').value.trim();
    if (!q) return;
    $('search-button').disabled = true;
    $('search-status').textContent = '探しています…';
    $('results').replaceChildren();
    try {
      const res = await fetch(base + '/search?q=' + encodeURIComponent(q), { cache: 'no-store' });
      const data = await res.json();
      if (res.status === 410) { setClosed(true); return; }
      if (!res.ok) throw new Error(data.error || 'error');
      const tracks = data.tracks || [];
      $('search-status').textContent = tracks.length ? '' : '見つかりませんでした。別の言葉で探してください。';
      $('results').replaceChildren(...tracks.map(resultItem));
    } catch {
      $('search-status').textContent = '検索できませんでした。少し待ってからもう一度お試しください。';
    } finally {
      $('search-button').disabled = false;
    }
  }

  function resultItem(track) {
    const li = document.createElement('li');
    let art;
    if (track.image_url) {
      art = document.createElement('img');
      art.src = track.image_url;
      art.alt = '';
      art.loading = 'lazy';
    } else {
      art = document.createElement('div');
      art.className = 'noimg';
    }
    const meta = document.createElement('div');
    meta.className = 'meta';
    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = track.title;
    const artist = document.createElement('div');
    artist.className = 'artist';
    artist.textContent = track.artist;
    meta.append(title, artist);
    const actions = document.createElement('div');
    actions.className = 'actions';
    const next = document.createElement('button');
    next.type = 'button';
    next.textContent = '次に予約';
    const now = document.createElement('button');
    now.type = 'button';
    now.className = 'secondary';
    now.textContent = '今すぐ流す';
    next.addEventListener('click', () => request(track, 'next', [next, now]));
    now.addEventListener('click', () => request(track, 'now', [next, now]));
    actions.append(next, now);
    li.append(art, meta, actions);
    return li;
  }

  async function request(track, mode, buttons) {
    const who = name.value.trim();
    if (!who) { toast('先に名前を入れてください', true); name.focus(); return; }
    buttons.forEach((b) => { b.disabled = true; });
    try {
      const res = await fetch(base + '/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ guest_id: guestId, name: who, uri: track.uri, mode }),
      });
      if (res.status === 410) { setClosed(true); return; }
      if (res.status === 429) { toast('続けて送りすぎです。少し待ってからどうぞ', true); return; }
      if (!res.ok) throw new Error('error');
      toast(mode === 'now' ? '「' + track.title + '」をすぐ流します' : '「' + track.title + '」を予約しました');
      setTimeout(refresh, 6000);
    } catch {
      toast('送れませんでした。電波の良いところでもう一度お試しください', true);
    } finally {
      buttons.forEach((b) => { b.disabled = false; });
    }
  }

  $('search-form').addEventListener('submit', search);
  refresh();
  setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, 5000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refresh(); });
})();
`;
