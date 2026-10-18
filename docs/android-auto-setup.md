# Android Autoの動作確認

Windows 11とAndroid Studioを使い、DHUでの車載画面と実機ナビへの接続を確認します。先に[環境構築手順](environment-setup.md)の10章まで完了してください。

対象はAndroid Autoです。Android Automotive OS（AAOS）には対応していません。設定名が異なる場合は[9章](#9-エラーの切り分け)を参照してください。

## 0. 確認の流れ

| 段階 | 作業 | 確認すること |
| --- | --- | --- |
| [1〜2章](#1-何が必要か) | DHU の導入 | `--version` が `2.0-windows` を返す |
| [3章](#3-スマートフォンを開発モードにする) | スマホ側の開発設定 | 5277 番の待ち受けが確認できる |
| [4章](#4-アプリをインストールする) | APK の導入 | `pkgFlags` に `TEST_ONLY` がない |
| [5章](#5-接続して-dhu-を起動する) | 接続と起動 | DHU に車載画面が描画される |
| [6章](#6-車載画面を確認する) | 動作の確認 | `TheaCarService` が車からバインドされている |
| [7章・任意](#7-画面構成とセンサーを変える) | 解像度・入力方式・センサー | 別構成で再起動できる |
| [8章・任意](#8-実機の-android-auto-ナビで確認する) | 実機ナビでの確認 | 車載ランチャーからアプリが起動する |

つまずいたときは[9章](#9-エラーの切り分け)、最後の確認は[10章](#10-完了の確認表と検証範囲)を使います。

DHUは車載機をPCで再現するツールです。アプリはUSB接続した実機スマートフォンで動きます。

## 1. 何が必要か

| 項目 | 用途・前提 |
| --- | --- |
| Desktop Head Unit (DHU) | 車載機の代行。Android SDK の SDK Tools から導入 |
| Android SDK Platform-Tools | `adb`。接続とポート転送 |
| Android 端末 | Android 8 / API 26 以上。USB デバッグを許可 |
| Android Auto アプリ | Play ストア版。プリインストールのスタブ版では動きません |
| USB ケーブル | データ転送対応のもの。充電専用では `adb devices` に出ません |
| JDK 21 / Android SDK 36 | APK のビルド。[環境構築手順1章](environment-setup.md#1-何が必要か)と同じ |
| 経路 API のトークン | **地図とターンバイターンを見る場合のみ。**[6.3節](#63-地図が出る条件)を参照 |

確認済みの構成:

| 対象 | バージョン |
| --- | --- |
| DHU | `2.0-windows`、Build `2022-03-30-438482292` |
| adb | `1.0.41` / `35.0.1-11580240` |
| Android Auto（スマートフォン） | `17.8.663874` |
| Gradle / JDK | `8.11.1` / Zulu 21 |

Bluetooth・Wi-Fi 投影の無線接続は本手順では使いません。USB で接続します。

## 2. DHU を入れる

Android Studio の **SDK Manager → SDK Tools** タブで「**Android Auto Desktop Head Unit Emulator**」にチェックを入れ、Apply します。

「Android Auto API Simulators」はメディア・メッセージングAPIのテスト用で、DHUとは別物です。

導入先と動作を確認します（PowerShell）。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe" --version
```

次のように出れば導入できています。

```text
Android Auto - Desktop Head Unit
  Build: 2022-03-30-438482292
  Version: 2.0-windows
```

SDK の場所を変更している場合は、SDK Manager に表示される実際のパスを使います。`local.properties` の `sdk.dir` と同じ場所です。`adb` が見つからない場合は Platform-Tools を PATH へ追加します。

```powershell
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
adb version
```

DHU 2.0（2022年ビルド）とAndroid Auto 17系で接続を確認しています。

## 3. スマートフォンを開発モードにする

Android 本体の開発者向けオプションと、**Android Auto アプリの中にあるデベロッパー向け設定は別物**です。両方必要です。

### 3.1 Android 本体の USB デバッグ

設定 → デバイス情報 → ビルド番号を 7 回タップ → 設定 → システム → 開発者向けオプション → **USB デバッグ** をオンにします。

PC へ USB で接続し、端末に出る「USB デバッグを許可しますか？」を許可します。

```powershell
adb devices -l
```

端末が `device` として 1 行出ることを確認します。`unauthorized` のまま進めません。`offline` はケーブルか USB モードの問題です。空行しか出ない場合は、ケーブルがデータ転送に対応しているかを先に疑います。

### 3.2 Android Auto アプリのデベロッパーモード

Android Auto の設定画面を開きます。本体の設定アプリからは階層が機種ごとに違うため、adb で直接開くほうが確実です。

```powershell
adb shell am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity
```

一番下までスクロールし、「**バージョンと権限情報**」を **10 回連打**します。「デベロッパーモードが有効になりました」と表示されます。

この操作で有効になるのは Android Auto アプリ内の設定だけです。本体の開発者向けオプションの中に Android Auto 関連の項目は増えません。

### 3.3 アプリモードを「デベロッパー」にする

右上の **⋮** → 「**デベロッパー向けの設定**」を開き、「**アプリモード**」を「**デベロッパー**」へ変更します。

既定は「リリース」です。この状態では **Play ストアで公開済みのアプリしか車載画面の一覧に出ません**。サイドロードした 旅れこを表示させるには必須の設定です。サービスがバインドされないので、ログにも何も出ません。

### 3.4 ヘッドユニットサーバーを起動する

同じ「デベロッパー向けの設定」の中の「**ヘッドユニットサーバーを起動**」をタップします。スマートフォンが 5277 番ポートで待ち受け、DHU からの接続を受け付けます。

待ち受けを確認します。

```powershell
adb shell "cat /proc/net/tcp /proc/net/tcp6" | Select-String 149D
```

`149D`（16 進で 5277）を含む行の状態が `0A`（LISTEN）であれば起動しています。何も出ない場合はタップが効いていません。

この設定は Android Auto アプリを再インストールすると消えます。3.2 から やり直してください。

## 4. アプリをインストールする

### 4.1 Android Studio の Run ボタンを使わない

**Android Studio の Run / Debug でインストールした APK は、車載画面の一覧に出ません。** Android Studio は実行用の APK に `android:testOnly="true"` を付与し、Android Auto は `TEST_ONLY` が立ったアプリを対象から除外します。エラーは出ず、ただ一覧に現れません。

コマンドラインでビルドしてインストールします。

```powershell
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

```powershell
adb shell dumpsys package com.theacar.android | Select-String pkgFlags
```

`TEST_ONLY` が**含まれていない**こと、`DEBUGGABLE` が**含まれている**ことを確認します。

### 4.2 Debug ビルドが必要な理由

`TheaCarService` のホスト許可は次のようになっています。

```kotlin
override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) {
    HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
} else {
    HostValidator.Builder(applicationContext).build()
}
```

**Release ビルドは許可リストが空なので、DHU も実車も含めてすべてのホストを拒否します。** 検証は Debug ビルドで行います。公開前には正式ホストの許可リストを設定する必要があり、現在は未設定です。

`ALLOW_ALL_HOSTS_VALIDATOR` は接続元の車載機を検証しません。Debug ビルドの APK を配布しないでください。

### 4.3 リポジトリ側の前提（変更不要）

次の宣言は `platform/android-auto` に入っています。新規セットアップで追加する作業はありません。内容を変えると車載画面が出なくなるため、記録として残します。

| 宣言 | 場所 | 役割 |
| --- | --- | --- |
| `androidx.car.app.NAVIGATION_TEMPLATES` | `AndroidManifest.xml` | ナビ用テンプレートの使用 |
| `androidx.car.app.ACCESS_SURFACE` | `AndroidManifest.xml` | 車載画面へ地図を描画する `SurfaceCallback` の登録 |
| `CarAppService` + `category.NAVIGATION` | `AndroidManifest.xml` | ナビアプリとしての登録 |
| `automotive_app_desc.xml`（`<uses name="template" />`） | `res/xml` | テンプレート方式のアプリであることの申告 |

`ACCESS_SURFACE` がないと、車載画面を開いた瞬間に `SecurityException: The car app does not have a required permission: androidx.car.app.ACCESS_SURFACE` でクラッシュします。`TheaCarSession` が車載画面を作るときに `setSurfaceCallback` を呼ぶためです。この 2 つの権限は車載接続時に自動で付与され、ユーザーへの確認は出ません。

付与状態の確認:

```powershell
adb shell dumpsys package com.theacar.android | Select-String "androidx.car.app"
```

## 5. 接続して DHU を起動する

### 5.1 ポート転送

```powershell
adb forward tcp:5277 tcp:5277
```

USB を抜き差ししたり端末を再起動した場合はやり直します。設定済みの転送は `adb forward --list` で確認できます。

### 5.2 DHU を起動する

```powershell
cd $env:LOCALAPPDATA\Android\Sdk\extras\google\auto
.\desktop-head-unit.exe
```

**自分のターミナルから対話的に実行してください。** DHU は標準入力でコマンドを受け取る対話型プログラムなので、標準入力が繋がっていない環境（スクリプト経由、出力だけを取得する実行）から起動すると、何も表示せず即座に終了します。起動しない原因として最も分かりにくい部分です。

成功すると次のように出て、別ウィンドウに車載画面が開きます。

```text
Android Auto - Desktop Head Unit
...
[I]: Connecting over ADB to localhost:5277...
[I]: connected.
```

既定で adb 転送を使うため、`--adb` の指定は不要です。別のポートを使う場合だけ `-a 5277` のように渡します。

### 5.3 初回の同意

初回接続では、スマートフォンに「Android Auto へようこそ」の確認画面が出ます。「**続行**」を押し、要求される権限を許可します。ここを放置していると DHU は `waiting for phone` のまま進みません。

スマートフォンの画面が消灯していると同意画面に気づけません。画面を点けた状態で起動してください。

## 6. 車載画面を確認する

DHU のウィンドウを見るだけでも確認できますが、記録を残す場合と、描画が止まっているのか接続が切れているのかを切り分ける場合は adb を使います。

### 6.1 サービスが車からバインドされているか

最も確実な判定です。

```powershell
adb shell dumpsys activity services com.theacar.android
```

`ServiceRecord{... com.theacar.auto.TheaCarService}` があり、`recentCallingPackage=com.google.android.projection.gearhead` が出れば、Android Auto が旅れこをナビアプリとして起動しています。

この出力が空の場合、アプリは車から認識されていません。[3.3節](#33-アプリモードをデベロッパーにする)のアプリモードと、[4.1節](#41-android-studio-の-run-ボタンを使わない)の `TEST_ONLY` を順に確認します。

### 6.2 車載画面を取得・操作する

車載画面はスマートフォンの仮想ディスプレイとして存在します。まず ID を調べます。

```powershell
adb shell dumpsys display | Select-String "mDisplayId|uniqueId|state"
```

gearheadが所有し、`state ON`になっているものが車載メイン画面です。IDは接続のたびに変わります。以下の`112`を、表示されたIDに置き換えてください。

```powershell
adb shell screencap -d 112 -p /sdcard/car.png
adb pull /sdcard/car.png
```

```powershell
adb shell input -d 112 tap 400 291
```

`-d` を付けないとスマートフォン本体の画面を操作してしまいます。DHU のウィンドウを直接クリックしても同じ操作になるので、スクリプト化しない場合は adb は不要です。

クラッシュの確認:

```powershell
adb logcat -d -b crash | Select-String -Context 0,25 "com.theacar"
```

古いクラッシュログと混同しないよう時刻を確認します。必要なら`adb logcat -c`でログを消してから再現します。

### 6.3 地図が出る条件

接続に成功しても、既定では車載画面に地図は出ません。`TheaCarSession` がホーム・ルート確認・案内の3画面を切り替えます。

| 状態 | 車載画面 |
| --- | --- |
| 接続先（`VALHALLA_URL`）が未設定 | ホーム。「ルートサーバーが設定されていません」 |
| 案内を開始していない（IDLE） | ホーム。最近の目的地を一覧し、車載から選べる |
| 経路を確認中（PREVIEW / PREVIEWING） | ルート確認。距離・所要時間・到着時刻と主な景観の内訳、「案内開始」 |
| 案内中（GUIDING） | 案内画面。ターンバイターン + 到着見込み + 「終了」 |

**「ルートサーバーが設定されていません」が出ている状態も、接続自体は成立しています。** エラーではありません。

地図には拡大・縮小・現在地のボタンを置いています。パンモードだけでは倍率を変えられない車載機があるためで、DHU でも同じボタンから操作します。案内中は現在地を追い、速度が上がるほど引いて傾けます。**倍率を手で変えると追従を解除します。**そのまま追従を続けると、次の位置で速度に合う倍率へ戻ってしまうためで、「現在地」のボタンで追従へ返ります。経路の景観が分かる場合は、ルート線を景観の分類で塗り分け、海が開ける地点の手前と、名前の付いた道に入ったときだけ数秒の知らせを出します。

地図・ターンバイターン・到着見込みまで確認するには、経路サーバーが動いていて、スマートフォン側で目的地を選び経路のプレビューを出している必要があります。経路 API のトークンが未設定だと「設定から経路 APIのトークンを確認してください。」で止まります。[ナビの操作](navigation.md)と[環境構築手順10.3節](environment-setup.md#103-トークンを保存する)に従ってトークンを保存してください。

目的地の**検索はスマートフォン側で行います**。車載画面から選べるのは、スマートフォンで案内を開始したことのある最近の目的地だけです。

## 7. 画面構成とセンサーを変える

### 7.1 設定ファイル

`extras/google/auto/config/` に既製の設定があります。

| ファイル | 解像度 | 入力 | 用途 |
| --- | --- | --- | --- |
| `default.ini` | 800x480 | タッチ | 既定。指定しないとこれ |
| `default_720p.ini` | 1280x720 | タッチ | 一般的な車載機に近い |
| `default_1080p.ini` | 1920x1080 | タッチ | 大画面 |
| `default_wide.ini` | 横長 | タッチ | 横長パネル |
| `default_6in.ini` | 小型 | タッチ | 小さい画面での崩れ確認 |
| `touchpad.ini` | 800x480 | タッチパッド | タッチ非対応機の操作 |
| `rotary.ini` | 800x480 | ロータリー | ダイヤル操作機 |
| `hybrid.ini` | 800x480 | 併用 | タッチ + コントローラー |
| `default_sensors.ini` | 800x480 | タッチ | 速度・燃料・走行距離・加速度・ジャイロ・方位・料金カードを有効化 |

```powershell
.\desktop-head-unit.exe -c config\default_720p.ini
```

入力方式だけを変える場合は `-i touch` / `-i rotary` / `-i hybrid` でも指定できます。**解像度と入力方式を変えたときは、レイアウトの崩れと、タッチなしでの操作可否を確認してください。** 車載機の入力方式は選べません。

### 7.2 対話コンソール

DHU を起動したターミナルでコマンドを入力できます。一覧は `help`、個別の説明は `help <コマンド>` で出ます。

DHU 2.0 が持つ機能は、昼夜モードの切り替え、タップとキーコードの注入、位置・速度・走行距離・燃料・航続距離・加速度・ジャイロ・方位・料金カード・運転制限の設定、マイク入力の wav 再生、映像フォーカスの切り替え、スリープ、終了です。コマンド名はバージョンで変わるため、`help` の出力に従ってください。

夜間モードと走行中の操作制限も、このコンソールから切り替えて確認します。アプリでの検証は未実施です。

### 7.3 走行を模擬する

机の上では車が動かないため、速度で倍率と傾きが変わること、景観の知らせが出ることを確かめられません。Debug ビルドには、案内中の経路の上を走ったことにして位置を作る口があります。測位の代わりに位置を差し込むだけで、案内の進み方は実機と同じです。

先に車載画面で案内を開始してから実行します。

```powershell
adb shell am broadcast -n com.theacar.android/.debug.DemoDriveReceiver -a com.theacar.debug.DRIVE --ei kmh 60
adb shell am broadcast -n com.theacar.android/.debug.DemoDriveReceiver -a com.theacar.debug.DRIVE --ez stop true
```

**受信口は `-n` で名指しします。** アクション名だけの放送は Android 8 以降のバックグラウンド制限でアプリに届かず、`Broadcast completed: result=0` だけが返って何も起きません。

`--ei kmh` が目標の速さです。止まった状態から15秒かけてそこまで上げ、その後も緩やかに上下します。`15` なら寄った地図、`100` なら引いて傾いた地図になります。終点まで走ると自動で止まります。

止めた直後は測位が戻るまで数秒かかります。**経路を引き直すのはそのあとにしてください。** 受信口は `androidApp/src/debug/AndroidManifest.xml` にしか登録していないため、Release ビルドでは位置を差し込めません。

## 8. 実機の Android Auto ナビで確認する

DHU で確認した後に行います。DHU と実車で挙動が違う部分（音声、実 GPS、切断、画面サイズ）があるためです。

1. スマートフォンを PC から外し、USB で車載機に接続します。
2. 車載機の**アプリ一覧（ランチャー）**から 旅れこを探して起動します。
3. 一覧に出ない場合は[3.3節](#33-アプリモードをデベロッパーにする)のアプリモードを確認します。これが「リリース」のままだと実車でも出ません。

**スマートフォンを車に繋いでいる間は PC の adb が使えません。** ログを後から回収するため、接続前にバッファを広げておきます。

```powershell
adb logcat -G 16M
adb logcat -c
```

車から戻ったら PC へ繋ぎ直して取り出します。

```powershell
adb logcat -d > car-session.log
```

実車で USB デバッグ付きの端末を接続する場合、車載機側が MTP を要求して接続が不安定になることがあります。接続が切れる場合は USB の設定を「ファイル転送」にします。

**運転中にアプリの検証をしないでください。** 停車して、同乗者に操作してもらうか、エンジンをかけた停車状態で確認します。

## 9. エラーの切り分け

| 表示・症状 | 確認と対処 |
| --- | --- |
| DHU を起動しても何も起きない | 標準入力のない環境から起動した。自分のターミナルから対話的に実行する |
| `waiting for phone` のまま | 3.4 のヘッドユニットサーバーが起動していない。5277 の待ち受けを確認。スマホの同意画面が放置されていないか見る |
| `Unable to connect to phone` | `adb forward` が未設定か、USB の抜き差しで消えた。再設定する |
| `adb devices` が空 | 充電専用ケーブル、USB デバッグ未許可、USB モードが充電のみ |
| `unauthorized` | 端末の許可ダイアログを承認していない。`adb kill-server` 後に再接続 |
| 車載画面の一覧にアプリがない | アプリモードが「リリース」/ APK に `TEST_ONLY` が付いている。4.1 の手順で入れ直す |
| 一覧にあるが起動すると落ちる | `adb logcat -b crash`。`ACCESS_SURFACE` の `SecurityException` は 4.3 を確認 |
| 一覧にあるが開くと黒いまま | Release ビルドを入れている。`HostValidator` が拒否している |
| 「停車中にスマートフォンで…」しか出ない | **正常。**地図を出す条件は 6.3 を参照 |
| 「設定から経路 APIのトークンを確認してください。」 | 経路 API のトークンが未設定・不正。[10.3節](environment-setup.md#103-トークンを保存する) |
| 地図だけ出ない | 背景地図は別配信。`MAP_STYLE_URL` とネットワークを確認。経路 API のトークンでは直らない |
| Android Auto の設定項目が本書と違う | アプリの更新で名前・場所が変わる。「デベロッパー」「ヘッドユニット」を含む項目を探す |
| Android Auto 自体が起動しない | Play ストア版が消えてスタブ版（`AndroidAutoStub`）に戻っている。Play ストアから入れ直す |
| 設定が全部消えた | Android Auto を再インストールするとデベロッパーモードごとリセットされる。3.2 からやり直す |
| AAOS エミュレーターで動かない | 対象外。Android Auto と Android Automotive OS は別物 |

`pm uninstall-system-updates` で Android Auto をスタブ版へ戻すと、Play ストアから入れ直すまで一切動作しません。SIM もモバイル通信もない端末では、Wi-Fi を有効にしてから再インストールします。復旧に時間がかかるので、切り分けの手段として実行しないでください。

## 10. 完了の確認表と検証範囲

- DHU が `2.0-windows` を返す。
- スマートフォンが 5277 番で待ち受けている。
- APK に `TEST_ONLY` がなく `DEBUGGABLE` がある。
- `dumpsys activity services` に `TheaCarService` と `recentCallingPackage=...gearhead` が出る。
- 車載画面にクラッシュなく画面が描画される。
- 地図まで確認する場合は、経路の確認中に地図が出る。

DHUでは、ホームの一覧・ルート確認・案内画面の描画、地図、ターンバイターン、到着見込み、地図のボタン（拡大・縮小・現在地）、[7.3節](#73-走行を模擬する)の模擬走行による追従の倍率と傾きまで確認済みです。実機ナビでは一覧表示と起動まで確認しています。

未確認の範囲:

- 実機ナビでの地図・ターンバイターン・到着見込み・地図のボタン。
- 景観によるルート線の塗り分けと、海が開ける地点・名前の付いた道の知らせ。景観エンジンが経路の景観を返す実走行でのみ出ます。
- 実走行、道路のマッチング、日本語 TTS、バックグラウンド、画面の再生成、車載切断からの復帰。
- 夜間モード、走行中の操作制限、800x480 以外の解像度、タッチ以外の入力方式。
- Release ビルドの正式ホスト許可リスト。**未設定のため Release は車載で起動しません。**
- 車載画面からの窓の操作。`platform:android-window` は Android Auto へ接続していません（[窓の操作](window-control.md)）。
