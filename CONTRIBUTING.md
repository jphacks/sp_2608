# 開発ガイド

初回は[環境構築](docs/environment-setup.md)を済ませ、[アーキテクチャ](docs/architecture.md)で変更する責務を確認してください。

## 変更する場所

| 対象 | 主なディレクトリ |
| --- | --- |
| ルートの契約・案内状態 | `feature/route`・`feature/navigation` |
| 候補抽出・探索・評価 | `infra/scenic/recommendation` |
| 地理データ・名道の構築 | `infra/scenic/import_*.py`・`infra/scenic/data` |
| API接続・サーバー運用 | `data/valhalla`・`infra/valhalla` |
| 位置・気象 | `core/location`・`core/environment`・`platform/android-location`・`data/weather` |
| 選曲・再生 | `feature/music`・`data/spotify`・`data/bgm`・`platform/android-music`・`infra/bgm-api` |
| 共有入力・推論 | `core/sensing`・`core/inference`・`feature/perception`・`data/perception` |
| 機能間の連携 | `feature/drive` |
| スマホ・車載UI | `platform/android-navigation`・`platform/android-auto`・`feature/dashboard` |
| 窓の操作 | `feature/cabin`・`platform/android-window`・`hardware/window-ai` |
| 旅の記録 | `feature/memory`・`platform/android-memory`・`web` |
| 起動・依存注入 | `androidApp`・`shared`・`iosApp` |

## 依存と状態

契約は利用する機能側が所有し、HTTP・OS・機器の実装は`data`か`platform`へ置きます。機能からデモ実装を参照しません。`route`・`music`・`cabin`の連携は`drive`へ置きます。
新しいGradleモジュールは`settings.gradle.kts`とルートの`allowedModuleDependencies`へ登録します。

UIは状態と操作コールバックを扱います。成功応答・失敗・再試行・キャンセル・競合をテストし、古い検索結果で案内中の経路を上書きしないでください。
公開契約や保存形式を変える場合は利用側とテストも更新します。データ・採点結果が変わる変更では評価版とスナップショットの互換性を確認します。

## テスト

変更したモジュールの単体テストに加え、依存境界を確認します。

```sh
./gradlew -g .gradle-local verifyModuleBoundaries \
  :feature:navigation:testDebugUnitTest :data:valhalla:testDebugUnitTest \
  :feature:music:testDebugUnitTest :feature:drive:testDebugUnitTest \
  :feature:memory:testDebugUnitTest :data:memory:testDebugUnitTest :platform:android-memory:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:lintDebug

.venv-scenic/bin/python -m unittest discover -s infra/scenic -p 'test_*.py'
.venv-scenic/bin/python -m unittest discover -s infra/valhalla -p 'test_*.py'
.venv-scenic/bin/python -m unittest discover -s tools -p 'test_*.py'
```

共通コードを変更した場合はmacOSで対象モジュールの`iosSimulatorArm64Test`と`shared:compileKotlinIosSimulatorArm64`も実行します。ビルド成功と起動成功は別なので、画面・権限・バックグラウンド動作は実機かエミュレーターでも確認します。

起動済みAPIの統合試験は次で実行します。試験用ジョブ・セッションを作るため、利用者の検索・案内と重ねないでください。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py check
SCENIC_EXPECT_DATA=1 SCENIC_EXPECT_ENVIRONMENT=1 \
  SCENIC_CASES=infra/scenic/fixtures/hokkaido-landscape-routing.json \
  node infra/scenic/test-recommendations.mjs
```

BGMと共有ページの検証は、それぞれ[API仕様](infra/bgm-api/docs/API.md)と[Web版の手順](web/README.md)を参照してください。

## リポジトリに残すもの

現行の仕様・構築手順・データの出典・テストを残します。作業日報、検索トレース、計測結果、旧版の資料、途中のメディアはコミットしません。仕様変更は既存の資料へ反映し、同じ説明の別ファイルを増やさないでください。

`tools/test_repository.py`で、追跡対象のソース・資料に競合マーカーが残っていないことと、Markdownのローカルリンク・見出し参照を確認します。マージ後も実行してください。

資格情報、ローカル設定、PBF・生成DB、端末の位置履歴・写真・音声はコミットしません。外部データ・SDK・ロゴの出典と利用条件は削除しないでください。
