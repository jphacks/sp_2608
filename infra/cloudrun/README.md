# 経路・景観・BGMサーバー（Cloud Run）

[Docker Compose版](../valhalla/README.md)と同じ構成を、Cloud Runの1サービス（asia-northeast1）に複数コンテナで載せます。PCとTunnelが動いていなくても、リクエストが来たときに起動します。アプリ側の変更はなく、ベースURLをCloud RunのURLにするだけです。

| コンテナ | 役割 | 内部ポート | 上限 |
| --- | --- | --- | --- |
| gateway | 入口（nginx）。Bearer認証と回数制限。`infra/valhalla/nginx-public.conf`から生成 | 8080（外部公開） | 0.5 CPU / 128Mi |
| valhalla | 経路計算。タイルをイメージに焼き込み | 8002 | 2 CPU / 2Gi |
| scenic | 景観推薦・場所検索。コードとDBをイメージに焼き込み | 8004 | 1 CPU / 1Gi |
| bgm | 景観BGM・同乗者リクエスト（`infra/bgm-api`のDockerfileを流用） | 8005 | 1 CPU / 512Mi |

- コンテナ同士は同じインスタンス内の`127.0.0.1`で通信する。起動順はvalhalla、scenic、bgm、gatewayで、`service.yaml`の`container-dependencies`とstartupProbeで守る
- インスタンスは最大1・最小0、CPUは常時割り当て。推薦ジョブとBGMの同乗者リクエストがメモリ・ローカルsqliteに状態を持ち、レスポンス後も計算が続くため。スケールインやデプロイで進行中のジョブ・同乗者リクエストは消える
- 経路トークンは`manage.py`が作った`infra/valhalla/.secrets/routing-auth.conf`をSecret Managerから`/run/secrets/routing-auth.conf`にマウントして使う。BGMの資格情報（`bgm.env`）も同様
- 同乗者の`/party/`の識別は`X-Forwarded-For`の先頭を使う（`CF-Connecting-IP`ではない）
- 起動時に約1.5GBのイメージを読み込むため、最小0での初回リクエストは遅い。`/status`が通るまで待つ

## 準備

Secret Managerのシークレット名はコードで固定している。

| シークレット | 中身 | 登録元 |
| --- | --- | --- |
| `tabireco-routing-auth` | nginxの認証map | `infra/valhalla/.secrets/routing-auth.conf` |
| `tabireco-bgm-env` | `SPOTIFY_CLIENT_ID`など | `infra/valhalla/.secrets/bgm.env`（無ければ`bgm.defaults.env`） |

Dockerのbuildx、gcloud（`gcloud auth login`済み）、envsubstを使う。データは[環境構築](../../docs/environment-setup.md)の4〜6章で作ったディレクトリ（`tiles/`と`*.sqlite`があるもの）を`DATA_DIR`に指定する。PBF・作業用sqlite・DEM・バックアップはイメージに入れない。

```sh
export PROJECT_ID=your-gcp-project
export DATA_DIR=/path/to/data/hokkaido
infra/cloudrun/deploy.sh setup     # API有効化・Artifact Registry・実行用サービスアカウント
infra/cloudrun/deploy.sh secrets   # 値は表示せず、ファイルから登録（再実行で新バージョン）
export TAG=$(date +%Y%m%d%H%M%S)   # build と deploy で同じタグを使う
infra/cloudrun/deploy.sh build     # linux/amd64 でビルドしてpush
infra/cloudrun/deploy.sh deploy    # service.yaml を反映し、サービスURLを表示
```

`all`で一括実行もできる。デプロイ後は、表示されたURLと`routing.token`で`manage.py check --endpoint <URL>`を実行し、未認証は401、認証ありは200になることを確認する。Cloud Runの呼び出し元確認は`service.yaml`で無効にしている。有効のままだと、アプリのBearerトークンをGoogleのトークンとして検証して401を返す。認証はnginxのBearerトークンで行う。

## 設定の対応

- 設定・DB・環境変数は`infra/valhalla/compose.yaml`と`manage.py`の`FILES`に対応する。DBの版を上げたら`scenic.Dockerfile`と`service.yaml`のファイル名も更新する
- `gateway.Dockerfile`は`nginx-public.conf`を`sed`で変換する。元の書き方が変わって置換できなくなるとビルドが失敗する
- イメージにデータを焼き込むため、データを更新したら`build`と`deploy`をやり直す
