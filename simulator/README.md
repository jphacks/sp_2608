# ドライブシミュレータ

ハンコン（ロジクールの G29）とペダルで、北海道の景色のよい道を走るシミュレータです。走った位置をスマホの旅れこ（Debug ビルド）へ現在地として送るので、ナビの案内が運転に合わせて進みます。JPHACKS Award Day（2026-11-15）の EXPO で、景色とナビを一緒に見せるために作りました。

- **景色:** Google ストリートビューの写真を自前で描きます。写真を「地面」と「周りの円筒（半径 80m）」という仮の形に貼り、カメラを連続して前へ動かすので、写真と写真の間も前へ進んで見えます。
  - 次の写真には、近づくにつれて、ゆっくり始まりゆっくり終わる形で重ねて替えます。
  - 次の写真を読み終えるまでは、入れ替えを始めません。
  - 交差点で横道の写真を拾わないよう、道の向きに沿って撮った写真だけを使います。
  - 4枚先（約70m、時速 60km で約4秒分）まで先読みし、車に近い写真から順に読み込みます。先の写真は、その地点の道の向きの分を読みます（カーブの先でずれないように）。
  - 2011 年ごろの古い写真（美瑛などに残っている）は大きさが小さく、タイルの並びが違うので、写真ごとに並びを合わせます。
- **車:** 制限速度は時速 60km、ブレーキは実車の約 3 倍効きます（体験会で止まりやすいように）。
- **道:** OSM の道路網の上だけを走ります。交差点では、ハンドルを切った方の道へ進みます。一方通行の逆には入りません。
- **動く場所:** ブラウザ（Chrome）で動きます。Mac でも Windows でも同じです。
- **会場が東京でも:** 北海道を走っている現在地をスマホへ渡せます。

```
ハンコン・ペダル ─ Chrome（このシミュレータ）─ 開発サーバーの中継（TCP 8788）─ スマホの旅れこ（Debug）
                   ストリートビュー＋OSM の道                                 ナビの案内・車載画面
```

## 準備

### 1. 部品を入れる

Node.js 22 で確かめています。

```sh
cd simulator
npm install
```

### 2. Google の API キー

キーは旅れこの GCP プロジェクト（`tabireco-app-2608`）で作り、`.env.local` に入れます。`.env.local` はコミットしません。

キーは Map Tiles API（ストリートビューの写真を自前の表示に使うための API）と、写真の有無を調べる Street View の API だけに使え、`http://localhost:5173` からだけ読めるように絞ります。キーの値は画面に出さず、そのまま `.env.local` へ書きます。

```sh
gcloud services enable tile.googleapis.com street-view-image-backend.googleapis.com apikeys.googleapis.com --configuration=tabireco
gcloud services api-keys create --display-name=tabireco-simulator \
  --api-target=service=tile.googleapis.com \
  --api-target=service=street-view-image-backend.googleapis.com \
  --allowed-referrers='http://localhost:5173/*' \
  --configuration=tabireco --format=none
KEY_NAME=$(gcloud services api-keys list --configuration=tabireco \
  --filter='displayName=tabireco-simulator' --format='value(name)')
echo "VITE_GOOGLE_MAPS_API_KEY=$(gcloud services api-keys get-key-string "$KEY_NAME" \
  --configuration=tabireco --format='value(keyString)')" > .env.local
```

2026-11-05 にこの手順でキーを作り、`.env.local` に入れてあります（この Mac だけ）。

### 3. 起動する

```sh
npm run dev
```

Chrome で http://localhost:5173 を開きます。キーを localhost に絞っているので、`127.0.0.1` ではなく `localhost` で開きます。

## 走らせ方

1. 走る道を選びます（数字キーでも選べます）。URL に `?route=hakodate-onuma` のように付けると、選ぶ画面を飛ばします。
2. ハンコンを初めて使う PC では、C キーで調整します。画面の指示に沿ってハンドルとペダルを動かすと、軸の番号と向きを覚えます（Chrome に PC ごとに残ります）。
3. スマホの旅れこで、画面の左上にある「旅れこの目的地に入れる名前」を目的地にして、案内を始めます。
4. 画面の上に、次の分かれ道までの距離と、今のハンドルで進む向き（左へ・まっすぐ・右へ）が出ます。ナビの案内に合わせてハンドルを切って、分かれ道を抜けます。

