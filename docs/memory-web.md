# 旅のステッカー Web 版（web/）

## 役割

ステッカー右下の QR から開く「このステッカーの Web 版」。紙に載せた情報（デフォルメ写真＋経路線、日付、地名、メンバー、距離、時間）を同じ見た目で再現し、紙に載せきれない情報（地図上の経路、出発・到着時刻、平均速度、出発地・到着地、写真の撮影地点）を足す。

- 実装: `web/`（Cloudflare Workers、TypeScript、D1 + R2）。旅の記録と音楽ログは D1（データベース名 `tabireco`、スキーマは `web/migrations/`）、写真は R2
- 公開ページはサーバ側で HTML を組み立てる。SPA なし。経路線は SVG、地図は MapLibre GL JS（スタイル `https://tiles.openfreemap.org/styles/liberty`）
- 書体は Google Fonts（Jost / Noto Sans JP）、色はデザインルールの 8 色
- キャッシュは公開ページ60秒、写真1日。写真URLに更新時刻を`?v=`で付ける。

## URL と API

公開ページは`/t/{id}`（`id`はTripMemoryのUUID）。Androidの`MEMORY_WEB_URL`にデプロイ先を設定する。Workerの`PUBLIC_BASE_URL`は空ならリクエスト元のoriginを使い、独自ドメインを固定したい場合だけ指定する。

| メソッド | パス | 用途 | 認証 |
| --- | --- | --- | --- |
| POST | `/api/trips` | 記録（＋写真）の登録・更新 | `Authorization: Bearer <TRIP_UPLOAD_TOKEN>` |
| POST | `/api/music-logs` | 旅中の音楽と盛り上がりのログの登録（アプリの音楽機能が案内終了時に送る） | 同上 |
| GET | `/api/trips/{id}` | 記録の JSON | なし |
| GET | `/t/{id}` | 公開ページ（HTML） | なし |
| GET | `/t/{id}/photo.jpg` | デフォルメ後の写真 | なし |

### POST /api/trips

- `multipart/form-data`。フィールド `trip`（JSON。文字列でもファイル名付きのパートでも可）と `photo`（JPEG、任意、8 MB まで）。
- `application/json` の本文だけでも受ける（写真なし）。`photo` が 0 バイトの場合も写真なしとして扱う。
- 端末 ID は JSON の `deviceId` か `X-Device-Id` ヘッダで送る（任意、128 文字まで、英数字と `._-`）。別送の音楽ログと結び付けるのに使う。Android では `installId(context)`（`platform/android-music/InstallId.kt`。SharedPreferences 名 `tabireco`、キー `installId`）の値を送る。
- 同じ `id` の再送は上書き（メンバーや写真をあとから足すため）。写真なしで再送しても既存の写真は残る。
- 応答: 新規 `201` / 上書き `200`、本文 `{"id": "...", "url": "https://<未定>/t/{id}"}`、`Location` ヘッダに同じ URL。
- 本文を読む前に `Content-Length` を見る。無ければ `411`、9 MB（写真 8 MB ＋ JSON の分）を超えれば `413`。Android 側は chunked ではなく長さ付きで送ること。
- 失敗: `400 {"error": "<理由>"}`（検証エラー）、`401`（トークン不一致・未設定）、`411` / `413`（上記）。

### POST /api/music-logs

`application/json`。アプリの音楽機能（`feature/music` の `MusicLogUploader`。HTTP は `data/bgm` の `MusicLogApi`）が案内の終了時に送る。旅の記録とは別のタイミング・別の経路で届くので、旅の id は持たない。

```json
{ "id": "<ログの UUID>", "deviceId": "<端末 ID>", "startedAtEpochMillis": 0, "endedAtEpochMillis": 0,
  "tracks": [ ... ], "mood": [ ... ] }
```

- `tracks` と `mood` の形は下の「音楽と盛り上がり」と同じ。両方空なら 400。
- 同じ `id` は置き換え（再送しても二重にならない）。応答は新規 `201` / 置き換え `200` で `{"id": "..."}`。
- 旅との結び付け: 旅の記録に `music` が同梱されていればそれを優先。無ければ、同じ `deviceId` で時間が重なる（`started_at <= 旅の到着` かつ `ended_at >= 旅の出発`）ログをまとめて使う。複数あれば時刻順に連結。
- 公開する曲は旅の期間と重なる部分だけ、発話サンプルは出発時刻以上・到着時刻未満に絞る。同梱・別送の両方に適用し、元ログは変更しない。終了時刻がない曲は同じログの次曲、最後の曲はログの終了・到着の早い方までとして扱う。期間内のデータがなければ`music`はnull。
- 送れなかったログは端末の `filesDir/music-logs/` に残り、次の案内開始時に再送される。

