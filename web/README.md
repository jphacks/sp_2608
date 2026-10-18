# 旅れこ「旅のステッカー」Web 版

ステッカー右下の QR から開く公開ページ（`/t/{id}`）と、アプリから旅の記録・音楽ログを登録する API。
Cloudflare Workers（TypeScript）。記録と音楽ログはD1、写真はR2。[設計とAPI](../docs/memory-web.md)を参照してください。

## 必要なもの

- git と Node.js 22 以上（`node -v` で確認）
- Cloudflare のアカウントは **不要**。ローカルでは wrangler が D1 / R2 の代わりを `.wrangler/state/` に作る。

## はじめて起動するとき

リポジトリを取ってきて（すでにあるなら `git pull origin main`）、ルートではなく `web/` の中で実行する。

```sh
git clone https://github.com/takumamiraidai/TheaCar.git
cd TheaCar/web
npm install
cp .dev.vars.example .dev.vars            # API のトークン。中身は TRIP_UPLOAD_TOKEN=dev-token のままで可
npx wrangler d1 migrations apply tabireco --local   # ローカル DB に表を作る（初回と migrations/ が増えたとき）
npm run dev                               # http://localhost:8787 で起動
```

`.dev.vars.example` は隠しファイルなので `ls -a` でないと見えない。
`npx wrangler d1 migrations apply ...` は「Migrations to be applied」と出たあと確認を求めることがある。`y` で進める。

起動直後は DB が空なので、`/t/...` を開いても 404 になる。次の手順でサンプルを入れる。

## サンプルの記録を入れてページを見る

別のターミナルで（`npm run dev` は動かしたまま）:

```sh
cd TheaCar/web
# 旅の記録
curl -X POST http://localhost:8787/api/trips \
  -H 'Authorization: Bearer dev-token' \
  -F 'trip=<sample/trip.json'
# → {"id":"11111111-...","url":"http://localhost:8787/t/11111111-2222-4333-8444-555555555555"}

# 旅中の音楽と盛り上がりのログ（同じ deviceId・時間帯なので上の旅に結び付く）
curl -X POST http://localhost:8787/api/music-logs \
  -H 'Authorization: Bearer dev-token' -H 'Content-Type: application/json' \
  --data-binary @sample/music-log.json
```

ブラウザで http://localhost:8787/t/11111111-2222-4333-8444-555555555555 を開く。
ステッカー、Trip、Genres、Lively Tracks、Mood、Route の各欄が出れば起動できている。

`trip.json`の形は[データモデル](../docs/memory-web.md#データモデル)を参照。

ページの `id` はサーバーが発行するのではなく、送る JSON の `id`（UUID）がそのまま使われる。アプリからは `TripMemory.id` が入る。
別のページを作りたいときは、新しい UUID を作って `sample/trip.json` の `id` に入れ、同じ curl で登録する。応答の `url` がそのページ。

```sh
uuidgen | tr 'A-Z' 'a-z'      # 新しい UUID を作る（小文字にする）
```

登録済みの id を一覧するには:

```sh
npx wrangler d1 execute tabireco --local --command "select id from trips"
```

写真も付けるときは `-F 'photo=@photo.jpg;type=image/jpeg'` を足す（JPEG、8 MB まで）。
同じ `id` で再送すると上書きされる。JSON だけ見たいときは `GET /api/trips/{id}`。

## 開発のしかた

- `src/` を編集すると `wrangler dev` が自動で再読み込みする。ブラウザは再読み込みするだけでよい。
- ページの HTML は `src/page.ts` で組み立てている。見た目の変更はここと `src/charts.ts`（グラフ）、`src/route-svg.ts`（経路線の SVG）。
- 公開ページには 60 秒のキャッシュヘッダが付く。ブラウザが古いページを見せるときは URL に `?v=2` のような適当なクエリを付けて開き直す。
- 色・書体と掲載情報は[Web版の設計](../docs/memory-web.md)と[ステッカーの仕様](../docs/memory-sticker.md)を参照する。

### ファイルの場所

| 場所 | 内容 |
| --- | --- |
| `src/index.ts` | ルーティング、認証、入力サイズの検査 |
| `src/trip.ts` / `src/music.ts` | 受け取る JSON の検証と型 |
| `src/store.ts` | D1 / R2 の読み書き |
| `src/page.ts` | 公開ページの HTML。`src/charts.ts`、`src/route-svg.ts`、`src/format.ts` を使う |
| `migrations/` | D1 のスキーマ。変更は新しい番号のファイルを足す（既存は書き換えない） |
| `public/` | 静的ファイル（favicon など）。`/` 直下で配信される |
| `sample/` | 上の手順で使うサンプルの JSON |
| `test/` | vitest のテスト |

## テスト・型チェック

```sh
npm test            # vitest。本物の Workers 実行環境（workerd）で D1 / R2 ごと動く
npm run typecheck   # tsc --noEmit
```

## 困ったとき

- `401 unauthorized` になる: `.dev.vars` が無い、または curl のトークンと中身が違う。作り直したら `npm run dev` を再起動する。
- `no such table` のようなエラー: `npx wrangler d1 migrations apply tabireco --local` を実行していない。
- ローカルの DB と写真を全部消してやり直したい: `npm run dev` を止めて `.wrangler/state/` を削除し、migrations を適用し直す。
- ポート 8787 が使われている: `npx wrangler dev --port 8788` のように変える。

## デプロイ

本番のD1・R2・secretの作成は[デプロイ手順](../docs/memory-web.md#デプロイ手順)を参照。`wrangler.jsonc`の`database_id`は本番のD1を作ってから設定する。
