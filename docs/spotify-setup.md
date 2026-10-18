# Spotify 音楽連携

ホームの「Spotifyと連携・音楽を開く」から使用します。Spotify Premium の個人利用向けです。旅れこ は検索と Spotify Connect の再生操作を行い、音源の配信・DRM・実際の音声再生は Spotify アプリに任せます。経路サーバーや Cloudflare Tunnel を経由しません。

## 初回設定

音楽画面の「Spotifyの初期設定」が手順を1〜5で案内します。Client ID は画面で保存できるので、**再ビルドは不要**です。

1. 画面の「Spotify Developer Dashboard を開く」から Create app を押します。アプリ名と説明は自由です。
2. Redirect URI に次を完全一致で登録します。末尾の `/` は追加しません。画面の「コピー」から貼り付けてください。

   ```text
   com.theacar.android.spotify://oauth/callback
   ```

3. 「Which API/SDKs are you planning to use?」で Web API を選び、Save を押します。
4. 作成したアプリの Settings にある Client ID を、画面の入力欄に貼って「保存して次へ」を押します。英数字32文字です。Client Secret は使いません。
5. 同じ画面の User Management で、使用する Premium アカウントを許可ユーザーに登録します。アプリ所有者にも Premium が必要です。新規アプリの開発モードは最大 5 ユーザーです。[開発モードの条件](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)
6. Android 端末に Spotify アプリをインストールし、同じ Premium アカウントでログインします。
7. 旅れこの音楽画面で「Spotifyにログイン」を押し、ブラウザで許可します。パスワードは Spotify の画面にのみ入力します。
8. Spotify で一度曲を再生してから 旅れこへ戻り、再生先を「更新」します。対象端末を選び、曲名・アーティストを検索して「この曲を再生」を押します。

保存した Client ID は端末内に Android Keystore の鍵で暗号化して保存します（`spotify-client.enc`、バックアップ対象外）。アンインストール・データ消去で消えます。別のアプリIDへ入れ替えると、前のIDで取得したログイン情報は破棄され、再ログインが必要です。

ビルド時に決め打ちしたい場合は、従来どおり `local.properties` に書けます。画面で保存した値があればそちらが優先されます。Client ID は公開識別子です。Client Secret・アクセストークン・リフレッシュトークンは書きません。

