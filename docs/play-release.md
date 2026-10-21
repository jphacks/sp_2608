# Google Play への配布

旅れこを Play Console の内部テストで配る手順です。Play から入れたアプリは、実車の Android Auto のアプリ一覧に出ます。サイドロードした APK は、DHU には出ても実車の一覧には出ません。

## 1. アップロード鍵を作る（初回だけ）

Play に上げる AAB は、手元のアップロード鍵で署名します。利用者に届くアプリの署名は Google が持つ鍵で行います（Play App Signing）。

鍵はリポジトリの外に作ります。パスワードは `keytool` が聞いてくるので、自分で決めて入力します。

```sh
keytool -genkeypair -v -keystore ~/keys/tabireco-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

鍵ファイルとパスワードは、パスワード管理ツールなどに控えておきます。なくすと、Play Console からアップロード鍵の再設定を申請するまで更新を上げられません。

## 2. 鍵の場所を local.properties に書く

```properties
RELEASE_STORE_FILE=/Users/<ユーザー名>/keys/tabireco-upload.jks
RELEASE_STORE_PASSWORD=<鍵ストアのパスワード>
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=<鍵のパスワード>
```

`local.properties` と `*.jks` は `.gitignore` に入っています。4つのどれかが空のときは、Release ビルドは署名なしで作られます。

## 3. AAB を作る

経路 API などの接続先は、APK と同じく `local.properties` か Gradle プロパティから埋め込まれます。Release ビルドの前に、本番の接続先になっていることを確かめます。

```sh
./gradlew :androidApp:bundleRelease
```

`androidApp/build/outputs/bundle/release/androidApp-release.aab` ができます。

Play には、前に上げたものより大きい `versionCode` の AAB しか上げられません。2回目からは、上げる前に `androidApp/build.gradle.kts` の `versionCode` を1つ上げます。

## 4. Play Console で内部テストに上げる

1. Play Console でアプリを作ります。パッケージ名は `com.tabireco.app` です。最初の AAB を上げると決まり、あとから変えられません。
2. 内部テストのトラックにテスターのメールアドレスを登録し、AAB を上げてリリースします。内部テストは審査なしで配れます。
3. 詳細設定のフォームファクタで Android Auto を追加します。
4. テスター用のリンクから参加し、Play ストアから旅れこを入れます。サイドロードした旅れこは先にアンインストールします。署名が違うため、上書きでは入りません。

Release ビルドは公式の Android Auto 以外の接続元を拒否します（[車載での確認手順](android-auto-setup.md#42-debug-ビルドが必要な理由)）。
