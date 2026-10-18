# アーキテクチャ

Android Autoの車載画面とスマートフォンが、一つのナビセッションを共有します。スマホは主な設定・比較の入口、車載画面は案内の表示先として実装し、自前PCが経路探索とBGM推薦を行います。KMPの契約・状態と、OS・外部APIに依存する実装を分けています。

[図をまとめたPDF](media/architecture/theacar-architecture.pdf) · [経路探索の詳細](route-algorithm.md) · [環境構築](environment-setup.md)

## 全体構成

![Android Autoの車載画面・スマホ・共有ナビセッション・経路探索](media/architecture/01-system.png)

車載UIは`CarAppService`のNavigationカテゴリとして提供し、`NavigationTemplate`とMapLibreの地図Surfaceで構成します。車載画面とスマホは同じ`NavigationSession`を操作し、GPS・TTSはAndroid側で実装します。

外部からの通信はCloudflare Tunnelと認証付きNginxを通します。`/route`はValhalla、景観推薦・場所検索・案内セッションはPythonの`scenic`、`/bgm/`はBGMサービスへ振り分けます。
ValhallaとscenicのポートはDocker内部用です。Tunnelは認証付きの`127.0.0.1:8003`へ接続します。

PC・Docker・回線・Tunnelが動いている間だけ検索できます。背景地図、Open-Meteo、Spotify、旅の共有ページはそれぞれ別のサービスです。Spotifyの資格情報はサーバー用・アプリ用で分け、経路トークンを地図配信先へは送りません。

## 探索と評価

![経路探索の流れ](media/architecture/02-route-search.png)

推薦エンジンが景観区間・通過点の組み合わせを探索し、Valhallaが走行可能な道路と時間を計算します。Valhallaの全道路へ景観コストを直接埋め込む方式ではありません。
有効な候補は途中公開し、アプリが`revision`を取得して表示を更新します。改善は計算時間・回数の上限まで続けます。

![景観の評価](media/architecture/03-factor-evaluation.png)

区間の地理特徴を、その候補自身の通過時刻・太陽位置・固定予報で補正します。ルート全体では景観の質・連続性・変化・名道を加点し、追加時間・負荷・重複・未確認情報を減点します。
森林・市街地へ水辺と同じ夜間補正は掛けません。気温・風など、取得・表示していても直接採点しない項目があります。景観帯は表示用の要約で、採点式とは別です。

## データ構築

![データ構築](media/architecture/04-data-pipeline.png)

OSMからValhallaタイル、道路特徴、地名検索用データを作ります。標高・土地被覆・登録名道は追加DBとして接続し、地図版・署名・スキーマの一致を検査します。
静的DBは検索時に読み取り専用で参照し、ジョブ・スナップショット・選択状態は別の状態DBへ保存します。

名道の本登録には公式・自治体・観光団体による道路紹介を必要とします。地形だけで見つけた道は未登録の探索候補として利用し、推薦ランクを自動付与しません。[登録基準](scenic-road-registry-policy.md)と[出典・利用条件](public-routing-data.md)を参照してください。

## アプリの責務

![アプリの機能分担](media/architecture/05-app-components.png)

| モジュール | 所有するもの |
| --- | --- |
| `core/model`・`core/location`・`core/environment` | 共通の値と、位置・環境入力の契約 |
| `feature/route`・`feature/navigation` | 経路要求・候補・比較モデル、案内状態・進捗・逸脱判断 |
| `feature/music`・`feature/cabin`・`feature/memory` | 選曲・再生、窓提案・機器契約、旅の記録 |
| `feature/drive` | ナビの進捗・景観を音楽の入力へ変換する連携 |
| `data/valhalla`・`data/spotify`・`data/weather`・`data/bgm`・`data/memory` | HTTP・認証・API型から共通モデルへの変換 |
| `platform/android-*` | GPS、MapLibre、TTS、再生画面、Keystore、BLE窓機器、ステッカー |
| `shared`・`androidApp` | UIの接続と依存注入、アプリ単位のRuntimeの所有 |
| `infra/scenic`・`infra/valhalla`・`infra/bgm-api` | 推薦・経路・BGMのサーバーとデータ生成 |
| `web`・`hardware/window-ai` | 旅の公開ページ、ESP32の窓操作機器 |

図の矢印は操作・情報の流れであり、Gradleの依存方向ではありません。契約は利用する機能側に置き、`data`・`platform`の実装を注入します。`route`・`music`・`cabin`どうしは直接依存せず、連携は`drive`に置きます。

`TheaCarApplication`が実ナビ・音楽・窓のRuntimeを所有し、GPSを共有します。スマホとAndroid Autoは同じ`NavigationSession`を読みます。Activityや車載Screenの破棄で案内そのものを終了しません。
ホームの`AppGraph.demo()`は別のデモ構成です。iOSの地図・TTS・バックグラウンド案内は未実装です。
旅の記録は`AndroidMemoryRuntime`が保存先を1つ所有します。`androidApp`が案内状態と`TripRecorder`を接続し、到着時の保存・ステッカー画面・旅の記録タブが同じ保存先を使います。ナビ画面は`NavigationTripRecording`を通じて保存結果・再試行・画面遷移を扱い、記録の保存実装には依存しません。保存に失敗した記録はプロセス内に保持し、到着画面から再試行できます。地名は目的地検索で選んだ名前を使い、ステッカー画面で書き換えられます。