| 道 | `route=` | 旅れこの目的地 | 距離（最短） | ストリートビューのある地点 |
| --- | --- | --- | --- | --- |
| 函館駅 → 大沼公園 | `hakodate-onuma` | 大沼公園 | 約27km | 98%（2024年が中心） |
| 札幌 石山 → 定山渓 | `sapporo-jozankei` | 定山渓温泉 | 約17km | 96%（2024年が中心） |
| 美瑛駅 → 青い池 | `biei-aoiike` | 青い池 | 約17km | 100%（2024年が中心） |
| 黄金道路（広尾 → えりも岬） | `ogon-road` | 襟裳岬 | 約45km | 100%（2024・2025年） |

- **出発点と向き:** 旅れこの経路サーバーで引いた経路の最初の点です（`src/routes.ts`）。
- **ストリートビューのある地点の割合:** 2026-11-05 に、その経路を 250m おきに Google の写真の有無（メタデータ）で調べた結果です。

| 操作 | ハンコン | キーボード |
| --- | --- | --- |
| 曲がる方を選ぶ | ハンドル | ← →（押している間だけ） |
| アクセル・ブレーキ | ペダル | ↑ ↓（スペースもブレーキ） |
| U ターン（止まっているとき） | 調整で決めたボタン | U |
| 出発点へ戻る | 調整で決めたボタン | Backspace |
| 道を選ぶ画面 | | M |
| ハンコンの調整 | | C |

## 道路のデータ

`public/roads/<道>.json` は、旅れこの経路から 300m 以内の車道を OSM から取ったものです（© OpenStreetMap contributors、ODbL）。私道・農道・歩道は入れていません。取り直すときは次のとおりです。

```sh
VALHALLA_URL=https://… ROUTING_TOKEN=… python3 simulator/tools/fetch_roads.py [道の id …]
```

## スマホとつなぐ

スマホには Debug ビルドの旅れこを入れます。Debug ビルドにだけ、位置を受け取る口（`SimulatorLinkReceiver`）が入っています。

USB かワイヤレスデバッグで PC とつないだスマホは、次のとおりです。

```sh
adb reverse tcp:8788 tcp:8788
adb shell am broadcast -n com.tabireco.app/com.theacar.android.debug.SimulatorLinkReceiver -a com.theacar.debug.SIMULATOR
```

「シミュレータにつながりました」と出て、シミュレータの右上が「スマホ 1台に位置を送っています」になれば、つながっています。

- **スマホが車載機に USB でつながっているとき:** ワイヤレスデバッグ（Android 11 以降）で `adb` をつなぐか、同じ Wi-Fi で PC の IP アドレスを指定します（`--es host 192.168.0.10`）。会場の Wi-Fi は、端末どうしがつながれない設定のことがあります。
- **アプリが落ちたり開き直されたりしたとき:** 前のつなぎ先へ自動でつなぎ直します（10秒ほど）。
- **止めて実機の測位へ戻す:** `--ez stop true` を付けて同じ放送を送ります。自動のつなぎ直しも止まります。
- **目的地は自動で入る:** シミュレータで道を選ぶと、出発点の位置のあとにその道の目的地（`routes.ts` の destination）が届き、旅れこが前の案内を止めてルートを引き、そのまま案内を始めます（車の画面と同じ、景観の指定なしのルート）。スマホで検索して打つ必要はありません。

### デモの日：1回で起動する

確認用の旅れこを入れたスマホを USB でつなぎ、次を打ちます。

```sh
simulator/tools/demo-start.sh
```

中継と開発サーバーを開き、USB の転送を張り、スマホの Android Auto のヘッドユニットサーバーを起動して DHU を開き、旅れこをシミュレータとつなぎ、Chrome でシミュレータを開きます。DHU が閉じたり線が抜けたりしたら、つなぎ直して開き直します。Ctrl+C で止めます。アプリは `APP=com.tabireco.app` のように変えられます（既定は `com.tabireco.app.dev`）。Android Auto の開発者モードは先に有効にしておいてください。

### スマホがネットにつながっていないとき

会場の Wi-Fi に入れないときなどは、USB の線で Mac のネットを使わせます（2026-11-06 に moto g50 で確かめた）。

```sh
VALHALLA_URL=https://<経路サーバー> node simulator/tools/phone-proxy.mjs   # Mac で中継を開く（8790）
adb reverse tcp:8790 tcp:8790
```

旅れこの Debug ビルドは、接続先を中継に向けて作ります。

