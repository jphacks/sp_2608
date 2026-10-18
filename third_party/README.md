# 同梱している外部ライブラリ

Maven Central 等で配布されていないため、取得元の版をそのまま置いている。更新するときは同じ形式で版ごとのディレクトリを追加し、`settings.gradle.kts` の取得範囲（`includeGroup`）と依存の版を合わせる。

| ライブラリ | 版 | 取得元 | SHA-256 | ライセンス |
| --- | --- | --- | --- | --- |
| Spotify App Remote SDK | 0.8.0 | https://github.com/spotify/android-sdk/releases/tag/v0.8.0-appremote_v2.1.0-auth （`spotify-app-remote-release-0.8.0.aar`、132,749 bytes） | `b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198` | Apache-2.0（同梱 AndroidManifest.xml の表記）。利用は Spotify Developer Terms に従う |

`.pom` はこのリポジトリで作成した最小の記述で、SDK が実行時に使う Gson への依存だけを宣言している。

## Valhallaのタグ変換

`infra/valhalla/lua/graph-3.5.1.lua`は[Valhalla 3.5.1のgraph.lua](https://github.com/valhalla/valhalla/blob/3.5.1/lua/graph.lua)を同梱したもの。MITライセンス（[同梱ライセンス](../infra/valhalla/lua/LICENSE.valhalla)）。SHA-256は`eda40a1a943e5f8347768018e03bb5500d4c09422334a28b91c40ab68f2ca8e5`。

道路タイルの構築時に`hokkaido-speeds.lua`を追加し、速度未登録の道路だけを補完する。
