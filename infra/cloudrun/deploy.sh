#!/usr/bin/env bash
# Cloud Run へのビルド・push・デプロイ。秘密の値は画面に出さず、ファイルから Secret Manager へ登録する。
#   PROJECT_ID=my-project DATA_DIR=~/Desktop/TheaCar/infra/valhalla/data/hokkaido infra/cloudrun/deploy.sh <setup|secrets|build|deploy|all>
set -euo pipefail

: "${PROJECT_ID:?PROJECT_ID を指定してください}"
REGION="${REGION:-asia-northeast1}"
REPOSITORY="${REPOSITORY:-tabireco}"
SERVICE="${SERVICE:-tabireco-routing}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-tabireco-routing}"
TAG="${TAG:-$(date +%Y%m%d%H%M%S)}"
PLATFORM=linux/amd64
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/infra/cloudrun"
SECRETS_DIR="${SECRETS_DIR:-$ROOT/infra/valhalla/.secrets}"
REGISTRY="$REGION-docker.pkg.dev/$PROJECT_ID/$REPOSITORY"
SERVICE_ACCOUNT="$SERVICE_ACCOUNT_NAME@$PROJECT_ID.iam.gserviceaccount.com"

# Secret Manager へ登録するシークレット名（service.yaml と一致させる）
ROUTING_AUTH_SECRET=tabireco-routing-auth
BGM_ENV_SECRET=tabireco-bgm-env

setup() {
  gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com --project "$PROJECT_ID"
  gcloud artifacts repositories describe "$REPOSITORY" --location "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1 ||
    gcloud artifacts repositories create "$REPOSITORY" --repository-format docker --location "$REGION" --project "$PROJECT_ID"
  gcloud iam service-accounts describe "$SERVICE_ACCOUNT" --project "$PROJECT_ID" >/dev/null 2>&1 ||
    gcloud iam service-accounts create "$SERVICE_ACCOUNT_NAME" --display-name "tabireco routing" --project "$PROJECT_ID"
  gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet
}

# 値は --data-file で読ませるだけで、表示しない。2回目以降は新しいバージョンを追加する。
put_secret() {
  local name="$1" file="$2"
  [ -f "$file" ] || { echo "見つかりません: $file" >&2; exit 1; }
  if gcloud secrets describe "$name" --project "$PROJECT_ID" >/dev/null 2>&1; then
    gcloud secrets versions add "$name" --data-file "$file" --project "$PROJECT_ID" >/dev/null
  else
    gcloud secrets create "$name" --data-file "$file" --replication-policy automatic --project "$PROJECT_ID" >/dev/null
  fi
  gcloud secrets add-iam-policy-binding "$name" --member "serviceAccount:$SERVICE_ACCOUNT" \
    --role roles/secretmanager.secretAccessor --project "$PROJECT_ID" >/dev/null
  echo "登録しました: $name"
}

secrets() {
  put_secret "$ROUTING_AUTH_SECRET" "$SECRETS_DIR/routing-auth.conf"
  if [ -f "$SECRETS_DIR/bgm.env" ]; then
    put_secret "$BGM_ENV_SECRET" "$SECRETS_DIR/bgm.env"
  else
    echo "bgm.env が無いので、資格情報なしの bgm.defaults.env を登録します。" >&2
    put_secret "$BGM_ENV_SECRET" "$ROOT/infra/valhalla/bgm.defaults.env"
  fi
}

build() {
  : "${DATA_DIR:?DATA_DIR（tiles/ と *.sqlite があるディレクトリ）を指定してください}"
  [ -f "$DATA_DIR/valhalla.json" ] || { echo "DATA_DIR に valhalla.json がありません: $DATA_DIR" >&2; exit 1; }
  local build_push=(docker buildx build --platform "$PLATFORM" --push)
  "${build_push[@]}" -f "$HERE/valhalla.Dockerfile" --build-context "data=$DATA_DIR" -t "$REGISTRY/valhalla:$TAG" "$HERE"
  "${build_push[@]}" -f "$HERE/scenic.Dockerfile" --build-context "data=$DATA_DIR" --build-context "code=$ROOT/infra/scenic" \
    -t "$REGISTRY/scenic:$TAG" "$HERE"
  "${build_push[@]}" -f "$HERE/gateway.Dockerfile" --build-context "valhalla=$ROOT/infra/valhalla" -t "$REGISTRY/gateway:$TAG" "$HERE"
  "${build_push[@]}" -t "$REGISTRY/bgm:$TAG" "$ROOT/infra/bgm-api"
  echo "pushed tag: $TAG"
}

deploy() {
  local rendered
  rendered="$(mktemp)"
  trap 'rm -f "$rendered"' RETURN
  PROJECT_ID="$PROJECT_ID" REGION="$REGION" REGISTRY="$REGISTRY" TAG="$TAG" SERVICE_ACCOUNT="$SERVICE_ACCOUNT" \
    envsubst '${PROJECT_ID} ${REGION} ${REGISTRY} ${TAG} ${SERVICE_ACCOUNT}' < "$HERE/service.yaml" > "$rendered"
  gcloud run services replace "$rendered" --region "$REGION" --project "$PROJECT_ID"
  gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT_ID" --format 'value(status.url)'
}

case "${1:-}" in
  setup) setup ;;
  secrets) secrets ;;
  build) build ;;
  deploy) deploy ;;
  all) setup; secrets; build; deploy ;;
  *) echo "usage: $0 <setup|secrets|build|deploy|all>" >&2; exit 2 ;;
esac
