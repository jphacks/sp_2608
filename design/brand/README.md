# 旅れこのアイコン

カーブする道と朝日を組み合わせたマーク。背景は深緑 `#173C32`、道はアイボリー `#F5F2E9`、朝日はサンドゴールド `#E9BD79`。文字を使わず、道のセンターラインは透過の抜きで表現します。

`theacar-icon.svg` は角丸・影・文字を含まない共通原稿です。Android は同じパスを VectorDrawable として使用し、ランチャー側で形状をマスクします。Android 13 以降のテーマアイコンは前景のシルエットを使用します。前景は 108 × 108 の中央の直径 66 の安全領域に収めています。

Androidの前景は`androidApp/src/main/res/drawable/ic_launcher_foreground.xml`、背景色は`values/colors.xml`、テーマアイコンは`drawable/ic_launcher_monochrome.xml`で管理します。形状変更時は共通原稿・前景・単色マスクをそろえます。

プレビューの更新:

```sh
magick -background none design/brand/theacar-icon.svg -resize 432x432 design/brand/theacar-icon-preview.png
```

iOS は現在 Xcode のアプリターゲットが未作成です。ターゲット作成時に共通原稿を 1024 × 1024 の不透明画像へ書き出し、AppIcon に登録します。角丸は画像へ焼き込まず OS に任せます。