### GET /api/trips/{id}

保存した記録に `deviceId`、`hasPhoto`、`photoVersion`、`createdAtEpochMillis`、`url`、`photoUrl`（写真が無ければ null。あれば `?v=` 付き）、`music`（上の結び付けで見つかったもの。無ければ null）を足して返す。

## データモデル

Android 側 `com.theacar.memory.TripMemory`（`feature/memory/`）と揃える。`POST /api/trips` の `trip` は次のキー名で受ける。

```json
{
  "id": "UUID",
  "startedAtEpochMillis": 1792278000000,
  "arrivedAtEpochMillis": 1792294200000,
  "timeZoneId": "Asia/Tokyo",
  "distanceMeters": 358000,
  "durationSeconds": 16200,
  "destinationTitle": "八雲町役場",
  "destinationCoordinate": { "latitude": 42.2563, "longitude": 140.2651 },
  "placeName": "北海道 八雲町",
  "geometry": [{ "latitude": 43.0618, "longitude": 141.3545 }, ...],
  "members": ["Honoka", "Kokoro"],
  "photoStyle": "WATERCOLOR",
  "photoLocation": { "latitude": 42.5, "longitude": 140.5 }
}
```

`TripMemory.toJson()` の形式（`startedAt` / `arrivedAt` / `destination`、座標は `[lat, lon]` 配列）も受け付ける。

検証: `id` は UUID（小文字に揃えて保存）、時刻は到着 ≧ 出発、距離・時間は 0 以上の整数、`geometry` は 20,000 点まで、`members` は 20 人・各 40 文字まで、`photoStyle` は `WATERCOLOR` / `POSTER` / `FILM` / null。`timeZoneId` が不明なら UTC に倒し、`destinationTitle` / `placeName` は 200 文字で切る（Android 側と同じく拒否しない）。

### 音楽と盛り上がり（任意の `music`）

Android の BGM 機能（`BgmMusicSession` の履歴と `CabinMoodTracker` の観測）から送る。無ければページに音楽の欄は出ない。

```json
"music": {
  "tracks": [
    { "trackId": "spotify:track:...", "title": "峠を越えて", "artist": "Ridge Riders", "genre": "Rock",
      "startedAtEpochMillis": 1792278540000, "endedAtEpochMillis": 1792278840000 }
  ],
  "mood": [
    { "atEpochMillis": 1792278000000, "speechRatio": 0.12 }
  ]
}
```

- `tracks[]`: 流れた曲。`genre` は null 可（ページでは「不明」）。`endedAtEpochMillis` が無ければ次の曲の始まり、最後の曲なら到着時刻を終わりとみなす。500 曲まで。
- `mood[]`: 1 分ごとの観測。`speechRatio` は直近 60 秒のうち発話があった秒の割合（0〜1）。`CabinMoodTracker` の lively の窓と同じ。1,440 件まで。
- ページに出すもの:
  - ジャンルごとの再生時間（分）と割合の横棒。
  - 盛り上がった時間に流れていた曲の順位（上位 5 曲）。曲が流れていた間の観測のうち `speechRatio` が 0.4 以上（lively のしきい値）だった分数で並べ、同じなら平均の割合で並べる。
  - 発話割合の折れ線（出発→到着）。0.4 の破線がしきい値。指やマウスで時刻と割合が出る。表形式の併記あり。
- **Android 側の記録**: `feature/music` の `DriveMusicLog` が、`BgmMusicSession` の再生通知（`BgmPlaybackListener`）と `CabinSpeechMonitor` の毎秒の発話判定から曲と 1 分ごとの発話割合を貯め、案内終了時に `MusicLogUploader` が `/api/music-logs` へ送る（`docs/bgm-recommendation.md` の末尾）。設定は `local.properties` の `MEMORY_WEB_URL` と `MEMORY_WEB_TOKEN`。
- ジャンルは未取得で、常にnull。ページでは「不明」と表示する。

## データの流れ