入力の確定は`feature/memory`の`TripMemoryEditor`、再送は`TripMemoryPublisher`、HTTPは`data/memory`の`TripMemoryApi`が担当します。写真の加工・描画・ファイル保存はAndroidアダプターへ置き、記録一覧では表示するカードだけ写真を読み込みます。[旅のステッカー](memory-sticker.md)に操作と保存形式をまとめています。

## 入力と推論

![入力・推論・統合・判断・実行](media/architecture/06-input-inference.png)

共有パイプラインは`core/sensing`の`SensorSource`、`core/inference`の`InferenceEngine`、`feature/perception`の推論制御、`data/perception`の環境統合に分かれています。実マイクは`core/sensing`へ接続済みです。`InferenceEngine`を使う実モデル推論とカメラ入力は未接続で、この経路は合成フレームで検証しています。

`SharedSensorFeed`は取得を一回にまとめ、不変フレームを配信します。最後の購読終了で機器を解放し、生フレームを再配信しません。推論結果には時刻・モデル版・信頼度を持たせ、期限切れの判断を使い続けません。推論モデルはプレイヤーや車両APIを直接操作しません。

車内のマイクは`platform/android-music`の`AndroidMicrophone`が`SensorSource<AudioFrame>`として提供します。`androidApp`の`TheaCarApplication`がこれを`SharedSensorFeed`で包んで1つだけ持ち、使う機能へ渡します。収音は購読者がいる間だけ動きます。音楽側は`feature/music`の`CabinSpeechMonitor`がこれを購読して発話の有無と声・雑音のレベルだけを取り出し、発話割合をBGM選曲や旅の記録へ、声の大きさをBGMの音量の調整へ使います。PCMは保存・送信しません。会話・画像は経路採点に使っていません。

音量の判断は`feature/music`の`CabinVolumeController`、端末への反映は`AndroidMusicVolume`が担当します。`CabinVolumeSession`が再生先・マイク・再生状態を確認し、減音の開始と解除を管理します。

## 状態・失敗・資格情報

- 状態はFlowで配信し、UIは状態と操作コールバックを扱います。Composableから直接HTTPや機器を操作しません。
- 新しい検索で旧要求をキャンセルし、世代番号・revisionで古い結果の反映を防ぎます。おすすめ候補と案内中の経路は別に保持します。
- Gatewayの操作は受理・成功応答を確認して状態へ反映します。キャンセルを通常の操作エラーへ変換しません。
- 案内セッションはメモリ内で、プロセス終了後の復元・端末間同期は未実装です。旅の保存・公開は別の永続化経路です。
- 経路トークンは端末保存値を優先し、なければビルド時の`ROUTING_TOKEN`を使います。埋め込み値はAPKから抽出できるため、少人数の開発利用に限ります。公開配布には利用者別認証・失効管理が必要です。
- 経路・Spotifyの保存鍵は分離します。窓の操作成功は送信成功までで、窓の位置・挟み込みは検知できません。提案からの自動開閉は接続しません。

窓の機器は`BleWindowGateway`がサービスUUIDで探索し、BLEで接続します。機器のイベントは`feature/cabin`の`CabinNoticeSource`を通して配信し、端末の読み上げ・スマホ画面・Android Autoがそれぞれ表示します。`androidApp`は接続の組み立てを担当し、TTS実装は`platform/android-window`へ置きます。

BLEはUUID・機器名で接続先を照合します。機器認証は未実装です。操作と制約は[窓の操作](window-control.md)を参照してください。

`AndroidSpotifyRuntime`は部品の組み立て、再生方式の切り替え、ログアウト時の後始末を担当します。

| 処理 | 担当 |
| --- | --- |
| 認証・認証設定 | `SpotifyAuthController`・`SpotifyAuthSettings` |
| 再生状態の監視 | `PlaybackMonitor` |
| 景観BGMの実行 | `BgmPlaybackLoop` |
| 位置・気象確認とおまかせ再生の定期更新 | `DriveEnvironmentController` |
| 発話判定 | `CabinSpeechMonitor` |
| 音楽ログの送信・再送 | `MusicLogUploader` |
| 音楽ログのHTTP・端末保存 | `data/bgm`の`MusicLogApi`・`FileMusicLogStore` |

依存境界は`verifyModuleBoundaries`で検査します。変更と確認手順は[開発ガイド](../CONTRIBUTING.md)を参照してください。

## 図の更新

図の編集用SVGは`docs/media/architecture/`、生成元は`tools/architecture-diagrams/render.py`です。PillowとmacOSのヒラギノフォントを使います。PDFは配布用の画像ベースです。

```sh
.venv-scenic/bin/python tools/architecture-diagrams/render.py
.venv-scenic/bin/python -m unittest discover -s tools/architecture-diagrams -p 'test_*.py'
```

### プレイリスト入力

`feature/music` の `PlaylistInputSession` が取込状態・所有者・推薦モードを所有し、`data/spotify` が曲の取得、`data/bgm` が注釈を担当します。取込結果はメモリ内で保持し、音楽画面とナビで共有します。操作と制限は[景観BGMの推薦](bgm-recommendation.md#プレイリストを入力する)を参照してください。
