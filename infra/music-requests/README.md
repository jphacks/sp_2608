# 同乗者の曲リクエスト

ホストのAndroidスマホがSpotifyへログインし、同乗者はQRから開くブラウザで曲を検索・追加します。同乗者のSpotifyログインは不要です。最初の追加で選択済みの端末の再生を開始し、以降はその端末のキューへ追加します。

## 受付サーバー

Python 3.9以上で、追加ライブラリなしで起動できます。

```sh
python3 infra/music-requests/server.py --bind 127.0.0.1 --port 8788
```

このHTTPポートを、利用中のリバースプロキシまたはトンネルでHTTPS公開してください。Androidの設定にはパスなしの公開URL（例: `https://music.example.com`）を入力します。プロキシは `Host` と `Origin` の対応を維持し、招待トークンを含むURLやAuthorizationヘッダーをログへ残さないようにします。

受付はメモリ内に保存され、サーバー再起動ですべて終了します。受付ごとの有効期間は最大4時間で、ホストからのポーリングが120秒を超えて途絶えた場合も終了します。検索結果・追加済み履歴は中継しますが、Spotifyの資格情報はホスト端末内に保持します。QRのURLを知っている人は参加できます。

## 動作確認

1. サーバーを起動し、HTTPS URLを用意します。
2. Androidの音楽画面でSpotifyに接続し、再生先を選びます。「このスマホ」を使う場合はSpotifyアプリも必要です。
3. 「みんなで曲を追加」に公開URLを入力し、受付を開始します。
4. 別のスマホでQRを読み取り、曲を検索して追加します。最初の曲が再生され、次の追加が曲を中断せずキューに入ることを確認します。
5. DHUのナビ画面の「曲を追加」でも同じQRが表示されること、ホスト画面を閉じても受付が続くことを確認します。
6. ホスト画面か通知で受付を終了し、参加者が追加できなくなることを確認します。送信済みの曲はSpotifyに残ります。

Spotify操作の失敗や応答不明時は、重複を避けるため追加を自動再送しません。参加者にはエラーを表示します。追加済み履歴はSpotifyへの送信成功を示し、現在のキューや再生完了を示すものではありません。

## 自動検証

```sh
python3 -m unittest discover -s infra/music-requests -p 'test_*.py'
./gradlew verifyModuleBoundaries :data:spotify:testDebugUnitTest :platform:android-music:testDebugUnitTest :androidApp:assembleDebug
```
