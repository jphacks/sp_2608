# 環境構築

macOSで北海道データの取得からAPI起動、Androidへのインストールまでを行う。まずUSB接続で動作を確認し、その後に外部公開する。

探索の仕組みは[経路探索アルゴリズム](route-algorithm.md)、取得元と利用条件は[公開データ](public-routing-data.md)を参照。

## 0. 始める前に

### 新規環境

1章から順に進め、USB接続で10章まで完了する。外で使う場合は11章へ進む。

このリポジトリをGitでcloneするか、ZIPで取得して展開する。

Android Studioでプロジェクトを開き、Terminalを使う。通常のターミナルでは、`cd`でプロジェクトフォルダへ移動する。

次のコマンドで3ファイルが表示されれば、作業場所は正しい。

```sh
ls README.md gradlew infra/valhalla/manage.py
```

以下のコマンドは、特記がなければすべてこのプロジェクトルートで実行する。例の`$`や`%`等のプロンプトは入力しない。各コード枠を上から実行し、前のコマンドが失敗したら、その段階で止める。

| 段階 | 作業 | 確認すること |
| --- | --- | --- |
| [1〜3章](#1-何が必要か) | ツール・Python・保存先・認証 | 依存のimportが成功し、保存設定と認証が作成される |
| [4〜6章](#4-北海道のosmを取得する) | 同じ北海道OSMからグラフ・DBを構築 | 各生成コマンドが正常終了する |
| [7〜9章](#7-api起動前の検査) | データ整合性・API・景観検索を検査 | doctor、起動後check、API試験が成功する |
| [10章](#10-usbでandroidを接続する) | APKをインストールして認証を保存 | 端末で場所・ルートを検索できる |
| [11章・任意](#11-外でも使う--cloudflare-tunnel) | 外部回線へ公開 | 公開URLのcheckと、端末の別回線での検索が成功する |

つまずいたときは[13章](#13-エラーの切り分け)、最後の確認は[14章](#14-構築完了の確認表と検証範囲)を使う。

### 既存環境

<details>
<summary>すでに北海道データがある方・以前から利用している方はこちらを開く</summary>

北海道のデータや保存済みトークンを削除しない。PBFの再取得、`prepare`、全DBの再生成は不要。

`infra/valhalla/.routing.json`があれば再利用する。ない場合だけ保存先を指定する。以下は`japan-test`にデータがある場合の例。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py init \
  --data-dir infra/valhalla/data/japan-test
.venv-scenic/bin/python infra/valhalla/manage.py doctor
```

既存の`.routing.json`と違う保存先は、`init`で上書きしない。意図して切り替える場合は、保存先とDBファイル名を設定ファイルで変更する。設定・データを確認した後、8章の起動へ進む。

</details>

## 1. 何が必要か

| 項目 | 用途・前提 |
| --- | --- |
| プロジェクト一式 | 自分のリポジトリをcloneするかZIPから展開。`infra/scenic`と`infra/valhalla`も必要 |
| Docker Desktop + Compose v2 | 道路グラフ生成、Valhalla、景観API、Gateway |
| Python 3.12 | ホストのDB生成と管理コマンド。管理コマンドは3.11以上を要求 |
| Node.js 24系 | `fetch`を使うAPI試験 |
| Android Studio / JDK 21 | Android SDK・ビルド・実機接続。Javaのコンパイル対象は17 |
| Android SDK | SDK Platform 36、必要なBuild Tools、Platform-ToolsをSDK Managerで導入 |
| Android端末 | Android 8 / API 26以上。USBデバッグを許可 |
| cloudflared | 外部から使う場合のみ。USB接続だけなら不要 |

macOSにHomebrewを導入済みの場合の例:

```sh
brew install python@3.12 node@24 cloudflared
export PATH="$(brew --prefix node@24)/bin:$PATH"
python3.12 --version
node --version
```

Homebrewがない場合は[公式の導入案内](https://brew.sh/)を確認する。[Python 3.12のFormula](https://formulae.brew.sh/formula/python@3.12)、[Node 24のFormula](https://formulae.brew.sh/formula/node@24)を参照。

Docker Desktopは[公式のmacOS手順](https://docs.docker.com/desktop/setup/install/mac-install/)から、自分のApple Silicon / Intelに合うものを入れ、アプリを起動する。

```sh
docker version
docker compose version
docker info
```

ClientだけでなくServer情報が表示されることを確認する。Dockerアプリのインストールと、エンジンの稼働は別。Composeは`up --wait --wait-timeout`が使えるv2を使う。古い`docker-compose` v1では進めない。

### リソースの目安

構築時の目安はPCメモリ16GB以上、空き容量20GB以上、Dockerへの割り当て8GB程度。

現行composeの上限は生成用4GB、Valhalla実行2GB、景観API1GB。DEM・衛星分類などのホストPython処理はDockerとは別にメモリを使う。初回は並列に走らせず順番に実行し、生成中はPCをスリープさせない。137/OOMならリソースを確認してから再試行する。

## 2. Pythonの生成環境を作る

プロジェクトルートで実行する。すでに動く`.venv-scenic`がある場合は、作り直さず依存確認から進む。

```sh
python3.12 -m venv .venv-scenic
.venv-scenic/bin/python -m pip install --upgrade pip
.venv-scenic/bin/python -m pip install -r infra/scenic/requirements-terrain.txt
.venv-scenic/bin/python -c 'import osmium, shapely, numpy, PIL, rasterio; print("Import dependencies: OK")'
```

OSM・地理計算・DEM画像・GeoTIFFの依存をまとめて入れる。`requirements.txt`だけではDEM・土地被覆の画像処理依存が不足する。システムPythonへ直接`pip install`したり、Condaとvenvのpipを混ぜたりしない。

以降は`.venv-scenic/bin/python`を明示するので、`source .../activate`は必須ではない。サーバーコンテナは標準ライブラリで動作し、これらの画像処理依存を実行時に導入しない。

## 3. 保存先とトークンを初期化する

新規環境では、既存データと混ぜない新しいディレクトリを指定する。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py init \
  --data-dir infra/valhalla/data/hokkaido
```

このコマンドは次を作る。

| 場所 | 内容 |
| --- | --- |
| `infra/valhalla/.routing.json` | データ保存先の絶対パス、7種類のDB名、予報の有効設定 |
| `infra/valhalla/.secrets/routing.token` | Androidが使う経路APIトークン |
| `infra/valhalla/.secrets/routing-auth.conf` | 同じトークンを許可するnginx設定 |

設定とトークンはGit対象外。トークンは初回に生成し、再実行時は既存の組を検証して再利用する。

`.routing.json`の例（トークンは含まない）:

```json
{
  "schema": 1,
  "data_dir": "/absolute/path/to/TheaCar/infra/valhalla/data/hokkaido",
  "files": {
    "SCENIC_DATABASE": "scenic-hokkaido.sqlite",
    "PLACES_DATABASE": "places.sqlite",
    "SCENIC_RECOMMENDED_ROADS": "road-recommendations-research-20261018-v7.sqlite",
    "SCENIC_LANDSCAPES": "landscapes-hokkaido-v4.sqlite",
    "SCENIC_TERRAIN": "terrain-hokkaido-v1.sqlite",
    "SCENIC_CONTEXT": "context-hokkaido-v1.sqlite",
    "SCENIC_LANDCOVER": "landcover-hokkaido-v3.sqlite"
  },
  "weather": true
}
```

生成時とAPI起動時は同じ保存設定を使う。管理コマンドは環境変数の`VALHALLA_DATA_DIR`・`SCENIC_*`より保存設定を優先する。

独自の保存場所・ファイル名を使う場合はこの設定を編集する。DBは同じ保存ディレクトリ内の`.sqlite`ファイル名で指定し、`/data/...`やホストの絶対パスをfiles欄へ書かない。プロジェクト移動時はdata_dirを更新する。

### 景観BGMの資格情報（任意）

景観BGM（`infra/bgm-api`）は8章の`up`で`bgm`コンテナとして一緒に起動し、経路APIと同じトークンで`/bgm/`から使う。全曲カタログ（`infra/bgm-api/catalog/catalog.jsonl`）は起動時に自動で取り込むので、生成作業は無い。

選んだ曲をSpotifyで再生するには、サーバーがSpotifyの曲IDを照合する資格情報が要る。`infra/valhalla/.secrets/bgm.env`を次の内容で作り、`chmod 600 infra/valhalla/.secrets/bgm.env`で本人だけが読めるようにする（Git対象外）。

```text
SPOTIFY_CLIENT_ID=Spotify DashboardのClient ID
SPOTIFY_CLIENT_SECRET=同じアプリのClient Secret
GEMINI_API_KEY=任意。プレイリストの曲を注釈する /bgm/annotate だけが使う
```

Client IDはアプリの[Spotify設定](spotify-setup.md)と同じもの。Client Secretはサーバーだけが使い、アプリやlocal.propertiesへは書かない。無い場合もナビと選曲は動くが、アプリでは曲を再生できない。doctorとcheckがWARNで知らせる。変更後は`up`で反映する。

`manage.py`は`bgm.env`があれば読み込み、なければ資格情報なしで起動する。Composeを直接使う場合は`BGM_ENV_FILE=./.secrets/bgm.env`を指定する。

## 4. 北海道のOSMを取得する

```sh
.venv-scenic/bin/python infra/valhalla/manage.py download-pbf
```

[Geofabrikの北海道配布](https://download.geofabrik.de/asia/japan/hokkaido.html)から`hokkaido-latest.osm.pbf`を取得し、保存先へ`region.osm.pbf`として配置する。転送サイズ・PBFヘッダーを検査し、取得URL・日時・SHA-256を`pbf-download.json`へ残す。

取得上限は512MB。通信失敗した`.download`を完成PBFとして扱わない。既存PBFや途中ファイルがあれば上書きせず停止する。別環境からコピーしたPBFを使う場合はこのダウンロード操作を省略し、設定済みの保存先へ正しい`region.osm.pbf`を置く。

以後、すべての地理DBと道路タイルをこの同じPBFから生成する。途中で`latest`を取り直して混ぜない。Liechtenstein・Monaco等の検証データでは北海道のGPSや検索は動かない。

## 5. Valhallaの道路グラフを構築する

```sh
.venv-scenic/bin/python infra/valhalla/manage.py prepare
```

コンテナイメージの取得後、行政区域DBと道路タイルを生成する。正常終了したら以下が揃う。

```text
保存先/
  region.osm.pbf
  pbf-download.json       （上記ダウンロードを使った場合）
  valhalla.json
  admin.sqlite
  tiles/.../*.gph
  routing-source.json
  routing.lua
```

`routing-source.json`はタイル生成が成功してから作り、元PBFのSHA-256を保存する。新規構築の設定は自動車の最大地点128、距離1,000km、標準代替経路2本。推薦側の上限とも整合させている。

速度未登録の道路には[北海道向けの速度補完](route-algorithm.md#走行速度の補完)を適用する。生成記録に`speed_profile`とLuaのハッシュが残る。既存タイルには再構築するまで反映されない。

既存のtilesディレクトリは上書きしない。失敗した生成物を正常なグラフとして起動しない。地域更新や途中生成のやり直しは、データを保管して新しいディレクトリで構築する。既存の成功済みグラフに対して`prepare`を再実行する必要はない。

続けて、景観と場所検索のDBを構築する。

地域抽出では、範囲外の行政区域や任意のtimezone/elevation DBに関するログが出る場合がある。成功は終了コード0と`doctor`・API検査で確認する。Tracebackや非0終了は原因を調べる。景観用DEMとValhalla内部の標高DBは別物。

## 6. 検索・景観の7種類のDBを構築する

```sh
.venv-scenic/bin/python infra/valhalla/manage.py build-data
```

管理コマンドが下記を順番に実行する。完了まで時間がかかる。途中の件数や地域番号が進むこと、最後の終了コードが0であることを確認する。

| 順番 | 生成物 | 内容・取得 |
| --- | --- | --- |
| 1 | `scenic-hokkaido.sqlite` | 北海道道路、海岸距離、森林・自然・建物の面積率。中間DBも作る |
| 2 | `places.sqlite` | 地名・施設のローカル検索。元PBFのハッシュを保存 |
| 3 | `landscapes-hokkaido-v4.sqlite` | 湖・川・谷・水域・田園の近接 |
| 4 | `road-recommendations-research-20261018-v7.sqlite` | 原稿の出典・形状を検査した名道。本登録・保留を区別。既存設定は自動変更されないため、更新時は[登録DBの適用手順](scenic-road-registry-policy.md#サーバーへの適用と起動)を確認 |
| 5 | `context-hokkaido-v1.sqlite` | 道路属性・未検証の周辺施設 |
| 6 | `terrain-hokkaido-v1.sqlite` | 地理院標高タイルを取得し、地形特徴を計算 |
| 7 | `landcover-hokkaido-v3.sqlite` | ESA WorldCover 2021を取得し、土地被覆を計算 |

登録道路は札幌市CKANの配布メタデータから最新の資料を取得する。認定15区間が揃わなければ検査で停止する。日帰り・宿泊モデルは道路登録に使わない。

標高の取得上限は550タイル、WorldCover原ファイルの合計上限は120,000,000bytes。取得元や地域が変わったら見直す。地理院の404は欠損として記録する。

元の公開資料の内容・構造・ライセンスが変わった場合は、取得・検査が止まることがある。チェックを外して通すのではなく、[データ仕様](public-routing-data.md)と出典を確認する。

現在の手順は登録不要の地理データを対象にする。Spotify音楽も使う場合は、別途[Spotifyの設定](spotify-setup.md)に従う。SpotifyのClient ID・認可情報、Cloudflareの接続資格情報、経路APIトークンを使い回さない。

### 中断・再実行

生成済みDBは整合性を検査して再利用し、未完了の段階から再開する。既存DBは上書きしない。

道路カタログの`.building`は同じ入力・設定で`--resume`を使って再開する。その他の`.building`は自動で再開・削除しない。`.routing.json`の該当DB名を新しい名前へ変更し、その段階を作り直す。例:

```sh
.venv-scenic/bin/python infra/valhalla/manage.py build-data --only terrain
```

`--only`には`roads / places / landscapes / registry / context / terrain / landcover`を指定できる。道路以外の段階は先にroadsが完成していること。`--only`の後は必ず7章のdoctorを実行する。

完成DBを使う場合でも元PBFは保持する。別PBFで作ったDBを同名で混ぜない。PBFや道路カタログが変わった場合は、新しいディレクトリで一式再構築する。

## 7. API起動前の検査

```sh
.venv-scenic/bin/python infra/valhalla/manage.py doctor
```

次を確認する。

- トークンとnginx認証設定が同じ組である。
- Valhalla設定・行政DB・実際の`.gph`が存在する。
- 7DBが存在し、SQLiteのquick_checkと必要なテーブルの検査が成功する。
- 元PBFと道路カタログのハッシュが一致する。
- 追加景観DB、登録道路DBが同じ地図・カタログに対応する。
- DEM・周辺施設・衛星分類が空のDBではない。
- 札幌・函館が範囲内で、ローカル検索に「函館」がある。
- 新規形式なら場所DBとValhalla生成元のPBFも一致する。

doctorはファイルの検査で、API・外部回線の到達確認ではない。新規手順通りに生成した場合は、旧版データに関するWARNは出ない。

### 既存データのWARN

旧places DBの元ハッシュや、旧グラフの`routing-source.json`がない場合は生成元を検証できない。新しい保存先で再構築すると解消する。

旧Valhallaの地点・距離・代替案上限は、グラフを作り直さず次で更新できる。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py upgrade-config
```

元設定を`valhalla.json.backup-日時`に保存し、対応する上限だけを変更する。反映には次のupが必要。案内・検索中には切り替えない。

## 8. 全APIを起動して検査する

```sh
.venv-scenic/bin/python infra/valhalla/manage.py up
.venv-scenic/bin/python infra/valhalla/manage.py status
```

`up`は事前検査後、保存設定を使ってValhalla・景観API・景観BGM・ローカルGateway・認証Gatewayを起動する。景観BGMのイメージは毎回ビルドし、変更がなければキャッシュを使う。

Valhallaと景観APIのヘルスチェックを待ち、認証Gatewayから次を実際に確認する。

1. 未認証・不正トークンは401。
2. 正しいトークンでstatusが200。
3. 道路・登録名道・水辺・地形・周辺情報・衛星分類の各ソースがloaded。
4. 予報有効の構成ではOpen-Meteoがconfigured。
5. 函館検索に結果がある。
6. 札幌市内の通常ルートが取得できる。
7. 景観BGM（`/bgm/`）が未認証を拒否し、全曲カタログから選曲できる。
8. 選んだ曲をSpotifyの曲に照合できる。`bgm.env`が無い・誤っている場合はWARN（全体は止めない）。

再検査だけの場合:

```sh
.venv-scenic/bin/python infra/valhalla/manage.py check
```

`check`では景観探索や予報取得は実行しない。次章で推薦を計算して確認する。

`up`は設定を確実に反映するためコンテナを再作成する。既存のPBF・DB・トークン・保存ボリュームは削除しないが、動作中の探索は中断される。稼働中に繰り返し実行しない。

| PC側の入口 | 用途 | 外部公開 |
| --- | --- | --- |
| `127.0.0.1:8002` | 認証なしのローカル開発用 | 不可 |
| `127.0.0.1:8003` | Bearer認証付きAPI | Tunnelはここへ接続 |
| Docker内`valhalla:8002` / `scenic:8004` / `bgm:8005` | 内部サービス | ホストへ直接公開しない。景観BGMは`/bgm/`経由 |

## 9. 景観ルート推薦まで検証する

まず認証の拒否・メソッド制限を確認する。

```sh
node infra/valhalla/test-gateway.mjs
```

次に、全追加データの読み込みと、札幌→函館・苫小牧の実探索を確認する。他の端末から検索していないときに実行する。

```sh
SCENIC_EXPECT_DATA=1 SCENIC_EXPECT_ENVIRONMENT=1 \
  SCENIC_CASES=infra/scenic/fixtures/hokkaido-landscape-routing.json \
  node infra/scenic/test-recommendations.mjs
```

Node試験は同じ`infra/valhalla/.secrets/routing.token`を内部で読み、値は表示しない。`data-status`、予算、候補・根拠参照、環境タイムライン、サーバー側の案内選択と試験データ削除まで確認する。数分程度かかることがあり、時間・走査上限の`limited`は候補なしエラーとは違う。

候補数はデータ・時刻・条件によって変わる。Pythonテストも実行できる。

```sh
.venv-scenic/bin/python -m unittest discover -s infra/valhalla -p 'test_*.py'
.venv-scenic/bin/python -m unittest discover -s infra/scenic -p 'test_*.py'
```

## 10. USBでAndroidを接続する

外部公開より先にUSBで動作確認すると、回線とアプリの問題を分離できる。

### 10.1 Androidの開発環境

Android Studioでプロジェクトを開き、SDK ManagerでSDK 36とPlatform-Toolsを入れる。`local.properties`の`sdk.dir`はAndroid Studioが設定した値を維持する。

[公式SDK Manager手順](https://developer.android.com/studio/intro/update#sdk-manager)で導入とライセンス承認を行う。SDKのインストール先が表示されるので、次の環境変数の例と異なる場合は実際の場所を使う。

CLIからJDKを指定するmacOSの例（Android Studioを標準位置へ入れた場合）:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$JAVA_HOME/bin/java" -version
```

そのパスにJDKがない場合は実際のJDK 21の場所へ変更する。`/usr/libexec/java_home -V`で登録済みJDKも確認できる。別PCの絶対パスをそのままコピーしない。

`adb`が見つからない場合はAndroid SDKのPlatform-ToolsをPATHへ追加する。macOS標準のSDK配置例:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb version
```

### 10.2 接続先設定・ビルド

端末の開発者向けオプションでUSBデバッグを有効にし、PCの接続許可を端末で承認する。

```sh
adb devices
adb reverse tcp:8003 tcp:8003
./gradlew -g .gradle-local -PVALHALLA_URL=http://127.0.0.1:8003 \
  :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.theacar.android/.MainActivity
```

複数端末が出たら各adbコマンドに`-s 端末ID`を付ける。`unauthorized`のまま進めない。`adb reverse`はUSBの再接続等でやり直す場合がある。端末の127.0.0.1を、そのままPCだと思わない。

ここではGradle引数でURLだけを上書きするため、既存local.propertiesを壊さない。`GEOCODING_URL`を別サービスに設定済みなら、空欄へ戻して自前APIの`/search`を使う。背景地図を変更済みなら`MAP_STYLE_URL`も確認する。

ビルド時の接続先はAPKに入る。`local.properties`を編集しただけでは、インストール済みAPKは変わらない。URLを変えたら再ビルド・`adb install -r`まで実施するか、アプリの設定画面「経路サーバーの URL」で上書きする。設定画面の値はビルド時の値より優先される。

### 10.3 トークンを保存する

少人数の開発利用では、Git対象外の`local.properties`へ`ROUTING_TOKEN=...`として`routing.token`の値を設定し、APKを再ビルドできる。埋め込み値があれば端末での入力は不要。ただしトークンはAPKから取り出せるため、不特定多数へ配布しない。公開配布には利用者別認証が必要。

端末に保存したトークンがあれば、埋め込み値より優先する。別サーバーへ切り替えるときや手入力で設定する場合は次の順にたどる。入力欄は既定で隠れている。

1. ホーム画面の「**地図・ナビを開く**」を押す。
2. 上部バーの右端の「**設定**」を押す。「ナビ設定」が開く。
3. 「道路の条件」の下まで送り、区切り線の下の「**接続・認証を変更**」を押す。
4. 現れた「**経路 API のトークン**」へ、PCの`routing.token`の内容だけを貼る。
5. 下端の「**保存して閉じる**」を押す。

`Bearer `、Tunnelの接続トークン、UUID.jsonの内容、SpotifyのClient IDを入れない。入力欄はパスワード表示なので、打ち間違いを目で確認できない。貼り付けで入れる。

前後の空白は保存時に除去され、`Bearer `は送信時に付く。途中に空白・改行・制御文字があると認証エラーになる。

案内中は保存できないため、案内を終了してから変更する。

macOSでコピーする場合（トークンは画面へ出ないが、クリップボードへ置かれる）:

```sh
pbcopy < infra/valhalla/.secrets/routing.token
```

Windowsの場合:

```powershell
(Get-Content infra/valhalla/.secrets/routing.token -Raw).Trim() | Set-Clipboard
```

USB入力で送る場合は、端末のトークン入力欄を選択した状態で、自分のPCの信頼できるターミナルから実行する。

```sh
adb shell input text "$(tr -d '\r\n' < infra/valhalla/.secrets/routing.token)"
```

```powershell
adb shell input text (Get-Content infra/valhalla/.secrets/routing.token -Raw).Trim()
```

adb入力は短時間ローカルのプロセス引数へ値が渡る。画面共有・端末のログ取得中は避ける。入力後は必ず「保存して閉じる」を押す。トークンをチャット・スクリーンショット・Gitへ貼らない。

保存できたかは、実際に場所を検索して経路が出ることで確認する。保存済みの値は画面に表示されない。「保存済み認証を削除」は端末の保存値を消し、埋め込み値がある場合はそちらへ戻る。サーバー側でトークンを更新したときは、端末で上書き保存するか、埋め込み値を更新して再ビルドする。

保存トークンは端末内で暗号化し、アプリ再起動・上書き更新後も再利用する。通常は毎回入力不要。アンインストール、アプリデータ消去、端末変更、サーバー側のトークン変更では再入力が必要。URLだけの変更でトークンを再生成する必要はない。

### 10.4 端末で確認する

位置情報の「正確」と必要な権限、端末の位置情報設定を有効にする。北海道の実機位置で、検索アイコン→「函館」→目的地選択→寄り道・好みを設定→検索を試す。景観詳細・名道・環境帯まで確認する。

北海道内の実機、または北海道の位置を設定したエミュレーターで確認する。GPSの取得時刻と精度も確認する。

## 11. 外でも使う — Cloudflare Tunnel

8・9章でローカルAPIの検査が通ってから進む。ホストPC上のcloudflaredを使い、Docker内に置く手順ではない。PC・Docker・回線・cloudflaredが動いている必要がある。

### 11.1 一時URLで試す

別ターミナルで次を実行し、開いたままにする。

```sh
cloudflared tunnel --url http://127.0.0.1:8003
```

表示された`https://...trycloudflare.com`を使う。8002へ接続して認証を回避しない。

Quick Tunnelはアカウント・独自ドメインなしで試せるが、再作成でホスト名が変わり、稼働保証がない開発向けの方式。[公式説明](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)を参照。本アプリはBearer APIなので、対話的なメールログインを要求する`--allowed-mail`は付けない。

新しいURLを値として設定して検査する（例示URLを実際のものへ置き換える）:

```sh
.venv-scenic/bin/python infra/valhalla/manage.py check \
  --endpoint https://your-actual-host.trycloudflare.com
```

未認証では401、正しいトークンでは200が返ることを確認する。TLS検証は有効のまま使う。検査コマンドはリダイレクト先へトークンを送らない。

### 11.2 固定URLを使う場合

常用する場合は、Cloudflare管理ドメインと固定ホスト名を用意し、[公式のリモート管理Tunnel手順](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/)で作成する。

1. CloudflareのTunnel作成画面でcloudflared接続を選び、名前を設定する。
2. OSに合うインストール・起動コマンドをPCで実行する。画面に表示されるTunnel接続資格情報は秘密にする。
3. Published application routeで、選んだホスト名から`http://127.0.0.1:8003`へ転送する。パス指定は空にしてAPI全体を通す。
4. 接続状態に加え、前項の`manage.py check --endpoint https://固定ホスト名`を実行する。

Cloudflare管理画面のTunnel tokenはcloudflared用。Androidへ入れる`routing.token`とは別。対話型Accessを重ねる場合はAPIクライアント側の対応が必要で、現在のアプリが自動でブラウザ認証を行うとは扱わない。

### 11.3 公開URLでアプリを再ビルドする

```sh
./gradlew -g .gradle-local -PVALHALLA_URL=https://your-actual-host.trycloudflare.com \
  :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

固定URLならそのURLへ置き換える。上書きインストールなのでトークンは維持される。USBでだけ成功している状態と区別するため、外部URL設定のAPKで、PCとは別回線から通常ルート・場所検索・景観検索を確認する。USBを抜く、または必要に応じてreverseを解除して確かめる。

```sh
adb reverse --remove tcp:8003
```

QUIC接続が回線で失敗する場合は`cloudflared tunnel --protocol http2 --url http://127.0.0.1:8003`でも確認できる。両方式とも失敗したら、トークンを外さず公式の接続事前確認と回線・VPN・ファイアウォールを調べる。

## 12. 再起動・停止・データ更新

### PC再起動後

Docker Desktopを起動し、プロジェクトルートから実行する。保存設定があるのでデータの再取得・トークンの再入力は不要。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py up
```

外部利用ならcloudflaredも起動する。Quick Tunnelの新しいURLは11章の検査・APK更新が必要。固定URLでは通常その変更は不要。

### 停止とログ

```sh
.venv-scenic/bin/python infra/valhalla/manage.py status
.venv-scenic/bin/python infra/valhalla/manage.py logs
.venv-scenic/bin/python infra/valhalla/manage.py down
```

停止は案内・探索していないときに行う。`down`にボリューム削除を付けていないため、状態ボリューム・元データは残る。cloudflaredはそのターミナルでCtrl+C等により別途停止する。

更新は新しいディレクトリへ一式生成し、doctor・API・実機確認を経て切り替える。旧PBF・DB・設定は戻せるよう保持する。原データだけを上書きして他のDBを据え置かない。

### 道路タイルだけを更新する

OSMが同じなら景観・場所検索の7DBは再生成しなくてよい。新しいディレクトリを用意し、既存の`data_dir`にある`region.osm.pbf`をコピーする。古いタイル・設定・PBFは残す。

`infra/valhalla/.routing.json`に、新しいディレクトリの絶対パスを`routing_dir`として追加する。`data_dir`と`files`は変更しない。

```json
"routing_dir": "/absolute/path/to/TheaCar/infra/valhalla/data/hokkaido-speeds-v1"
```

```sh
.venv-scenic/bin/python infra/valhalla/manage.py prepare
.venv-scenic/bin/python infra/valhalla/manage.py doctor
```

構築・検査が完了したら、検索・案内していないときに`manage.py up`と`manage.py check`を実行する。URL・トークン・APKは変更不要。前の候補は古い時間を持っているため、アプリから再検索する。

元に戻す場合は`routing_dir`を削除し、`up`で切り替える。速度の変更で探索候補・順位・通過時刻も変わるため、切り替え前後を同じ目的地・好み・寄り道上限で比較する。

## 13. エラーの切り分け

| 表示・症状 | 確認と対処 |
| --- | --- |
| Docker Serverへ接続不可 | Docker Desktopを起動し、docker infoを確認 |
| PBFがない | initの保存先とregion.osm.pbfの有無。prepareより先にdownload-pbf |
| Existing tiles / DB | 上書き保護。成功済みなら再生成を省略、更新なら新しい保存先 |
| `.building`が残る | 道路は同入力で再開、他DBは新しい出力名。完成扱い・無条件削除しない |
| exit 137 / OOMKilled | Docker/ホストの空きメモリ、同時処理を確認 |
| exit 133 / unhealthy | manage logsで最初のValhallaエラーを確認。設定・タイル欠落やイメージの実行環境を調べる。番号だけで原因を断定しない |
| missing database | 7DBのどの段階が未完了か確認。placesだけでは景観は動かない |
| catalog signature mismatch | 異なるPBF/カタログの混在。対応する一式へ戻すか新規構築 |
| token/config mismatch | routing.tokenとrouting-auth.confの組を復元。片方だけ編集・再生成しない |
| 認証ファイルがディレクトリ | initより先にDockerを起動すると空ディレクトリができる場合がある。停止後、誤作成物を別名へ退避し、既存の正しい組を復元する。初回で資格情報がまだない場合のみinitで作成 |
| 401 | 空白・Bearerの二重入力、未保存、別サーバーのトークン、古い端末保存値 |
| 403/404 | /route等をベースURLに付けていないか、Gatewayが正しいか |
| 502/503 | scenic profile・DB・コンテナ稼働。Tunnelより先にローカルcheck |
| 429 | 景観の同時探索枠かGatewayのレート制限。連打・複数試験を止め、前の処理終了を待つ |
| 検索不可 | places DBとscenic稼働、GEOCODING_URLの別設定、APKの実際の接続先 |
| GPS確定不可 | 地図の中心ではなく、権限・GPS設定・精度・鮮度とカタログ内の位置を確認 |
| 経路なし | 基準経路、地域、道路・時間・休憩条件、search.failures。条件を勝手に緩めない |
| URLを変えても繋がらない | 設定画面のURLがビルド時の値より優先される。設定画面の値、再ビルド・上書きインストール、公開URLのcheck |
| 地図だけ出ない | 背景地図のスタイル・タイル・フォントは別配信。APIトークンやValhallaタイルでは直らない |

背景地図は既定でOpenFreeMapをネット取得する。Valhallaを自前運用しても背景地図まで完全オフラインにはならない。Open-Meteoの無料APIの用途・上限、OSM帰属・ODbL、地理院・ESA・道路紹介資料の利用条件も[データ仕様](public-routing-data.md)に従う。

## 14. 構築完了の確認表と検証範囲

- doctorで必要ファイル・署名・認証の検査が通る。新規構築では元PBFの検証不能WARNがない。
- 未認証・不正トークンが拒否され、正しい認証でcheckが通る。
- 場所検索・通常ルート・景観推薦のAPI試験が通る。
- 端末へインストールしたAPKの接続先とトークンが合い、実際に検索できる。
- 外部利用する場合は、公開URLと別回線からも同じ検査が通る。
- 再起動後も同じデータ・トークンで使える。

単体テストは管理処理・トークン再利用・上限設定・途中ファイル保護・入力検証を扱う。実APIの起動検査、初回の全データ構築、別PC・外部回線・実機での確認は、それぞれ別に行う。

実走評価と未対応項目は[設計書18章](route-algorithm.md#18-検証と制約)を参照。