```properties
SPOTIFY_CLIENT_ID=Dashboardに表示された32文字のClientID
```
カスタムスキームの戻り先は [Spotify のセキュリティ更新](https://developer.spotify.com/blog/2025-02-12-increasing-the-security-requirements-for-integrating-with-spotify)と Android のインテントフィルターに合わせています。公開版で独自ドメインを用意する場合は検証済み HTTPS App Links への移行を検討してください。

## このスマホで再生する（App Remote SDK）

再生先の「このスマホ」は、端末の Spotify アプリを [App Remote SDK](https://developer.spotify.com/documentation/android) で操作します。Spotify アプリを開かなくても裏で起動して再生するので、ナビの案内開始と同時に景観BGMを流せます。Web API の Spotify Connect は起動中の Spotify アプリしか再生先として返さないため、この経路を加えています。

追加の設定（初回だけ）:

1. 端末に Spotify アプリを入れ、旅れこで使う Premium アカウントでログインします。
2. Dashboard のアプリの Settings で「Which API/SDKs」に **Android** を追加し、Android packages に次を登録します。
   - Package name: `com.theacar.android`
   - SHA1 fingerprint: ビルドに使う署名の SHA-1。デバッグ署名は次で確認できます。署名は PC ごとに異なるので、別の PC でビルドする人はそれぞれ登録します。

   ```shell
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
   ```

3. 最初に「このスマホ」で再生するとき、Spotify アプリの許可画面が1回だけ開きます。許可すると 旅れこ に戻ります。

SDKはMavenで配布されていないため、`third_party/maven`に取得元の版を置いています（[出典と利用条件](../third_party/README.md)）。同梱版は0.8.0です。

| 表示 | 原因 | 対処 |
| --- | --- | --- |
| Spotifyアプリが入っていません | 未インストール | 「入手」から入れて「再試行」 |
| Spotifyアプリにログインしていません | アプリが未ログイン | Spotify アプリでログインして「再試行」 |
| Spotifyアプリでの許可が必要です | 初回の許可を閉じた、または Dashboard にパッケージ名・SHA-1 が無い | 「再試行」で許可画面を開く。開かない場合は Dashboard の登録を確認 |

再生状態はこのスマホへ最後に操作したときだけ App Remote から取得し、それ以外の端末は従来どおり Web API から取得します。Spotify アプリが起動していると Web API にも同じ端末が別名で表示されますが、どちらを選んでも同じアプリで鳴ります。

## 操作と制限

### プレイリストを景観BGMへ取り込む

音楽画面の「みんなのプレイリスト」に表示名とURLを入力する。
既にログイン済みなら「読取権限を追加してSpotifyに再ログイン」で
`playlist-read-private`・`playlist-read-collaborative`を許可する。
ログイン中のアカウントが所有または共同編集できるプレイリストが対象で、他人の公開URLだけでは内容を取得できない。
同乗者のリストはそのアカウントを共同編集者に追加してから取り込む。
複数人の曲は重複をまとめ、`blend`またはリスト内に限定する`playlist`で景観と合わせて選ぶ。
仕様・上限・注釈用のサーバー設定は[プレイリスト入力](bgm-recommendation.md#プレイリストを入力する)を参照。

### 取得した現在地・気象情報を確認

音楽画面の「現在地・天気を取得して確認」は、Spotify未連携・再生前でも利用できます。位置情報を許可すると現在地を最大15秒待ち、天気を取得します。この操作では選曲・再生・既存の選曲計画の変更は行いません。同じ丸め座標の天気は1分間再利用し、おまかせ再生の取得結果も同じ表示へ反映します。

画面には天気・小数1桁の気温・昼夜・端末で取得した日時を表示します。「取得情報の詳細を見る」で以下を確認できます。

- 端末の最終位置の緯度・経度、端末が報告した水平精度、位置更新からの経過秒数
- 気象取得に使用した端末位置、丸めて送信した座標、APIが返した予報地点
- 気象モデルの周辺標高・対象時刻と、端末で取得した時刻（タイムゾーン付き）
- 選曲計画に使用した位置・気象の対象時刻、計画作成時刻、参照ルート・到着見込み

気象はモデルの推定値で、GPSと予報地点はずれる場合があります。住所・地名の逆引きは未実装です。位置の鮮度・精度・権限不足は画面に表示し、再取得に失敗した場合は前回の値と取得時刻を残します。

天気の確認で更新された情報と、既に選曲に使った情報は別々に表示します。音楽画面を閉じると進行中の確認処理をキャンセルし、確認用のGPS監視を解放します。案内中のナビが所有するGPS監視は止めません。位置・気象の確認結果はメモリ内のみで、アプリの再起動後には復元しません。

### 現在地・天気でおまかせ再生

案内中のルートがある場合は、高速道路区間・経由地・到着見込みと曲長も使って再生順を計画します。[ルート連動の音楽計画](route-music.md)に仕組みと制限をまとめています。

再生先を選び、「おまかせ再生」を押してください。位置情報の許可後、現在地の天気を取得し、検索した最大10曲を選択端末で自動再生します。公式Spotifyアプリで一度再生しておく必要があります。初期状態ではオフで、起動だけでは位置取得・天気通信・自動再生しません。

| 条件（上から優先） | 選曲テーマ |
| --- | --- |
| 雪 | 冬・ピアノ |
| 雨・雷雨 | 雨・チル |
| 現在地が夜 | 夜・シティポップ |
| 周辺の標高が600m以上 | アコースティック・フォーク |
| 晴れの昼 | 晴れ・ドライブ |
| その他 | インディー・メロウ |

天気・昼夜・周辺標高から検索語を決めるルールベースの選曲です。景観データを使う[景観BGM](bgm-recommendation.md)とは別の機能です。

天気は[Open-Meteo Forecast API](https://open-meteo.com/en/docs)から取得し、送信座標は小数点以下2桁に丸めます。Spotifyへは検索語だけを送ります。GPS・天気はメモリ内で扱い、位置履歴には保存しません。無料APIは非商用向けです。[利用条件・プライバシー](https://open-meteo.com/en/terms)

音楽画面表示中は15分ごと、または前回取得から2分以上経過して10km以上移動したときに天気を更新します。同じ検索語の候補は15分間再利用します。開始時の曲は一度にセットし、Spotify側が順次再生します。環境・ルートの変化は提案を更新するだけで、再生中の曲を自動差し替えしません。変更した候補を使う場合は「この提案で再生し直す」を押します。

画面を閉じる、端末をロックする、再生先・曲を手動で変える、連携を解除する、通信・権限エラーが起きると自動更新を終了します。Spotifyの再生と、案内中のナビのGPS監視は続きます。

### その他

- 検索、再生先の選択、曲の再生、一時停止／再開、前／次の曲、現在の再生状態に対応します。検索は明示操作のみ、1 回最大 10 曲です。検索のページ送りやプレイリスト一覧は未実装です。景観BGMでは共有URLからプレイリスト全曲を取り込めます。`playlist-read-private` と `playlist-read-collaborative` を追加しているため、以前の連携で権限エラーが出る場合は連携し直してください。
- 再生先がない場合は Spotify を開いて再生し直します。制御不可の端末は候補から除外し、使用中でない端末を勝手に選択しません。指定した端末が別の PC・スピーカーなら音はそちらから出ます。
- 再生状態は Spotify の応答を表示します。操作の HTTP 成功だけで「再生中」にしません。反映に時間差がある場合は「更新」で確認できます。
- 音楽画面を開いている間だけ 15 秒ごとに状態を確認します。バックグラウンドの定期取得は行いません。画面を閉じても Spotify 側の再生は継続します。
- ログイン情報は Android Keystore の鍵で暗号化し、バックアップ対象外の端末内領域へ保存します。通常は再起動・更新後も復元し、アクセストークンを自動更新します。サーバー側の失効・有効期限・権限変更時は再ログインが必要です。[PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)、[トークン更新](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens)
- 「連携解除」は端末内の Spotify 認証情報と画面状態を削除します。Spotify の再生停止や Spotify アカウント側でのアプリ許可取消は行いません。許可取消は Spotify アカウントのアプリ管理から行います。
- Android Auto 専用の音楽テンプレート・iOS のログイン／プレイヤー画面は未実装です。車載側の音楽操作は当面 Spotify 公式の Android Auto 対応を使います。KMP の HTTP・認証更新・検索・再生操作は iOS にも共有可能です。
- ホームのデモはSpotifyと未接続です。景観BGMには[会話に合わせた音量調整](bgm-recommendation.md#会話に合わせた音量調整)があります。音声コマンド・曲の音源解析は未実装です。公開・商用利用は[Developer Policy](https://developer.spotify.com/policy)に従います。

## エラー

| 表示 | 確認するもの |
| --- | --- |
| 初期設定 | 音楽画面で Client ID を保存する。ビルド時設定を使う場合のみ再ビルド |
| ログインが必要 | 認証キャンセル、state 不一致、10 分以上経過、トークン失効。再ログイン |
| 操作を許可しない | Premium、Dashboard の許可ユーザー、アプリの API 設定 |
| 再生できる端末がない | 同じアカウントの Spotify アプリを開いて一度再生し、再生先を更新 |
| 利用上限 | Retry-After の待機時間が過ぎるまで通信を控える。自動連続リトライはしない |
| 保存・復元できない | 端末の空き容量、データ消去や Keystore の状態。再ログイン |

## 設計と検証

| モジュール | 責務 |
| --- | --- |
| feature/music | MusicService、MusicSession、曲・端末・再生状態。HTTP や Android に依存しない |
| data/spotify | Spotify Web API、PKCE の認可 URL／コード交換、トークン更新、HTTP エラー変換 |
| platform/android-music | ブラウザ／コールバック、PKCE の乱数と SHA-256、暗号化保存アダプター、Android UI・画面ライフサイクル |
| platform/android-security | Keystore と AtomicFile による認証ファイルの暗号化。経路 API の既存保存形式・鍵名は維持 |
| core/environment | WeatherSource / LocalWeather。気象入力の契約 |
| data/weather | Open-Meteoへの座標丸め・天気コード変換・鮮度検証。Spotify認証を受け取らない |
| data/weather | WeatherRepository。取得結果・元の入力位置・取得時刻を配信し、同じ地域の並行取得と1分キャッシュを管理 |
| feature/music | DriveMusicPolicy / DriveMusicSession。選曲ルール・更新間隔・候補と自動再生開始の制御 |
| platform/android-location | ナビ・音楽で共有するAndroid GPSアダプター。所有者ごとの取得・解放 |

OAuth の state と verifier は毎回生成し、暗号化して 10 分だけコールバックに使用します。戻り先・state・重複パラメーターを確認し、検証済みの認可要求はコード交換前に消費します。秘密情報をログや UI 保存状態に入れません。音楽トークンを経路 API・地図へ送らず、経路 API のトークンも Spotify へ送りません。

```shell
./gradlew -g .gradle-local verifyModuleBoundaries \
  :feature:music:allTests :data:spotify:allTests \
  :data:weather:allTests :core:location:allTests \
  :platform:android-music:testDebugUnitTest \
  :platform:android-navigation:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:lintDebug
```

実機ではログイン→再起動→検索→端末選択→再生／停止→連携解除を確認します。公式ロゴの出典は[BRANDING.md](../platform/android-music/BRANDING.md)に記録しています。