1. ナビ到着時にTripMemoryを端末へ保存する。ステッカー画面で写真や地名・メンバーを加える。
2. 保存・印刷時にAndroidが`POST /api/trips`へJSONと任意のJPEGを送る（共有トークン付き）。失敗時は次回起動・書き出し時に再送する。
3. Worker が JSON を検証し、D1 の `trips` に保存（`music` 同梱なら `music_logs` にも）、写真を R2（`{id}.jpg`）に保存。`url` を返す。
   - 音楽機能は案内の終了時に、別途 `POST /api/music-logs` で曲と発話割合を送る（D1 の `music_logs` / `music_tracks` / `music_mood`）。
4. Androidは`MEMORY_WEB_URL/t/{id}`をQRにする。送信とQRに同じ接続先を使い、送信完了前でも画像を書き出せる。未送信の間はQR先が404になる場合がある。
5. 誰かが QR を読むと `GET /t/{id}` が HTML を返す。写真は `/t/{id}/photo.jpg`、地図タイルはブラウザが OpenFreeMap から直接取る。

## Android 側との接続点

- 接続先: `local.properties`の`MEMORY_WEB_URL`にWeb版のURLを指定する。Workerで`PUBLIC_BASE_URL`を固定する場合は同じ接続先にそろえる。
- トークン: `TRIP_UPLOAD_TOKEN`（Workers の secret）。Android 側は同じ文字列を `Authorization: Bearer ...` で送る。アプリに埋め込む共有トークンなので、漏れたら `wrangler secret put` で差し替える前提。音楽機能は `MEMORY_WEB_TOKEN`（`local.properties`）として同じ値を使う。ステッカー側の送信も同じ値にする。
- 端末 ID: ステッカー側の `POST /api/trips` でも `X-Device-Id` に `installId(context)` の値を付けること。付けないと別送の音楽ログと結び付かない。
- 写真: デフォルメ後の JPEG を `photo` フィールドで送る。写真の向き・比率はステッカー上部（横 7 : 縦 6.5 程度）に `object-fit: cover` で収める。
- PHOTO SPOT の星の塗りは、写真で一番多く使われている色（ブラウザ側で写真を 64px に縮めて 32 段階に丸めて数え、最多の区分の平均色）。写真が無ければ白。縁取りは常に白。ラベルは左下・右下・左・右・左上・右上の順に候補を見て、経路線に最もかからない位置に置く（`route-svg.ts` の `placeLabel`）。
- 経路線の投影は Android の `StickerLayout.kt` の `projectRoute` と同じ決め方（経路と写真の位置で外接矩形、余白は高さの 12%、全点が同じなら中央）。距離は `StickerText.kt` と同じ整数 km。片方を変えたらもう片方も揃える。

## デプロイ手順

```sh
cd web
npm ci
npx wrangler login
npx wrangler d1 create tabireco               # 出た database_id を wrangler.jsonc の d1_databases[0].database_id に入れる
npx wrangler d1 migrations apply tabireco --remote
npx wrangler r2 bucket create tabireco-photos
npx wrangler secret put TRIP_UPLOAD_TOKEN     # アプリと共有する文字列
npm run deploy
```

表示されたデプロイ先をAndroidの`MEMORY_WEB_URL`へ設定する。`PUBLIC_BASE_URL`が空ならそのドメインを使うため、ローカルではlocalhost、本番ではデプロイ先のURLが返る。独自ドメインを使う場合だけ`PUBLIC_BASE_URL`とroutesも設定する。

## 構成

```
web/
  wrangler.jsonc       Worker の設定（D1 / R2 / vars）
  migrations/          D1 のスキーマ（wrangler d1 migrations apply で適用）
  src/index.ts         ルーティング・認証・API
  src/trip.ts          データモデルと入力検証
  src/format.ts        日付・距離・時間などの表示文字列
  src/route-svg.ts     経路線の投影（ステッカー上部の SVG 用）
  src/page.ts          公開ページの HTML
  src/store.ts         D1 / R2 の読み書き（旅・音楽ログ・写真）
  src/music.ts         音楽ログの検証と集計（ジャンル、盛り上がりの順位）
  src/charts.ts        折れ線と横棒
  public/              静的ファイル（favicon）
  test/                vitest（@cloudflare/vitest-pool-workers で本物の D1 / R2 を使う）
```

## 未対応

- 独自ドメインは未設定。デプロイ先をAndroidの`MEMORY_WEB_URL`へ設定する。
- 出発地名は未取得のため、座標を表示する。
- 「通った景色の種類」は未実装。
- ステッカーの登録は実装済み。`data/memory`の`TripMemoryApi`が`POST /api/trips`へ送信し、`TripMemoryPublisher`が失敗した記録の再送を管理する。詳しくは[旅のステッカー](memory-sticker.md#webとqr)。