```sh
ORG_GRADLE_PROJECT_VALHALLA_URL=http://127.0.0.1:8790/routing \
ORG_GRADLE_PROJECT_MAP_STYLE_URL=http://127.0.0.1:8790/ofm/styles/liberty \
ORG_GRADLE_PROJECT_ROUTING_TOKEN=… ./gradlew :androidApp:assembleDebug
```

- 中継は、地図の設定ファイルの中の配信元のアドレスも中継を通るように書き換えます。
- 接続先が `http://127.0.0.1` のときだけ、地図の部品（MapLibre）を「ネットにつながっている」扱いにします（`SimulatorLinkStarter`）。そうしないと、端末がつながっていないと見て地図を取りに行きません。
- **別の PC で作った旅れこが入っている端末:** 署名の鍵が違うと上書きできません。`-PDEBUG_APP_ID_SUFFIX=.dev` を付けて作ると、`com.tabireco.app.dev` として並べて入ります（放送の宛先も `com.tabireco.app.dev/…` に変わる）。

## 規約と費用

- **保存しない:** ストリートビューの写真は、走るたびに Google から読み込んで表示します。写真を保存・一括ダウンロードしてはいけません（Google Maps Platform 規約 3.2.3(a)）。保存してよいのは写真の ID だけです（Map Tiles API のポリシー）。
- **表記:** 画面の左下に「Google Maps」と写真ごとの出典、右下に「この画像の問題を報告」のリンクを出します（Map Tiles API のポリシー）。消したり隠したりしません。正式なロゴの画像はまだ入れておらず、文字で出しています（要対応）。
- **宣伝の動画に使わない:** ストリートビューの画像は、宣伝用の動画に入れてはいけません（同ポリシー）。ピッチの動画にシミュレータの画面を入れないでください。
- **地図を混ぜない:** ストリートビューと Google 以外の地図を同じ画面に出してはいけません（規約 3.2.3(e)）。シミュレータには地図を出さず、OSM の道は車を道に乗せるためだけに使います。ナビの地図はスマホの画面に出します。この使い方が規約の上で問題ないかは要確認です。
- **費用:** 写真のタイル（512×512）1枚ごとに数えられます。月10万枚まで無料、その先は1,000枚で約2ドルです（2026-11 の料金表）。前方の 4 列 × 2 段だけを、写真約 15〜20m おきに読むので、1km あたり約 350 枚（2026-11-05 に黄金道路で測定）、時速 60km で1時間走ると約2万枚です。写真の ID は 3 秒に1回まとめて聞きます。
- **1時間の使い回し:** 写真のタイルは「1時間は使い回してよい」と指定されています（`Cache-Control: max-age=3600`）。Google とのセッションをブラウザに残して使い回すので、1時間以内に同じ道を走ると、ブラウザに残った写真が使われます。残すのはセッションの文字列だけで、写真をディスクに保存することはしません（規約で不可）。画面の右上に、読み込んだタイルの枚数を出しています。

## テスト

```sh
npm test          # 道路網の上の走り方、写真の選び方、車の速さ、ハンコンの軸の読み取り、スマホへの中継、4本の道の実データ
npm run typecheck
```

## ファイル

| ファイル | 中身 |
| --- | --- |
| `src/main.ts` | 画面の組み立てと毎フレームの処理 |
| `src/streetviewApi.ts` | Map Tiles API。写真の ID・写真の情報・タイルを取る |
| `src/panoramaFeed.ts` | 走る先の写真を順に選び、前方のタイルだけを読み込む。今の写真と次の写真の重ね方 |
| `src/panoramaRenderer.ts` | 写真を仮の形（地面と円筒）に貼り、写真の位置から離れたカメラで描く（WebGL2） |
| `src/roads.ts` | OSM の道路網。交差点で曲がる道の選び方、一方通行、行き止まり、U ターン |
| `src/vehicle.ts` | アクセルとブレーキによる速さの変わり方 |
| `src/input.ts`・`src/calibration.ts`・`src/calibrationView.ts` | ハンコンとキーボードの読み取り、軸の調整 |
| `src/link.ts` | 中継へ1秒ごとに位置を送る |
| `src/routes.ts` | デモで走る4本の道 |
| `bridge/link.ts` | 開発サーバーに載せる中継。ブラウザから受け取った位置を、TCP でスマホへ流す |
| `tools/fetch_roads.py` | OSM から道路網を取る |
