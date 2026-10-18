# ルート連動の音楽計画

ナビでルートの案内を開始し、音楽画面で再生先を選んで「おまかせ再生」を押します。「再生順とタイミングを見る」で、セット再生開始からの予定を確認できます。案内中のルートがない場合は従来の天気ベースの選曲です。

## 計画の入力

- GPSに基づく案内中のルート・残り時間・再探索の版番号
- Valhallaが返す各案内区間の `time` と `highway`、経由地のルート形状上の位置
- 現在地の天気・昼夜・気象モデルの周辺標高
- Spotifyの検索結果に含まれる曲長 `duration_ms`

既存のルート情報を使って端末上で選曲します。Spotifyへ送るのは検索語・曲URI・再生端末IDで、座標やルートは送りません。

## 区間と再生順

雨・雪は天気のテーマを優先します。それ以外は到着まで約3分以内をアコースティック、経由地到着の約1分前後をチル、高速道路区間を昼・夜のドライブ向け検索語にします。その他は現在地の天気のテーマを使います。検索語と曲長によるルールベースの選曲です。

各曲は最初から最後まで再生する前提で、次の区間境界・経由地・到着に近い長さの候補を順に選びます。同じ曲を重複させず最大10曲、ルートの残り時間を超えたところで追加を終了します。曲長が不明な曲は3分30秒と仮定し、画面に明示します。検索はテーマごとに行い、同じ計画内では再利用します。最大4テーマ、取得結果は15分・最大8検索語のメモリ内キャッシュです。候補がないルート用テーマは天気の候補へ戻します。

Spotifyへ曲順どおりのURIを一度に渡し、各曲を最後まで再生します。最後の曲が到着後まで続く場合もあります。

## ETAと再計画

`feature/route` の `RouteTiming` がルート形状の距離と区間所要時間から時刻の目安を作ります。区間時間がそろっている場合は合計をルート全体の所要時間に正規化し、各区間内を距離で補間します。欠損や不連続がある場合は全体の距離比へ戻します。ナビの残り時間と音楽の区間計画はこの同じ計算を使用します。リアルタイム渋滞・信号待ち・休憩時間は扱いません。

ルート変更・再探索、元の計画から2分以上のETAずれ、従来の天気更新条件に応じて提案を作り直します。天気を取得中にルートが変わった場合、古いルートの計画で自動再生しません。再計画は提案だけに反映し、既に再生中のセットは変更しません。「この提案で再生し直す」で現在のルートを起点に新しいセットを開始します。

画面の時刻は再生開始からの目安です。一時停止・スキップ・通信遅延などでずれます。計画した順序で聴く場合はSpotifyのシャッフル・リピート・クロスフェードをオフにし、予約キューを確認してください。

## 担当範囲

| モジュール | 所有する処理 |
| --- | --- |
| data/valhalla | 区間時間・高速道路フラグの取得 |
| feature/route | RouteStep / RouteTiming。ルート上の位置と時間の対応 |
| feature/navigation | GPS進捗・案内中の残り時間 |
| feature/drive | NavigationMusicJourney。ナビ状態から音楽用の区間イベントへ変換・形状計算のキャッシュ |
| feature/music | MusicJourney / RouteMusicPlanner / DriveMusicSession。テーマ・曲長・曲順・更新制御 |
| data/spotify | 曲長の取得、並べた曲URIの再生要求、シャッフル状態取得 |
| androidApp | ナビと音楽の入力を接続。音楽からナビへの直接依存はない |

音楽画面を閉じると環境・ルートの監視と再計画は終了します。Spotifyへ渡した曲順はそのまま再生されます。ナビ画面やAndroid Autoからの音楽計画操作、バックグラウンドでの再計画、海岸線・展望台などの景観データ、区間到着時刻の気象予報、自然な曲境界でのキュー自動差し替えは未実装です。

## 検証

```shell
./gradlew -g .gradle-local verifyModuleBoundaries \
  :feature:route:allTests :feature:navigation:allTests \
  :feature:drive:allTests :feature:music:allTests \
  :data:valhalla:allTests :data:spotify:allTests \
  :androidApp:assembleDebug :androidApp:lintDebug
```

単体テストは区間時間の欠損・補間・再探索、曲長に応じた順序、候補不足、キャッシュ、古いルートの計画抑止を確認します。実走行でのタイミングと実Spotifyアカウントの再生検証は別途必要です。

APIの仕様は [Valhalla Route API](https://valhalla.github.io/valhalla/api/route/api-reference/)、[Spotify Search](https://developer.spotify.com/documentation/web-api/reference/search)、[Spotify Start Playback](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback)を参照してください。
