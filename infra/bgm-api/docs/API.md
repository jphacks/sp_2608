# bgm-api の使い方

複数人ドライブ向けナビアプリの「BGM推薦」バックエンドの API です。経路サーバー（`infra/valhalla`）の `bgm` コンテナとして、景観エンジンと同じ PC で動きます。
アプリ（Android Auto ナビ）から呼び出して、景観と車内の雰囲気に合う次の1曲を選びます。

## 目次

1. [基本](#1-基本)
2. [典型的な呼び出しの流れ](#2-典型的な呼び出しの流れ)
3. [エンドポイント一覧](#3-エンドポイント一覧)
4. [GET /](#get-)
5. [GET /modes](#get-modes)
6. [POST /recommend](#post-recommend)
7. [POST /link/spotify](#post-linkspotify)
8. [POST /annotate](#post-annotate)
9. [値の一覧（景観・車内・6軸）](#9-値の一覧)
10. [エラーと制限](#10-エラーと制限)
11. [景観エンジンとの連携](#11-景観エンジンとの連携)

---

## 1. 基本

### ベースURL

| 環境 | URL |
| --- | --- |
| 本番（公開入口、Bearer 認証） | `{VALHALLA_URL}/bgm`（例：`https://routing.example.com/bgm`） |
| PC 内（認証なしのローカル入口） | `http://127.0.0.1:8002/bgm` |
| Android エミュレータから PC | `http://10.0.2.2:8002/bgm` |

経路サーバーのゲートウェイが `/bgm` を外してから渡すので、この文書のパス（`/recommend` など）は `/bgm/recommend` として呼びます。起動は `manage.py up`（[環境構築](../../../docs/environment-setup.md)）。以前の Cloudflare Workers 版（`bgm-api.ssk-st0205.workers.dev`）は廃止しました。

リクエストとレスポンスの本文はすべて JSON（`Content-Type: application/json`）です。

### 認証

公開入口では、すべてのリクエストに経路 API と同じトークンが必要です（`/modes` も含む）。

```
Authorization: Bearer <ROUTING_TOKEN>
```

- 認証はゲートウェイ（nginx）が行い、bgm-api には `Authorization` を渡しません。トークンが無い・違うときはゲートウェイが 401 を返します
- PC 内のローカル入口（8002）は認証なしです。外部へ公開しません
- 例外として、同乗者のリクエスト画面（`/party/...`）はトークンなしで届きます。QR コードから開くブラウザに合言葉を持たせられないためです。推測できない code（22文字）を知っている人だけが使え、案内の終わりか12時間で使えなくなります（[同乗者のリクエスト](#同乗者のリクエストparty)）
- `APP_TOKEN` を設定すると bgm-api 自身も認証します（`GET /`・`GET /modes`・`/party/...` を除く）。ゲートウェイの後ろでは通常設定しません
- Spotify・Gemini の資格情報は `infra/valhalla/.secrets/bgm.env` に置きます（[環境構築 3章](../../../docs/environment-setup.md#景観bgmの資格情報任意)）。Spotify が未設定のとき `/link/spotify` は `503 {"error":"spotify_not_configured"}` を返します

### 曲のID（track_id）

| 形式 | 使う場面 |
| --- | --- |
| `isrc:XXXXXXXXXXXX` | **基本はこれ**。全曲カタログの曲はすべてこの形式。同乗者のプレイリストの曲も、ISRC が分かれば（Spotify の曲データの `external_ids.isrc`）この形式で送ると、カタログの同じ曲と同一として扱われる |
| `local:曲名\|アーティスト名` | ISRC が分からない曲。`/annotate` で `track_id` を省略すると自動でこの形式になる（Spotify の照合はできない） |

Spotify の曲ID（`3L7ISJ...`）は track_id には使いません。再生する直前に `POST /link/spotify` で ISRC から変換します。
再生アプリを Spotify 以外に替えても、track_id（ISRC）はそのまま使えます。

---

## 2. 典型的な呼び出しの流れ

Androidの音楽画面「みんなのプレイリスト」から、表示名とSpotifyプレイリストURLを入力できる。
現在ログイン中のアカウントが所有・共同編集できるリストを取得し、ISRCで重複をまとめる。
曲名・アーティスト・ISRC・曲長を既存の`/annotate`へ50曲ずつ送り、全曲の準備成功後に`pool`へ反映する。
Spotifyの認証トークン・プレイリストURLはこのAPIへ送らない。
取込失敗では以前の入力を保持する。初回の読取権限追加にはSpotifyへの再ログインが必要。

Androidの「好み＋新しい曲との出会い」は`blend`、「プレイリスト内だけ」は`playlist`に対応する。
後者は既存の`include_catalog=false`を明示して、インストによるカタログ補完も無効にする。
API一般の`playlist`既定動作（カタログ補完あり）は互換性のため維持する。
取込結果・モードは音楽画面とナビ開始で共有し、プロセス終了時に消える。
詳細は[アプリ側の取込仕様](../../../docs/bgm-recommendation.md#プレイリストを入力する)を参照。

```
アプリ起動
  └─ GET /modes ……………………… モードの選択肢を画面に出す

乗車（同乗者がプレイリストを選んだあと）※ blend・playlist モードのとき
  └─ POST /annotate ………………… カタログに無いプレイリストの曲を注釈しておく（カタログにある曲は即座に返る）

曲の切れ目ごと（今の曲が終わる30秒ほど前）
  ├─ POST /recommend ……………… 次の1曲を選ぶ（景観エンジンのタイムラインと今の位置、または景観の値・車内の状態・履歴・プレイリストを送る）
  ├─ POST /link/spotify ………… 選ばれた曲の Spotify の曲IDを得る
  │     └─ not_found なら、candidates の次の曲で /link/spotify をやり直す
  └─ 再生して、history に追加する（次の /recommend で送る）
```

- 推薦の計算はサーバーで行いますが、計算部分（`src/engine.ts`）は外部に依存しない純粋関数なので、将来は端末側に移植する予定です
- 音声はサーバーに送りません。車内の状態は端末で計算した4つの状態（`cabin`）だけを送ります

---

## 3. エンドポイント一覧

| メソッドとパス | 認証 | 用途 | 外部APIの消費 |
| --- | --- | --- | --- |
| `GET /` | 不要 | 死活確認 | なし |
| `GET /modes` | 不要 | 選曲モードの一覧と表示名 | なし |
| `POST /recommend` | 必要 | 次の1曲を選ぶ | なし（D1 の読み取りのみ） |
| `POST /link/spotify` | 必要 | track_id → Spotify の曲ID | 未照合の曲だけ Spotify Search を1曲1回 |
| `POST /annotate` | 必要 | 曲の6軸を注釈する | 未注釈の曲だけ Gemini を80曲あたり1回 |
| `POST /parties` | 必要 | 同乗者のリクエストの受付を作る（車の端末） | なし |
| `POST /parties/:code/sync` | 必要 | 今の曲と予約の列を渡し、届いたリクエストを受け取る（車の端末） | なし |
| `POST /parties/:code/close` | 必要 | 受付を閉じる（車の端末） | なし |
| `GET /party/:code` | 不要（code） | 同乗者のリクエスト画面（HTML。`app.js`・`app.css` も同じ場所から配る） | なし |
| `GET /party/:code/state` | 不要（code） | 今の曲と予約の列 | なし |
| `GET /party/:code/search` | 不要（code） | 曲名・アーティストで検索 | 同じ言葉は10分間覚えておき、それ以外は Spotify Search を1回 |
| `POST /party/:code/requests` | 不要（code） | 曲をリクエストする | 検索結果に無い曲だけ Spotify の曲情報を1回 |

---

## GET /

死活確認です。`bgm-api ok`（テキスト）を返します。

---

## GET /modes

選曲モードの一覧を返します。表示名はサーバー側（`src/modes.ts` の `MODE_LABELS`）で変えられるので、アプリに文言を埋め込まず、ここから取ってください。

### レスポンス

```json
{
  "default": "blend",
  "modes": [
    { "id": "discover", "label": "すべての曲から", "shortLabel": "すべての曲から", "description": "景色と車内の雰囲気だけで選びます" },
    { "id": "blend", "label": "すべての曲から（好みを反映）", "shortLabel": "すべての曲から＋好み", "description": "みんなのプレイリストに近い曲を優先します" },
    { "id": "playlist", "label": "みんなのプレイリストから", "shortLabel": "みんなのプレイリストから", "description": "同乗者のプレイリストの曲だけを流します" }
  ]
}
```

| 項目 | 内容 |
| --- | --- |
| `default` | 既定のモード（`/recommend` で `mode` を省略したときに使われる） |
| `modes[].id` | `/recommend` の `mode` に渡す識別子 |
| `modes[].label` | 画面に出す表示名 |
| `modes[].shortLabel` | 画面の幅が足りないときの短縮形 |
| `modes[].description` | 補足の一言 |

### モードの違い

| id | 候補 | プレイリストの扱い | 知名度の加点 |
| --- | --- | --- | --- |
| `discover` | 全曲カタログ | 使わない（所有者の情報も無視） | 弱め |
| `blend` | 全曲カタログ＋プレイリスト | リストにある曲・同じアーティストの曲を加点。加点なしの曲も5曲に1曲くらいは選ばれる | 標準 |
| `playlist` | プレイリストの曲だけ | ―（運転負荷が高い区間でリストの曲が上限を満たさないときだけ、カタログのインストで補う） | なし |

---

## POST /recommend

景観・車内の状態・履歴・プレイリストから、次の1曲を選びます。

入力はDBへ問い合わせる前に検証します。不正な型・範囲・配列要素は`400 {"error":"invalid フィールド名"}`です。省略可能な値はフィールド自体を省略し、`null`は送らないでください（区間の`openness`・`recommended_rank`を除く）。

| 項目 | 範囲・上限 |
| --- | --- |
| `members` | 1〜20名、idは重複不可。idは128文字、nameは200文字まで |
| `pool` / `history` / `recent_track_ids` | 最大5,000曲 / 1,000件 / 5,000件。曲IDは500文字まで |
| `pool[].owners` | 最大20名、membersに含まれるid |
| `scene.features` / `previous_target` | 既定の特徴・6軸のみ。値と信頼度は0〜1 |
| `scene.hour` / `top_k` / `seed` | 0〜23 / 整数1〜30 / 整数0〜4,294,967,295 |
| `route.timeline` / `route.reveals` | それぞれ最大20,000件 |
| 経路上の距離 / `next_duration_ms` | 0〜2,000,000m / 1〜3,600,000ms。区間の終点は始点より後 |

`/annotate`も曲名・アーティスト名・曲ID（各500文字まで）と正の曲長を検査し、不正な入力は400を返します。`/link/spotify`の`track_ids`は空でない文字列の配列です。

### リクエスト

```json
{
  "mode": "blend",
  "members": [
    { "id": "a", "name": "A" },
    { "id": "b", "name": "B" }
  ],
  "pool": [
    { "track_id": "isrc:JPPO02100710", "owners": ["a"] }
  ],
  "scene": {
    "features": { "sea_proximity": { "v": 0.9, "q": 1 }, "openness": 0.8 },
    "load": "normal",
    "curvy": false,
    "highlight": false,
    "hour": 14
  },
  "cabin": "normal",
  "history": [
    { "track_id": "isrc:JPPO02200420", "owner_id": "a" }
  ],
  "recent_track_ids": [],
  "seed": 1
}
```

| 項目 | 必須 | 型 | 内容 |
| --- | --- | --- | --- |
| `members` | ○ | `{id, name}[]` | 同乗者。1人以上。`name` は演出の一言（「Aさんのリストから」）に使う |
| `route` | △ | object | 景観エンジンのルート ID（またはタイムライン）と今の位置。次の曲が流れる区間の景観を、サーバーが計算する（[11章](#11-景観エンジンとの連携)）。`scene` か `route` のどちらかが必須 |
| `scene` | △ | object | 景観の値と運転条件（下の表）。`route` と一緒に送ると、`scene` の値が優先される（`load`・`curvy` などアプリ側で分かる値を足す使い方） |
| `mode` | | `"discover"` \| `"blend"` \| `"playlist"` | 省略時は `GET /modes` の `default`（今は `blend`）。不正な値は 400 |
| `pool` | | `{track_id, owners}[]` | 同乗者のプレイリストの曲と、その曲を持っている人の `members[].id`。**省略すると開発用モード**（下の注意） |
| `include_catalog` | | boolean | 既定 `true`。`false` にすると全曲カタログを候補に足さない |
| `cabin` | | `"lively"` \| `"normal"` \| `"quiet"` \| `"resting"` | 車内の状態。省略時 `normal`（[値の一覧](#車内の状態-cabin)） |
| `history` | | `{track_id, owner_id?, artist?}[]` | **このドライブで流した曲**（古い順）。ここにある曲は候補から外れる。`owner_id` はその曲を選んだときの `chosen.owner_id`（所有者の偏りの計算に使う） |
| `recent_track_ids` | | string[] | 前回までのドライブで流した曲。候補には残るが減点される |
| `previous_target` | | object | 前の曲を選んだときの `plan.target`。曲調が急に飛ばないよう、軸ごとに 0.25 までしか変えない。初回は省略 |
| `top_k` | | number | 候補の数。既定 5 |
| `seed` | | number | 乱数のシード。同じ入力とシードなら結果が同じ。省略すると毎回変わる |

#### scene

| 項目 | 型 | 内容 |
| --- | --- | --- |
| `features` | object | 景観の特徴。キーは [景観の特徴](#景観の特徴-scenefeatures)。値は `0〜1` の数値か `{ "v": 0〜1, "q": 0〜1 }`（`q` は信頼度。データが無いときは 0、省略時 1） |
| `load` | `"normal"` \| `"high"` | 操作の負荷（交差点が密、右左折が連続）。`high` で energy ≤ 0.6 |
| `curvy` | boolean | カーブが多い・急勾配。energy ≤ 0.65 |
| `highlight` | boolean | 名道区間。知名度の加点を強め、energy の上限を少し緩める |
| `hour` | 0〜23 | 時刻。夜（19時以降）は energy を下げ、朝（9時まで）は tempo を上げる |

> **開発用モード**：`pool` を省略すると、カタログ以外の曲（手元で注釈した曲）をメンバーに順番に割り当てて、プレイリストの代わりにします。アプリからは必ず `pool` を送ってください（プレイリストが無いときは `[]`）。

> **プレイリストは無くてもよい**：`pool: []` でも、discover と blend は全曲カタログから選びます（blend はプレイリストの加点が無いだけ）。playlist モードだけは選べる曲が無いので `chosen: null` になります。その場合は discover か blend に切り替えてください。
> API は前回の呼び出しを覚えていないので、プレイリストはドライブの途中からでも、次の `/recommend` の `pool` に足せば反映されます（途中参加・途中離脱も同じ）。

### レスポンス

```json
{
  "pool_size": 2057,
  "sample_size": 24,
  "missing": [],
  "mode": "blend",
  "volume": 0.6,
  "cap_mode": "none",
  "fallback": null,
  "chosen": {
    "track_id": "isrc:JPPO02200420",
    "title": "まつり",
    "artist": "藤井 風",
    "duration_ms": 225000,
    "owner_id": null,
    "message": "海が近いので、みんなのリストにあるアーティストの、開放感のある曲を流します"
  },
  "candidates": [
    {
      "track_id": "isrc:JPPO02100710", "title": "きらり", "artist": "藤井 風", "owners": ["a"],
      "score": 0.049, "prob": 0.09, "distance": 0.138, "over_cap": 0, "over_soft": 0,
      "recent": 0, "same_artist": 0, "owner_bias": 0, "shared": 0,
      "popularity": 0.87, "pref": "track", "conf_penalty": 0
    }
  ],
  "plan": {
    "target": { "energy": 0.59, "valence": 0.72, "tempo": 0.545, "acoustic": 0.5, "spacious": 0.835, "vocal": 0.5 },
    "weights": { "energy": 1, "valence": 1.5, "tempo": 0.5, "acoustic": 1, "spacious": 1.5, "vocal": 1 },
    "caps": {},
    "softCaps": {},
    "volume": 0.6,
    "famBoost": 1,
    "reasons": ["海が近い", "視界が開けている"]
  },
  "pool_ranges": { "energy": 0.8, "valence": 0.77, "tempo": 1, "acoustic": 0.96, "spacious": 0.72, "vocal": 0.96 },
  "effective_weights": { "energy": 1, "valence": 1.5, "tempo": 0.5, "acoustic": 1, "spacious": 1.5, "vocal": 1 }
}
```

アプリが主に使うのは `chosen`・`volume`・`candidates` です。

| 項目 | 内容 |
| --- | --- |
| `chosen` | 選ばれた曲。候補が1曲も無いときは `null`（`note` に理由） |
| `chosen.track_id` | `/link/spotify` に渡す ID。再生したら `history` に追加する |
| `chosen.duration_ms` | 曲の長さ（分からなければ `null`） |
| `chosen.owner_id` | 演出に名前を出した所有者（`members[].id`）。所有者がいない曲は `null`。`history` の `owner_id` にそのまま入れる |
| `chosen.message` | 演出の一言（例：「海が近いので、Bさんのリストから爽やかな曲を流します」） |
| `volume` | 推奨音量（0.2〜1）。盛り上がり・休息中・運転負荷・下の `fallback` で下がる |
| `candidates` | 先頭が選んだ曲、そのあとがスコア順の次点（合わせて最大 `top_k` 曲）。Spotify に無かったときの次点に使う |
| `candidates[].prob` | その曲がくじ引きで選ばれる確率。くじ引きの対象外なら 0 |
| `sample_size` | くじ引きの対象になった曲数。最高スコアから 0.1 以内の曲を最大 30 曲まで（`src/engine.ts` の `SAMPLE`）。同じ景色でも毎回同じ数曲にならないよう、`top_k` とは別に広く取る |
| `candidates[].pref` | blend の加点：`"track"`（リストにある曲）、`"artist"`（同じアーティスト）、`null` |
| `mode` | 実際に使われたモード |
| `cap_mode` | 安全のための上限の扱い：`none`（上限なし）、`absolute`（上限を満たす曲から選んだ）、`catalog_fill`（playlist モードでカタログのインストから補った）、`nearest`（上限を満たす曲が無く、はみ出しが小さい曲で補った） |
| `fallback` | ターゲットに近い曲が無く、直前の曲調に寄せたときに `{min_distance, ref}`。このとき音量を 0.1 下げている。通常は `null` |
| `pool_size` / `missing` | 候補の曲数と、DBに無かった（未注釈の）track_id。`missing` があれば `/annotate` を呼ぶ |
| `route_status` | `route` を送ったときだけ。`given`（タイムラインを受け取った）、`resolved`（ルート ID から取得した）、`unavailable`（ルート ID から取得できず、景観なしで選んだ）。送らなければ `null` |
| `route_window` | `route` を送ったときだけ。次の曲が流れる区間（`window`）、景観の評価済みの割合（`coverage`）、カテゴリ別の占有率（`shares`）、海が見え始める地点（`reveal`）、実際に使った `scene`。送らなければ `null` |
| `plan.target` | 選曲のターゲット（6軸）。次の `/recommend` の `previous_target` に入れる |
| `plan.smoothed` | `previous_target` によって変化を抑えた軸 |
| `plan` / `pool_ranges` / `effective_weights` | 選曲の根拠（ターゲット・重み・上限など）。デバッグと検証用 |

#### candidates の項目（スコアの内訳）

| 項目 | 内容 |
| --- | --- |
| `score` | 総合スコア（高いほど良い）＝ −距離 ＋ 加点 − 減点 |
| `distance` | ターゲットとの距離（0〜1、小さいほど合っている） |
| `over_cap` / `over_soft` | energy の上限・vocal のソフトな上限からのはみ出し |
| `recent` / `same_artist` | 前回のドライブで流した（1/0）、直近3曲と同じアーティスト（1/0） |
| `owner_bias` / `shared` | 所有者の偏り、複数人のリストにある度合い |
| `popularity` | 知名度（0〜1） |
| `conf_penalty` | 注釈の信頼度が低い分の減点 |

---

## POST /link/spotify

選ばれた曲の Spotify の曲IDを返します。**再生する直前に、選ばれた曲だけ**を照合する前提です。
一度照合した曲はDBに保存され、以降は Spotify に問い合わせずに返します（全ユーザーで共有）。

### リクエスト

```json
{ "track_ids": ["isrc:JPPO02100710"] }
```

| 項目 | 必須 | 内容 |
| --- | --- | --- |
| `track_ids` | ○ | 1〜5件。`isrc:` 形式、または ISRC がDBに入っている曲の track_id |

### レスポンス

```json
{
  "links": [
    {
      "track_id": "isrc:JPPO02100710",
      "status": "found",
      "spotify_id": "3L7ISJTvKx56uhsF28aJ4p",
      "spotify_uri": "spotify:track:3L7ISJTvKx56uhsF28aJ4p"
    }
  ],
  "retry_after": null
}
```

| `status` | 意味 | アプリの対応 |
| --- | --- | --- |
| `found` | 見つかった | `spotify_uri` を再生する |
| `not_found` | Spotify（日本のマーケット）に無い | `candidates` の次の曲で照合し直す |
| `pending` | Spotify のレート制限でまだ照合できていない | `retry_after` 秒待ってから再度呼ぶ。急ぐなら次の候補へ |
| `no_isrc` | ISRC が分からず照合できない | 次の候補へ |

`retry_after` はレート制限に当たったときだけ数値（秒）が入ります。

---

## POST /annotate

曲名とアーティスト名から、6軸の特徴量（Gemini で推定）を返します。
全曲カタログの曲は注釈済みなので、**カタログに無いプレイリストの曲を、乗車時に一度だけ**注釈する用途です。結果はDBに保存され、全ユーザーで共有されます。

### リクエスト

```json
{
  "tracks": [
    { "track_id": "isrc:JPPO02100710", "title": "きらり", "artist": "藤井 風", "duration_ms": 231000 }
  ]
}
```

| 項目 | 必須 | 内容 |
| --- | --- | --- |
| `tracks` | ○ | 1〜500曲 |
| `tracks[].title` / `artist` | ○ | 曲名・アーティスト名 |
| `tracks[].track_id` | | 推奨：`isrc:` 形式。省略すると `local:曲名\|アーティスト名` |
| `tracks[].duration_ms` | | 曲の長さ |
| `force` | | `true` で注釈済みでも注釈し直す（Gemini を消費するので通常は使わない） |

### レスポンス

```json
{
  "model": "gemini-3.8-flash",
  "cached": 1,
  "annotated": 0,
  "errors": [],
  "tracks": [
    {
      "track_id": "isrc:JPPO02100710", "title": "きらり", "artist": "藤井 風", "duration_ms": 231000,
      "energy": 0.78, "valence": 0.75, "tempo": 0.475, "acoustic": 0.22, "spacious": 0.55, "vocal": 0.85,
      "conf": 0.95, "popularity": 0.87, "jp_familiarity": 0.85, "isrc": "JPPO02100710", "catalog_group": "jp",
      "tags": ["pop", "dance", "bright", "summer"], "bpm": 117, "cached": true
    }
  ]
}
```

| 項目 | 内容 |
| --- | --- |
| `cached` / `annotated` | DBにあった曲数 / 今回 Gemini で注釈した曲数 |
| `errors` | Gemini のエラー（レート制限など）。エラーになった曲は `tracks` に `"error": "not annotated"` で返る。あとでもう一度呼べば続きから注釈する |
| `tracks[]` | 6軸（[値の一覧](#曲の6軸)）、`conf`（信頼度）、`jp_familiarity`（日本での認知度）、`popularity`（知名度）、`tags`、`bpm` など |

---

## 同乗者のリクエスト（party）

車の端末が案内ごとに受付（party）を作り、`{VALHALLA_URL}/party/{code}` を QR コードで同乗者に見せます。同乗者はログインせずに曲を探し、「次に予約」か「今すぐ流す」を送ります。車の端末は5秒ごとに `sync` で受け取り、アプリの予約の列に入れて曲の終わりに流します（「今すぐ」はすぐ流します）。Spotify のキューは使いません（[景観BGMの推薦](../../../docs/bgm-recommendation.md)）。

### 車の端末（トークンが必要）

`POST /parties` → `201 {"code": "<22文字>", "expires_at": 1792277723863}`。作るときに、終わってから1日たった受付とリクエストを消します。

`POST /parties/:code/sync`

```json
{
  "after": 0,
  "now_playing": { "title": "海の曲", "artist": "A", "kind": "bgm" },
  "queue": [{ "title": "次の曲", "artist": "B", "by": "たろう", "kind": "request" }]
}
```

- `after`：前回受け取ったリクエストの最大の `id`。それより後のものだけを返す（1回50件まで）
- `now_playing`・`queue`：同乗者の画面に出す値。`title` が無い項目は捨て、文字数を切り詰めて保存する。`queue` は30件まで
- レスポンス：`{"open": true, "requests": [{"id": 7, "guest_name": "たろう", "uri": "spotify:track:…", "title": "…", "artist": "…", "duration_ms": 180000, "image_url": "https://i.scdn.co/…", "mode": "now" | "next", "created_at": …}]}`。`open` が false なら受付は終わっている
- 知らない code は 404

`POST /parties/:code/close` → `{"closed": true}`。同乗者の画面は「終わりました」になり、検索・リクエストは 410 になります。

### 同乗者のブラウザ（トークン不要）

| 呼び出し | 内容 |
| --- | --- |
| `GET /party/:code/state` | `{"open": true, "now_playing": {...} \| null, "queue": [...], "updated_at": …}`。終わった受付・知らない code は `{"open": false}` |
| `GET /party/:code/search?q=…` | `{"tracks": [{"uri", "title", "artist", "duration_ms", "image_url"}]}`（10曲まで、市場は JP） |
| `POST /party/:code/requests` | 本文 `{"guest_id": "<8〜64文字>", "name": "<20文字まで>", "uri": "spotify:track:…", "mode": "now" \| "next"}` → `201 {"id", "title", "artist", "mode"}` |

- 曲名などはブラウザから受け取らず、サーバーが Spotify で確かめた値を使います（存在しない曲は 404 `unknown_track`）
- 1人（`guest_id`）は1分に3曲まで、1つの受付は10分に40曲までです。超えると 429 `too_many`
- 画面は外部のスクリプトを読み込まず、CSP で自分の配信元と Spotify のジャケット画像（`i.scdn.co`）だけを許します。曲名は `textContent` で表示します
- ゲートウェイでは `/party/` だけトークンなしで通し、接続元（Cloudflare の `CF-Connecting-IP`）ごとに毎分60回、全体で毎分300回に絞ります

## 9. 値の一覧

<a id="景観の特徴-scenefeatures"></a>

### 景観の特徴（scene.features）

| キー | 意味 |
| --- | --- |
| `sea_proximity` | 海の近さ |
| `sea_visibility` | 海が見えるか（確認済みのときだけ。`q` を 0.7 以上に） |
| `openness` | 視界の開け具合 |
| `forest` | 森の中・森の縁 |
| `nature` | 自然の多さ |
| `rural` | 田園の広がり |
| `urban` | 街の度合い |
| `water` | 湖・川・渓谷 |

<a id="車内の状態-cabin"></a>

### 車内の状態（cabin）

端末でマイクの音から計算し、状態だけを送ります（音声は送らない）。

| 値 | 目安 | 選曲への影響 |
| --- | --- | --- |
| `lively` | 会話が盛り上がっている | ボーカル控えめ、音量 −0.15 |
| `normal` | 通常 | なし |
| `quiet` | 発話がほとんど無い状態が3分以上 | 明るめ、みんなが知っている曲を優先 |
| `resting` | 静かな状態が5分以上で、いびきか長時間運転 | 落ち着いた曲（energy ≤ 0.4）、音量 −0.2。画面に「寝ている」とは出さない |

### 曲の6軸

| 軸 | 0 に近い | 1 に近い |
| --- | --- | --- |
| `energy` | 静か | 激しい・高揚 |
| `valence` | 切ない・暗い | 明るい |
| `tempo` | 遅い（BPM 60 以下） | 速い（BPM 180 以上）。(BPM − 60) / 120 |
| `acoustic` | 電子音・打ち込み | 生楽器・弾き語り |
| `spacious` | 密な音像 | 余白・広がり |
| `vocal` | インスト | ボーカルが前面 |

---

## 10. エラーと制限

### エラー

| ステータス | 本文 | 原因 |
| --- | --- | --- |
| 400 | `{"error":"scene (or route) and members are required"}` | `/recommend` の必須項目が無い |
| 400 | `{"error":"route.position_m and route.timeline (or route.route_id) are required"}` | `route` の形が正しくない |
| 400 | `{"error":"mode must be one of discover, blend, playlist"}` | `mode` が不正 |
| 400 | `{"error":"track_ids is required"}` / `{"error":"max 5 track_ids per request"}` | `/link/spotify` の件数 |
| 400 | `{"error":"tracks is required"}` / `{"error":"max 500 tracks per request"}` | `/annotate` の件数 |
| 401 | `{"error":"unauthorized"}` | トークンが無い・違う |
| 503 | `{"error":"spotify_not_configured"}` | `/link/spotify` で Spotify の資格情報が未設定（`bgm.env`） |
| 404 | `{"error":"not_found"}` / `{"error":"unknown_track"}` | 知らない受付の `sync`、Spotify に無い曲のリクエスト |
| 410 | `{"error":"closed"}` | 終わった受付への検索・リクエスト |
| 429 | `{"error":"too_many"}` | 同乗者のリクエストの回数制限 |
| 503 | `{"error":"busy","retry_after":30}` | 同乗者の検索・リクエストで Spotify のレート制限に当たった |

### 制限

| 対象 | 制限 | 備考 |
| --- | --- | --- |
| `/link/spotify` | 1回5曲まで | Spotify の開発モードはレート制限があるため、次の曲の候補だけを照合する |
| `/annotate` | 1回500曲まで | Gemini を使う（前払いクレジットを消費）。未注釈の曲だけ80曲ずつ送る |
| Spotify 開発モード | ユーザー5人まで、アプリ所有者は Premium が必要 | 2026年2月の変更 |

---

## 11. 景観エンジンとの連携

景観ルートエンジン（scenic-routing v2）が返す経路の `timeline[]` から、次の曲が流れる区間の景観を計算できます。
変換は `src/scenic.ts` の純粋関数で、端末（Kotlin）にそのまま移植できます。端末で変換して `scene` だけを送る形にしても、選曲の結果は同じです。

### ルート ID で指定する（PC 版）

景観エンジンと同じ PC で動かしている場合（`infra/valhalla` の `bgm` コンテナ）は、タイムラインを送らずにルート ID だけを送れます。

```json
{ "route_id": "route_cedebfa32cfe76af7ebe738b85aecd3a", "position_m": 8000, "next_duration_ms": 240000 }
```

- サーバーが景観エンジンの `GET /routes/{route_id}/evidence` から内部接続でタイムラインを取り、下の「route の形」と同じ項目だけを取り出す。座標・名道の固有名・物理道路IDは保存しない
- 景観エンジンは検索結果を1時間で消すので、最初に取ったものを `route_timeline` に24時間保持し、ドライブの間はそれを使う
- 取得できなかったときは、`route_status: "unavailable"` を返し、景観なし（`scene` を送っていればその値）で選曲する。400 にはしない
- `route_id` は `route_` + 小文字の16進32桁。Workers 版は景観エンジンに届かないので、常に `unavailable` になる

### route の形（タイムラインを直接送る）

```json
{
  "position_m": 8000,
  "next_duration_ms": 240000,
  "timeline": [
    { "start_m": 3000, "end_m": 9000, "category": "forest", "confidence": 0.3, "arrival_time": "2026-10-17T15:04:00+09:00" },
    { "start_m": 9400, "end_m": 20000, "category": "sea", "confidence": 0.3, "recommended_rank": "signature",
      "openness": null, "visibility_verified": false, "arrival_time": "2026-10-17T15:10:24+09:00" }
  ],
  "reveals": [{ "start_m": 9400 }]
}
```

| 項目 | 必須 | 景観エンジンの元の値 |
| --- | --- | --- |
| `position_m` | ○ | 今の位置（経路始点からの距離 m）。次の曲はここから流れ始める想定 |
| `next_duration_ms` | | 次の曲の長さ。省略時 3分30秒 |
| `timeline[].start_m` / `end_m` / `category` | ○ | `timeline[]` の同名の項目 |
| `timeline[].confidence` | | `timeline[].confidence`。`0` または `category: "unknown"` は未評価として扱う |
| `timeline[].arrival_time` | | `timeline[].arrival_time`。速度（窓の長さ）と時間帯の計算に使う |
| `timeline[].recommended_rank` | | `recommended_roads[].rank` のうち最上位の1つ（`signature` / `panorama` / `recommended` / `scenic`） |
| `timeline[].openness` / `visibility_verified` | | 同名の項目（補助データがあるときだけ値が入る） |
| `reveals[].start_m` | | `evaluation.reveals[].start_m` |

**送らない項目**：`recommended_roads[].name`（名道の固有名）と `physical_position`（物理道路ID）は送らないでください。座標を含まなくても経路が特定できるためです。サーバーはこれらを使いません。

### 変換のしかた

1. **窓**：今の位置から、次の曲の長さぶん先まで。速度は今の位置をはさむ2地点の `arrival_time` の差から出す（無ければ時速40km）
2. **占有率**：窓と重なる長さでカテゴリを集計し、評価済みの長さ（`unknown` 以外）で割る。`unclassified`（トンネルなど）は分母に含める
3. **対応**：

| 景観エンジン | BGM の特徴（`scene.features`） | 信頼度 q |
| --- | --- | --- |
| `sea` | `sea_proximity` | 1 × 評価済みの割合 |
| `forest` | `forest` | 1 × 評価済みの割合 |
| `open_nature` | `nature` | 1 × 評価済みの割合 |
| `inland_water` | `water` | ブロックの `confidence` × 評価済みの割合 |
| `lake` / `river` / `valley` | `water`（`inland_water` と長さを足し合わせる） | 1 × 評価済みの割合 |
| `urban` | `urban`（`townscape` と長さを足し合わせる） | 1 × 評価済みの割合 |
| `farmland` | `rural` | 同上 |
| `townscape` | `urban` | 同上 |
| `sea` かつ `visibility_verified: true` | `sea_visibility` | 評価済みの割合 |
| `openness`（null 以外） | `openness`（長さで加重平均） | 値がある長さの割合 |

4. **名道**：窓の3割以上が `recommended_rank` = `signature` / `panorama` なら `highlight: true`
5. **時間帯**：窓の始まりの `arrival_time` の「時」（現地時刻）を `hour` にする。今の時刻ではなく、その曲が流れる時間帯で判断する
6. **reveal**：窓の中に海が見え始める地点があれば、`route_window.reveal` に何秒後かを返す（曲の切り替えを合わせるため）

`confidence` は補助データの有無で決まる定数（0.3 / 0.6）なので、OSM から計算される3カテゴリには使いません（そのまま掛けると景観の効果が3割に弱まる）。

### 景観エンジン側の制約（2026-10-17 時点）

| 項目 | 状況 |
| --- | --- |
| 既定構成で値が入るカテゴリ | `sea`・`forest`・`open_nature` の3つ。`inland_water`・`farmland`・`townscape`・`openness`・`sea_visibility`・reveal は補助データ（DEM・土地被覆）が必要 |
| 夜の海 | 景観エンジンが `sea` と `open_nature` に明るさを掛けるため、夜は `sea` にならない（BGM 側では打ち消せない。打ち消すにはタイムラインに明るさの値が必要） |
| 運転負荷・カーブ | 区間ごとの値が応答に無い。アプリ側で分かる場合は `scene.load` / `scene.curvy` で送る |
| 走行済みの道路 | 履歴を送って探索すると、既に走った道路の景観は 0（`unclassified`）になる |
| Kotlin 側 | `ScenicTimelineBlock` に `openness`・`visibility_verified`・`arrival_time` を残す必要がある（今は読み捨てている） |

## 関連ファイル

| ファイル | 内容 |
| --- | --- |
| `src/index.ts` | ルーティング（この文書の対象） |
| `src/engine.ts` | 推薦ロジック（ターゲットの計算・距離・スコア） |
| `src/modes.ts` | モードの定義と表示名 |
| `src/scenic.ts` | 景観エンジンのタイムライン → `scene` の変換（先読みの窓） |
| `src/annotate.ts` / `src/spotify.ts` | Gemini・Spotify の呼び出し |
| `scripts/catalog.ts` / `catalog/sources.json` | 全曲カタログの作成 |
| [景観BGMの設計](../../../docs/bgm-recommendation.md) | アプリ連携・推薦ロジック・未対応範囲 |
