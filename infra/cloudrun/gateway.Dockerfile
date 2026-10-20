# Cloud Run 用の認証ゲートウェイ。infra/valhalla/nginx-public.conf から生成し、設定の二重管理を避ける。
# --build-context valhalla=infra/valhalla で渡す（deploy.sh build）。
FROM nginx:1.28.0-alpine
COPY --from=valhalla nginx-public.conf /tmp/nginx-public.conf
# PC 構成との違いは3点だけ。
#  1. 上流を Docker のサービス名ではなく同一インスタンス内の 127.0.0.1 にし、Docker DNS の resolver を外す
#  2. 同乗者の識別を CF-Connecting-IP ではなく X-Forwarded-For の先頭にする（Cloud Run の前段は Google のフロントエンド）
#  3. 認証用の map は /run/secrets/routing-auth.conf（Secret Manager のマウント）からそのまま読む
# 元の書き方が変わって置換が効かなくなったら、下の grep でビルドが止まる。
RUN set -eu; \
    sed -e '/resolver 127\.0\.0\.11/d' \
        -e 's#http://scenic:8004#http://127.0.0.1:8004#' \
        -e 's#http://valhalla:8002#http://127.0.0.1:8002#' \
        -e 's#http://bgm:8005#http://127.0.0.1:8005#' \
        -e '/Tunnel 経由では/c\    # Cloud Run では接続元が前段のプロキシになるので、X-Forwarded-For の先頭（クライアント）で数える。' \
        -e '/map \$http_cf_connecting_ip/c\    map $http_x_forwarded_for $party_client { "" $binary_remote_addr; "~^ *([^, ]+)" $1; }' \
        /tmp/nginx-public.conf > /etc/nginx/nginx.conf; \
    ! grep -nE '127\.0\.0\.11|cf_connecting|//(scenic|valhalla|bgm):' /etc/nginx/nginx.conf; \
    grep -q 'http_x_forwarded_for' /etc/nginx/nginx.conf; \
    grep -c '127.0.0.1:80' /etc/nginx/nginx.conf; \
    mkdir -p /run/secrets && printf 'map $http_authorization $routing_authorized { default 0; }\n' > /run/secrets/routing-auth.conf; \
    nginx -t; \
    rm -rf /run/secrets /tmp/nginx-public.conf
EXPOSE 8080
