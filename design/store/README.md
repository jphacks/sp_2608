# Google Play の掲載画像

フィーチャーグラフィックとスクリーンショットは Claude Design のキャンバスで作りました。素材はロゴ、八雲町の朝焼けの写真、PV のアプリ画面、写真ACの北海道の道の写真3枚です。

| 写真 | 撮影地（写真ACの題名とタグによる） | 写真ACの番号 | 投稿者 |
| --- | --- | --- | --- |
| 雪の道と霧氷 | 十勝 | [4401349](https://www.photo-ac.com/main/detail/4401349) | makieni |
| 紅葉の森の道の空撮 | 阿寒横断道路 | [35463264](https://www.photo-ac.com/main/detail/35463264) | makieni |
| 海沿いの道の空撮 | 黄金道路（広尾町〜えりも町） | [35447950](https://www.photo-ac.com/main/detail/35447950) | makieni |

写真ACの規約（2026-11-03 に確認）では、制作物の一部として使うことは商用でも自由で、クレジットは要りません。写真そのものを公開・配布することは禁止です。そのため、元の写真はリポジトリにもキャンバスにも置かず、書き出した画像だけを残します。

| ファイル | 用途 | 仕様 |
| --- | --- | --- |
| `icon-512.png` | アプリのアイコン | 512 × 512、32ビット PNG（アルファあり）、1024KB 以下 |
| `feature-graphic-1024x500.png` | フィーチャーグラフィック | 1024 × 500、24ビット PNG（アルファなし） |
| `screenshots/` | スクリーンショット5枚 | 1080 × 1920、24ビット PNG（アルファなし） |
| `make_images.py` | アイコンを作るスクリプト | |
| `source/photo.png` | 紙の質感に加工した朝焼けの写真 | 1108 × 1206 |

## アイコン

朝日の前に、ロゴの「旅」の1字を白で置きました。レコードの溝は写真が透けて見える形で、中心の赤だけロゴのままです。角丸は Play が付けるので、画像には入れません。

Play のアイコンはランチャーのアイコンと揃える決まりです。このアイコンはまだランチャーには入れていません。採用するときは、`design/brand/` の原稿と `androidApp/src/main/res/` のアイコンも同じ絵に替えます。

字の輪郭はロゴの画像から取り出し、レコードだけ同じ寸法で描き直しています。ロゴや写真を差し替えたら、次のコマンドで書き出します。

```sh
python3 design/store/make_images.py
```

## フィーチャーグラフィック

calimoto の作りに合わせました。黄金道路の海沿いの道の写真の上に、ロゴ、機能の3行、斜めのスマホ2台を置いています（2026-11-03 に阿寒の版と比べて社長が選んだ）。

## スクリーンショット

Roadtrippers の作りに合わせました。1枚ごとに地の色を変え、大きな見出しの下にスマホを置き、道の写真を切り抜いた形を重ねています。地の色はデザインのルールの `sea`、`sunset`、`paper`、`mist`、`mountains` です。

## スマホの枠

Android Studio に入っている Pixel 9 の公式の枠（`plugins/android/resources/device-art-resources/pixel_9/back.webp`）を使っています。画面の表示領域は 1080 × 2424 です。PV の画面は実機より短いので、1.05倍にして左右を切り、上下は見出しの帯とタブの色を伸ばしました。ステータスバーの偽物は描いていません。

## 書き出し方

キャンバスを直したら、`.dc.html` を Chrome のヘッドレスで開いて実寸の画像にし、ImageMagick でアルファを外します。

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --allow-file-access-from-files \
  --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 --virtual-time-budget=8000 \
  --screenshot=/tmp/main.png "file://<キャンバスのファイル>/project/MainRoad.dc.html"
magick /tmp/main.png -alpha off -define png:color-type=2 design/store/feature-graphic-1024x500.png
```
