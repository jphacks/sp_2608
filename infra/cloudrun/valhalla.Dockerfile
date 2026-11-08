# Cloud Run 用の Valhalla。タイルと設定をイメージへ焼き込む（Cloud Run には PC のようなボリュームがない）。
# データはリポジトリ外にあるので、ビルド時に --build-context data=<データディレクトリ> で渡す（deploy.sh build）。
FROM ghcr.io/valhalla/valhalla:3.5.1@sha256:4ac3d13d1019f9a130e9e6b6924c16ea55da0fa51e3653039152e33ad4095661
# 実行に必要なものだけ。region.osm.pbf・*.work.sqlite・DEM・バックアップは入れない。
COPY --from=data valhalla.json admin.sqlite /data/
COPY --from=data tiles /data/tiles
ENTRYPOINT ["valhalla_service"]
CMD ["/data/valhalla.json", "2"]
