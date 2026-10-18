# 経路・景観・BGMサーバー

Docker ComposeでValhalla、景観推薦、BGM、認証ゲートウェイを起動します。初回は[環境構築手順](../../docs/environment-setup.md)に従ってデータと認証を準備してください。

構築済みの環境では、プロジェクトルートで実行します。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py up
.venv-scenic/bin/python infra/valhalla/manage.py check
```

`manage.py`は`.routing.json`の保存先とDB設定を使い、認証・データ整合性・APIを検査します。`up`はコンテナを再作成するため、検索・案内中には実行しません。

- `127.0.0.1:8003`：認証付き入口。外部利用のTunnelはここへ接続する
- `127.0.0.1:8002`：認証なしのローカル試験用。外部へ公開しない
- Valhallaタイル：経路計算用。背景地図はMapLibre側で別に取得する
- `.secrets/`：経路認証とサーバー用Spotify資格情報。Gitへ含めない

停止・ログ・Tunnel・APK接続は[環境構築](../../docs/environment-setup.md)、データの用途と利用条件は[公開データ](../../docs/public-routing-data.md)、名道の更新は[登録基準](../../docs/scenic-road-registry-policy.md)を参照してください。
