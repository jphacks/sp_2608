# 旅れこ

<img width="631" height="391" alt="hero" src="https://github.com/user-attachments/assets/581926a3-aee8-4b92-b3e2-25d2370b79ca" />

旅れこは、移動そのものを楽しむためのAndroid Auto対応ナビです。
海・森・湖・川、天気や太陽の位置をもとに、走って気持ちいい道を探します。

スマートフォンでルート候補を検索し、GoogleMapのようにスマホアプリとしてルート案内を行うことも、Android Autoに対応していれば、車載画面での案内も可能です。景観に合わせたSpotifyの選曲、専用機器による窓操作、写真付きの旅の記録にも対応しています。

対象地域は北海道です。

## 背景(開発のきっかけや課題等）

楽しい旅の最初と最後の「移動時間」。旅は、ここから始まり、ここから終わりますが、その道中を楽しむことは蔑ろにされがちです。
しかし、天気の良い日に、海沿いを気持ちよく走ったり、少しだけ遠回りして北海道にある[「秀逸な道」](https://roads.scenicbyway.jp)を走れば、新たな発見や綺麗な景色に癒されたりすることは想像に難くありません。

そこで、「道中少しでも綺麗な景色を」と思っても、多くのナビやマップでは、最短経路で移動時間を短くすることにフォーカスしたサービスがほとんどで、移動時間の「楽しさ」「ワクワク」を最大化するサービスは見つかりません。

ここを解決し、旅必要不可欠な「移動時間」を最高のものにできれば、旅の最初からワクワク、旅の最後まで楽しいを実現できると考えました。

## ユーザーストーリー

1. 車載ナビに「旅れこ」を接続する
2. 目的地を入力してルートを決める
   - [特徴①](#特徴①-最短最速ではなく楽しい道を選ぶ)：最短・最速ではなく、楽しい道を選ぶ
3. 音楽は旅れこにおまかせ。聴きたい曲は同乗者がリクエスト
   - [特徴②](#特徴②-走っている道と車内の雰囲気に合った曲をセレクト)：走っている道、車内の雰囲気に合った曲をセレクト
   - [特徴③](#特徴③-運転手以外が各自のスマホから曲をリクエスト)：運転手以外が、各自のスマホから曲をリクエスト（予約）
4. 楽しくドライブをする
5. 旅の思い出にひたる
   - [特徴④](#特徴④-走った道と思い出の写真をステッカーとwebサイトに保存)：走った道、思い出の写真をステッカーとWebサイトに保存
6. また旅に出る

## PV1
https://github.com/user-attachments/assets/54f053a4-6567-4c07-b462-cc3ff3e8f046

## PV2
https://github.com/user-attachments/assets/210fd430-2355-444c-afe4-2f15dc2d6831

[デモ動画(Youtube)](https://youtu.be/KT4o08DKyFY)

## 旅れこの特徴
### 特徴① 最短・最速ではなく楽しい道を選ぶ

寄り道してもいい時間を決めると、その範囲で海・森・湖など好みの景色を長く楽しめる道を探します。景色の移り変わりや、各地の名道を通るかどうかも比べて、雰囲気の違う複数のルートを提案します。
候補を比べ、選んだルートを車載画面で案内します。


https://github.com/user-attachments/assets/168be06e-36c6-4a6e-b22d-7fb8c4ce6859


操作は[ナビの操作](docs/navigation.md)、探索と評価の仕組みは[経路探索アルゴリズム](docs/route-algorithm.md)を参照してください。

#### Android Autoでのナビゲーション

車載画面にはAndroid for Cars App Libraryのナビ用テンプレートを使っています。地図の描画、ルートのプレビュー、案内開始・終了、右左折情報、残り時間・距離を実装しています。

- スマートフォン：停車中の目的地検索、景観の好み・寄り道上限の調整、候補の比較
- Android Auto：選択したルートの確認と案内、車載地図の表示
- 共通の案内処理：GPS・経路進捗・逸脱判断・音声案内。スマホと車載で別々の案内状態を作らない

DHU（Desktop Head Unit）とAndroid Auto対応ナビでの起動確認、未確認の走行条件は[車載での確認手順](docs/android-auto-setup.md)にまとめています。

### 特徴② 走っている道と車内の雰囲気に合った曲をセレクト

案内中のルートの景観と車内の状態をもとに、Spotifyから次の1曲を選びます。ナビで案内を始めると、選曲も自動で始まります。

使い方は[景観BGMの使い方](docs/bgm-recommendation.md)、選曲の仕組みは[音楽推薦の設計](docs/bgm-recommendation-design.md)を参照してください。

### 特徴③ 運転手以外が各自のスマホから曲をリクエスト

車載画面に表示したQRコードを同乗者が読み取ると、自分のスマホで曲を検索して、リクエストできます。同乗者はSpotifyにログインする必要はありません。予約曲がある間は予約を優先し、なくなると特徴②の選曲に戻ります。

受付サーバーと動作確認は[同乗者の曲リクエスト](infra/music-requests/README.md)を参照してください。

### 特徴④ 走った道と思い出の写真をステッカーとWebサイトに保存

目的地に到着すると走行記録が保存されます。写真・地名・メンバーを加えると、走った道の形をかたどったステッカーができます。ステッカーのQRコードからは、地図上の経路や時刻まで見られるWebサイトを開くことができます。

作り方は[旅のステッカー](docs/memory-sticker.md)、Webサイトは[Web版](docs/memory-web.md)を参照してください。

## 将来性と実現可能性

想定するのは、車1台で旅行に行く3〜8人の大学生のグループです。
旅行での移動時間を、「景色のいい道を提案するナビ」「景色と会話・乗客のリクエストに合わせたBGM再生」「旅のステッカー生成」という一連の体験で最高のものにします。

以下のように、同じ移動時間でも、通る道を景色のいい道にしてみたいという発想が根本の思想です。
| ルート（函館駅→大沼公園） | 所要時間 | 森林に沿って走る距離 |
| --- | --- | --- |
| 最短 | 24分 | 3.9km |
| 旅れこの提案 | 38分 | 12.2km |

本サービスは、Google Playでの一般公開を目指しています。
Googleマップのようなナビ・マップアプリとしてはほとんどのAndroidリリース要件を満たしており、リリース作業を行うのみとなっております。（テスターが不足しており苦戦しています。）
ただし、Android Auto の車載ナビアプリとしても使えるようにリリースするにはまだいくつかの厳しい要件で懸念があるため、現在調整を行なっています。
また、現在はAPIを自前PCでCloudflare Tunnel経由でデプロイしていますが、リリースに向け、クラウドサービスでのデプロイを行う必要があると考えています。
公開までの障壁と対応、北海道の外へ広げる方法は[将来性](docs/outlook.md)を参照してください。

## 車載で案内するルートを、どう選ぶか

札幌から函館までの実際の探索です。海・森林・川・湖に加え、通過時刻・太陽・天気を考慮して候補を比較する流れを示しています。



https://github.com/user-attachments/assets/1eb10c6c-3b0d-4dad-ab50-349dd7b00ebd



約2分・音声なし。実際の探索結果を可視化した動画で、車載画面の録画ではありません。


## 車載ナビを支える仕組み

### スマホと車載画面で、一つの案内状態

Android Autoの車載画面とスマートフォンが同じ`NavigationSession`を参照します。自前PCの景観推薦とValhallaが候補を作り、選択した経路を共通の案内処理へ渡します。スマホは主な設定・比較の入口、車載画面は運転中の案内の表示先です。

![Android Autoの車載画面・スマートフォン・共有ナビセッション・経路探索の構成](docs/media/architecture/01-system.png)

### 経路探索

通常ルートを基準に景観区間を組み合わせ、道路網上の経路と所要時間を確認します。有望な案を組み替えながら特徴の異なる候補を残し、選んだ形状をそのまま車載ナビの案内へつなぎます。

![候補の生成・改善・比較までの経路探索](docs/media/architecture/02-route-search.png)

### 景観評価

地理的な特徴を通過時刻・太陽・予報で補正し、景観の連続性や名道、追加時間、道の重複などをルート全体で評価します。

![場所・通過時刻・好みによる景観評価](docs/media/architecture/03-factor-evaluation.png)

### データ構築

公開データから道路の特徴を事前に計算し、検索時は地図版の一致したDBを参照します。

![公開データから道路・地形・名道のDBを構築する流れ](docs/media/architecture/04-data-pipeline.png)

<details>
<summary>アプリの分担・入力と推論・窓操作の機器</summary>

### アプリの分担

共通の契約・状態管理と、画面・外部API・OS固有の処理を分けています。

![ナビ・音楽・車内機能のモジュール構成](docs/media/architecture/05-app-components.png)

### 入力と推論

マイク入力はアプリ全体で共有し、端末内で発話の有無や車内の状態を判定します。結果はBGMの選曲・音量調整・旅の記録に使い、音声そのものは保存・送信しません。
実線が現在の実装、破線は未接続のカメラ・AIモデル向けの拡張経路です。

![入力取得から推論・情報統合・判断・操作までの分担](docs/media/architecture/06-input-inference.png)

### 窓操作の機器

助手席の窓を、ESP32とにおいセンサーBME688で動かす機器の配線です。車載リレーが切れているときは純正スイッチの配線がそのまま使え、車側はギボシでつなぐので元に戻せます。部品と書き込み手順は[窓操作の機器](hardware/window-ai/README.md)にまとめています。

![車の12V電源から端子台・リレー・ESP32・BME688までの配線](docs/media/hardware/window-wiring.png)

</details>

### 景観に合わせた選曲

これから通る景色と車内の会話に合わせて、Spotifyで流す次の1曲を選びます。海沿いでは明るく広がりのある曲、森林では落ち着いた曲を選びやすくします。

![景観と車内の状態から曲調を決め、好み・再生履歴を踏まえて選曲し、Spotifyで再生する流れ](docs/media/architecture/07-bgm-recommendation.png)
<img width="2000" height="1500" alt="music-category" src="https://github.com/user-attachments/assets/33075d4a-7209-44ec-84be-0d64e32b14fc" />

好みを反映するには、音楽画面の「みんなのプレイリスト」で表示名とSpotifyプレイリストURLを入力し、「取り込む」を押します。「プレイリスト内だけ」を選ぶと、取り込んだ曲だけから選曲します。

[使い方](docs/bgm-recommendation.md) · [推薦の仕組み・図解](docs/bgm-recommendation-design.md) · [全体の設計](docs/architecture.md)

## ドライブとの連携

- 景観BGM：案内中のルートと車内の状態に合わせてSpotifyの曲を選び、再生する
- 同乗者のリクエスト：車載画面に受付用QRを表示し、同乗者が自分のスマホから曲を予約する
- 換気の通知：ESP32機器のイベントを音声・スマホ・車載画面へ通知する。窓の手動操作はスマホの専用画面で行う
- 旅の記録：到着時に走行記録を保存。写真やメンバーを加えてステッカーにし、QRから共有ページを開く

## 車載画面で試す

まず[環境構築手順](docs/environment-setup.md#新規環境)に沿って経路APIとAndroid端末を準備し、スマホで検索できることを確認します。その後、[Android Autoの確認手順](docs/android-auto-setup.md)へ進み、DHUまたは対応ナビで車載画面を開きます。アプリのAPKは車載機ではなくスマートフォンへインストールします。

APKだけでは経路検索できません。PC側のAPI、道路・景観データ、認証の準備が必要です。生成データと資格情報はGitに含めません。

構築済みの環境では、Docker Desktopを起動してから実行します。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py up
.venv-scenic/bin/python infra/valhalla/manage.py check
```

`up`はコンテナを再作成するため、検索・案内中には実行しないでください。外出先から使う場合は、[Cloudflare Tunnelの設定](docs/environment-setup.md#11-外でも使う--cloudflare-tunnel)も必要です。

音楽には[Spotifyの設定](docs/spotify-setup.md)、窓の操作には[専用機器の設定](docs/window-control.md)を行います。旅の共有ページは[Web版の手順](web/README.md)を参照してください。

## 設計とデータ

| 資料 | 内容 |
| --- | --- |
| [アーキテクチャ](docs/architecture.md) | 全体図、モジュールの責務、入力と推論、実装状況 |
| [経路探索アルゴリズム](docs/route-algorithm.md) | 候補生成、景観評価、時間制約、複数案の選抜、API |
| [音楽推薦の設計](docs/bgm-recommendation-design.md) | 景観の先読み、音楽6軸への変換、好み・履歴による採点と抽選（図解） |
| [将来性](docs/outlook.md) | 想定ユーザー、Google Playでの公開までの障壁 |
| [ナビの操作](docs/navigation.md) | 目的地検索、好みの調整、候補比較、景観タイムライン |
| [公開データ](docs/public-routing-data.md) | 取得元、利用条件、地理・地形・土地被覆の扱い |
| [道路の登録基準](docs/scenic-road-registry-policy.md)・[道路台帳](docs/scenic-road-registry.md) | 名道の選定、出典、対象区間、本登録と保留 |
| [開発ガイド](CONTRIBUTING.md) | 担当範囲、依存境界、テスト |

## 技術構成

ナビの契約・状態管理はKotlin Multiplatform、共通UIはCompose Multiplatform、Android Autoの画面はAndroid for Cars App Libraryで実装しています。スマホと車載画面は同じ案内セッションを使い、景観探索はPython、走行経路の計算は自前のValhallaが担当します。

```text
androidApp/   Androidの起動・機能の組み立て
shared/       共通UIと依存の組み立て
core/         共通の値・位置・環境・入力・推論の契約
feature/      ルート・案内・音楽・車内・旅の記録
data/         API・気象・Spotify等の接続実装
platform/     Android Auto・スマホの地図・GPS・再生・機器操作
infra/        Valhalla・景観推薦・BGMのサーバーとデータ構築
web/          旅の記録の共有ページ
hardware/     窓操作機器のファームウェア・配線
docs/         設計・構築・運用資料
```

### 独自技術

#### ハッカソンで開発した独自機能・技術

##### HackDay以前に作成・準備したもの

- ハードウェアの準備・開発
  - ガスセンサーBME688・ESP32などの部品の選定・購入
  - ESP32とセンサーを使った機器の開発
  - 窓操作機器の取り付けに向けた車の内装の取り外し・構造の確認
- 景観道路の調査・選定
  - 公式機関・自治体・観光団体の紹介資料をもとに、秀逸な道の認定区間や、おすすめロードなどの候補を選定
  - 口コミを補助資料として調べ、道路の選定基準・ランク・対象区間を整理
- 経路探索に使うデータの選定・設計
  - 道路・海岸線・森林・水域、標高・地形、土地被覆、気象データの調査
  - 各データを道路区間の特徴に対応づけ、ルート評価に使うための設計

##### HackDay期間中に作成した機能

- 経路推薦・ナビゲーション
  - 景観ルート探索アルゴリズムの設計・実装と、道路・景観DBの構築、推薦APIの実装
  - 寄り道上限・景観の好みを使った探索、複数候補の選抜、通常ルートとの比較
  - 目的地検索・現在地表示・景観タイムライン・音声案内
  - Android Autoの車載地図・ルート確認・案内と、スマホとの案内状態の共有
- 音楽・車内入力
  - これから通る景色・天気・車内の状態を使ったBGM推薦と、Spotifyでの再生
  - 同乗者のプレイリストによる好みの反映と、QRからの曲リクエスト
  - 共有マイク入力による発話判定と、会話に合わせたBGMの音量調整
- 機器とアプリの連携
  - 窓操作機器とのBLE接続・操作と、換気イベントの音声・スマホ・車載画面への通知
- 旅の記録・共有
  - 走行経路・写真・地名・メンバーを使ったステッカーの生成
  - QRから経路・写真・音楽などを振り返れるWebページの実装
- アプリ・APIの基盤
  - KMP / CMPによる共通ロジック・UIと、Android固有処理の分離
  - Valhalla・景観推薦APIの起動環境と、データ構築・環境構築手順の整備

#### 経路探索アルゴリズムについて

旅れこで探したいのは、目的地まで速く着く道だけではありません。「あと30分遠回りしてもいいから、海を見ながら走りたい」という好みに応えるため、道路区間の組み合わせを探す推薦エンジンを実装しました。道路網上の経路計算にはOSSのValhallaを使い、どの景観区間を通り、どの候補を残すかを独自に決めています。

まず通常ルートを計算し、その所要時間に寄り道の上限を加えます。海・森・湖・川・渓谷などの特徴を持つ道路を候補にし、有望な組み合わせを複数残しながら探索します。通る区間の追加・削除・入れ替えでも改善し、計算時間と回数の上限まで候補を探します。最短ルートの近くだけに候補が偏らないよう、地域の違う通過経路も探索の出発点にしています。

評価するのは、景色のある地点に近づいたかではなく、実際にどんな道をどれだけ走るかです。Valhallaが返した経路を道路データに照合し、候補ごとの通過時刻・太陽の位置・予報も使って採点します。同じ景色が気持ちよく続くことと、景色が切り替わることを評価する一方、同じ道の往復や、体験があまり変わらない遠回りでは得点を稼げないようにしています。

景観区間の入口と出口を指定するだけでは、途中で近道に抜ける場合があります。そこで、返ってきた経路が狙った区間を実際に通っているかも確認します。最後は点数だけで1本に決めず、時間・景観・通る道路の違いから最大6案を選びます。比較画面に出した経路は、形を変えずにスマホとAndroid Autoの案内へ渡します。

[探索・評価式の詳細](docs/route-algorithm.md) · [候補を生成・改善する実装](infra/scenic/recommendation/search.py) · [異なる候補を選ぶ実装](infra/scenic/recommendation/portfolio.py)

#### 景観と音楽をつなぐ選曲

現在地だけで曲を選ぶと、海の曲を流し始めたころには森に入っていることもあります。旅れこは、経路の進捗・予測速度・曲の長さから、次の曲が流れる間に通る景色を先読みします。その景色と車内の会話から、明るさ・速さ・歌声の強さなど6軸の目標を作り、楽曲の特徴・同乗者の好み・再生履歴を使って選曲します。曲の特徴は事前に保存し、走行中の選曲は数値の比較で行います。

[景観の先読みと選曲の詳細](docs/bgm-recommendation-design.md)

## 対応状況

Android Auto向けに開発中です。iOSのナビは未対応です。

## 使用技術・データ・ツール・参考資料

### 使用技術

| 分野 | 技術 | 用途 |
| --- | --- | --- |
| アプリの共通処理 | Kotlin / Kotlin Multiplatform | ナビ・音楽・旅の記録のモデルと状態管理 |
| アプリUI | Compose Multiplatform / Material 3 | 共通UIとスマホの操作画面 |
| 車載UI | Android Auto / Android for Cars App Library | 車載地図、ルート確認、右左折・残り時間の案内 |
| 地図表示 | MapLibre Native / OpenFreeMap | スマホ・車載画面の背景地図と経路の描画 |
| アプリの通信・非同期処理 | Ktor / Kotlin Coroutines・Flow | API通信と状態の配信 |
| 景観ルート推薦 | Python / SQLite | 景観DBの検索、候補の生成・評価・選抜 |
| 道路網上の経路計算 | Valhalla | 道路条件に合う経路と所要時間の計算 |
| BGM推薦API | TypeScript / Hono / Node.js / SQLite | 景観・車内の状態に合う曲の推薦と履歴管理 |
| 楽曲の特徴づけ | Gemini API | 曲の明るさ・速さ・歌声の強さなど6軸の特徴を事前に推定 |
| 音楽検索・再生 | Spotify Web API / Spotify Android SDK | 楽曲・プレイリストの取得と再生制御 |
| 旅の共有Web | TypeScript / Cloudflare Workers・D1・R2 | 共有ページ・API、旅の記録と写真の保存 |
| APIの起動・公開 | Docker Compose / Nginx / Cloudflare Tunnel | 自前PCでのサーバー起動と外部からの接続 |
| 窓操作機器 | ESP32 / Arduino（C++）/ BME688 / BLE | ガスの検知、リレーによる窓操作、アプリとの通信 |
| テスト | Kotlin Test / Python unittest / Vitest | 共通ロジック、推薦API、Web、ドキュメントの検証 |

### データ

- [OpenStreetMap](https://www.openstreetmap.org/copyright) / [Geofabrik 北海道](https://download.geofabrik.de/asia/japan/hokkaido.html) — 道路・海岸線・森林・水域
- [国土地理院 標高タイル](https://maps.gsi.go.jp/development/demtile.html) — 標高・地形の評価
- [ESA WorldCover](https://esa-worldcover.org/en/data-access) — 土地被覆（2021 v200）
- [Open-Meteo](https://open-meteo.com/en/docs) — 天気予報
- [北海道開発局「秀逸な道」](https://www.hkd.mlit.go.jp/ky/kn/dou_kei/slo5pa0000009at1.html) — 認定された景観道路

各道路の紹介元は[道路台帳](docs/scenic-road-registry.md)、データの取得元・利用条件は[公開データ](docs/public-routing-data.md)にまとめています。

### ツール・公式ドキュメント

- [Kotlin Multiplatform](https://kotlinlang.org/multiplatform/) / [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/) — 共通ロジック・UI
- [Android for Cars App Library](https://developer.android.com/training/cars/apps/navigation) — Android Autoのナビ画面・案内
- [Valhalla](https://valhalla.github.io/valhalla/) — 経路計算
- [MapLibre Native](https://maplibre.org/maplibre-native/docs/book/) / [OpenFreeMap](https://openfreemap.org/) — 地図表示
- [Spotify Web API](https://developer.spotify.com/documentation/web-api) / [Android SDK](https://developer.spotify.com/documentation/android) — 音楽検索・再生
