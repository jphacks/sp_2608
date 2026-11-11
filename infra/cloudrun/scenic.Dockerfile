# Cloud Run 用の scenic。PC では infra/scenic をマウントし、DB を /data から読んでいる。ここでは両方を焼き込む。
# --build-context code=infra/scenic --build-context data=<データディレクトリ> で渡す（deploy.sh build）。
FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
ENV PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY --from=code . /app
RUN rm -rf /app/test_*.py /app/*.mjs /app/fixtures /app/__pycache__
COPY --from=data scenic-hokkaido.sqlite places.sqlite road-recommendations-research-20261111-v8.sqlite \
     landscapes-hokkaido-v4.sqlite terrain-hokkaido-v1.sqlite context-hokkaido-v1.sqlite landcover-hokkaido-v3.sqlite /data/
# 推薦ジョブが「どの経路データで計算したか」のハッシュを取るためのファイル。無いものは無いまま扱われる。
RUN --mount=from=data,target=/d mkdir -p /routing/tiles && cp /d/valhalla.json /routing/ && \
    for f in routing-source.json routing.lua tiles/tile_manifest.json; do if [ -f "/d/$f" ]; then cp "/d/$f" "/routing/$f"; fi; done
CMD ["python", "-B", "server.py"]
