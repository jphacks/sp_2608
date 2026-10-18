# 公開データ

北海道の道路カタログに使うデータの出典・取得方法・用途をまとめる。本人登録・APIキーが不要な公開データを採用する。

初回は[環境構築](environment-setup.md)、評価式は[経路探索アルゴリズム](route-algorithm.md)を参照。以下の個別生成コマンドはデータ更新用。

## 採用元と用途

| 元データ | 収集するもの | 使い方 |
| --- | --- | --- |
| 北海道OSM PBF | 道路・海岸・森林・水域・田園・建物 | 既存の候補抽出・近接・景観の連続性・経路採点 |
| 国土地理院DEM10B標高タイル | 地表標高、周囲250・500・1000mの地形 | 谷の地形・開放性・太陽方向の地形遮蔽を近似 |
| ESA WorldCover 2021 v200 | 樹木・草地・耕作地・建物等・水域・裸地等 | OSMの森林・開けた自然・田園を弱めて補完 |
| OSM道路タグ | 勾配、条件付きアクセス、冬季サービス、除雪、浅瀬等 | 数値として解釈できる勾配は負荷評価へ。条件付きタグは未解釈の注意表示 |
| OSM POI | 展望地点、滝、駐車場、トイレ、給油、充電、休憩所等 | 出典付きの未登録探索候補の台帳。未検証の入口へ自動誘導しない |
| Open-Meteo | 雲量・降水・視程・気温・湿度・雪・風・突風・天気コード・降水確率・日射 | 既存の天気補正、環境表示、モデル値の保存。路面凍結や車両安全性は断定しない |
| 審査済みの道路紹介 | 公式・自治体・観光団体等の道路紹介と対象区間 | 本登録道路48件のランク報酬・区間通過検証。地形やPOIから自動昇格しない。[道路台帳](scenic-road-registry.md) |

[国土地理院の仕様](https://maps.gsi.go.jp/development/demtile.html)、
[タイル一覧](https://maps.gsi.go.jp/development/ichiran.html)、
[コンテンツ利用規約](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)を参照。
出典と加工した主体をAPIのsourcesへ保存し、アプリの根拠詳細にも残す。
不特定多数への公開・製品提供時は、[測量成果の利用手続](https://www.gsi.go.jp/LAW/2930-index.html)に照らした確認も別途行う。

ESAは[公式のデータ取得案内](https://esa-worldcover.org/en/data-access)に掲載された公開AWSファイルを使う。
CC BY 4.0。観測年は2021年。原データは10mだが、処理には提供元の縮小層を使い、約27〜37mの解像度で最近傍参照する。
引用先は[WorldCover 2021 v200](https://doi.org/10.5281/zenodo.7254221)。

## 地形の推定と限界

地理院のPNGを仕様どおり符号付きcmへ復号する。無効画素はNaN、404は未取得とし、標高0で埋めない。
道路周辺に必要な503タイルを列挙し、492ファイルを取得、11タイルは404だった。キャッシュは約47MB。
各ファイルのURL・取得状態・SHA-256と取得日時をmanifestへ記録する。

zoom11は北海道付近で約50〜60m相当。狭い峡谷や道路面の細かな起伏は評価できない。
周囲8方位、それぞれ250・500・1000mの地表高度から、各方向で地形がせり上がる角度を求める。
向かい合う2方向に壁があり、それと直交する方向が開けた位置を谷の候補とする。
平地・山頂・全方向が同じ高さの盆地を、単に標高が高いだけで谷へ分類しない。
必要なサンプルが欠損している場合、谷の推定値は未取得とする。

- 道路サンプル2,620,431件中、標高取得2,620,295件。
- 全方位の計算ができたもの2,381,104件（約90.9%）。
- 谷の推定値0.45以上は77,381件。道路サンプルの件数であり、独立した渓谷の数ではない。
- DEM由来の道路勾配は未採用。道路区間長に対して標高の解像度が不足するため。
- OSMから数値の勾配を読めたものは12道路way。橋・トンネルへ地表の勾配を代入しない。

推定した谷とOSMに明示された谷・渓谷の特徴は最大値で統合し、二重加点しない。
SQLの候補抽出にも使うため、OSMに渓谷名がない道路も探索対象になる。
地形の開放性は森林・建物の情報でさらに控えめにし、海の可視性の確認済みフラグには使わない。
地形に太陽が隠れると推定される場合は朝夕の方位加点をしない。地形情報がない場合もその加点はしない。
8方位・1kmまでの近似であり、遠い山・建物・樹木・防潮堤や光の屈折を完全には扱わない。

## 土地被覆の統合

北海道の道路を含む7タイル、原ファイル合計105,117,938bytesを取得する。
生成済みのlandcover-hokkaido-v3.sqliteでは、2,620,431道路サンプルすべてに有効な分類を付与できた。
DB版はfde0b98b2b461299752290839c2c8211。これは分類を参照できた件数であり、地表の正解率ではない。
森林は道路周囲100m、開けた自然・田園などは周囲500mを各13点でサンプリングする。
有効な分類が80%未満なら、その道路の衛星補完は未取得とする。

採点値はmax(OSM値, 0.75×衛星分類の割合)とし、古い衛星データを現在のOSM情報へ無条件に上書きしない。
衛星の補完分は現在の建物被覆率でさらに抑え、開けた自然・田園の補完は現在の森林率でも抑える。
明示的な補完データの指定がある場合は、その指定を優先する。
衛星の水域を海・湖・川へ自動分類せず、裸地・建物・過去の雪氷を「自然が美しい」として加点しない。
建物等の分類面積率は、OSMの建物被覆率や街並みの美しさとは別物として扱う。
2021年からの伐採・造成などは追跡できていない。分類自体の誤差も残る。

## 周辺施設と道路タグ

context DBには31,297件のOSMノード・wayを収集した。relationsは収集対象外。
同じ実施設を表すノードとポリゴンがある場合もあり、独立した施設の実数ではない。

| 種類 | OSMレコード数 |
| --- | ---: |
| 駐車場 | 17,492 |
| 展望地点 | 427 |
| 滝 | 129 |
| トイレ | 1,476 |
| 給油所 | 1,145 |
| 充電施設 | 47 |
| 休憩エリア | 74 |
| サービス施設 | 168 |

全件navigation_verified=false、opening_hours_verified=false、recommendation_status=unregistered_candidate。
ポリゴンの代表点を車の入口と取り違えず、駐車可能・立ち寄り可能と断定しない。
営業時間文字列の完全な解釈、入口・道路接続・利用可否を検証するまでは、自動の休憩先へ投入しない。
電話番号・個人口コミ本文などは保存していない。

道路属性は12,814wayで追加取得した。条件付き通行・季節性・冬季サービス・浅瀬などは、
該当経路の注意表示へ反映する。これらのタグを公式の実況通行規制の代わりにはしない。
経路の走行条件と接続は引き続きValhallaで検証し、景観推定だけで厳格制約を緩めない。

## 気象の扱い

[Open-Meteoの仕様](https://open-meteo.com/en/docs)に従い、1回の地点取得で必要な変数をまとめて収集する。
負の気温を落とさず、欠損する降雪・積雪を0へ置き換えない。
WMO天気コードは離散値のまま近い時刻を選び、風向は360度の境界をまたぐ円周補間を行う。
霧・降雪・雷雨も環境タイムラインに表示する。気温・雪・風・降水確率などは区間タップで確認できる。

降雪量と降水は時間集計のモデル値、積雪深は地表のモデル値。除雪済みの路面状態とは異なる。
風速を車種や地形暴露の検証なしに「車の安全性」の点数へ変換しない。
予報は最大7地点・取得6秒目安・50kmの適用範囲を維持するため、経路が大きく迂回すると欠損がある。
無料APIの用途・上限は[利用条件](https://open-meteo.com/en/terms)に従う。

## データ状況API

既存の認証付きゲートウェイでGET /route-recommendations/data-statusを使える。
ファイル名やトークンを露出せず、データ版・生成日・観測年・収集件数・未接続の理由を返す。

- loaded: 対応するDBを読み込めた。全地物の網羅や現地検証を意味しない。
- configured: 予報の取得設定が有効。各位置・時刻の取得成功は検索snapshotで確認する。
- unconfigured/unavailable/unverified: 接続なし、未取得、未検証を区別する。
- excluded_by_scope: 対象外。JAXAは本人登録が必要なため採用せず、ESAを使う。
- complete=false: 地域内の全データの取得・検証は未完了。

公式の実況通行規制、紅葉・開花・現況の積雪景観、施設入口・利用可否、実車窓からの眺望は未検証。
[北海道開発局の規制情報](https://www.hkd.mlit.go.jp/ky/kn/dou_iji/ud49g7000000b2dd.html)はAPIへ未接続。

## 生成・起動

コードはオフライン収集・特徴計算と、実行時の読み取り専用DB参照を分離した。
探索中にDEMや衛星画像をダウンロードしない。全sidecarはカタログ署名を検証する。
API応答のsnapshotには各DB版、予報、実装ハッシュ、評価版を保存する。

```sh
export VALHALLA_DATA_DIR="$PWD/infra/valhalla/data/japan-test"
.venv-scenic/bin/pip install -r infra/scenic/requirements-terrain.txt

.venv-scenic/bin/python infra/scenic/import_context.py \
  "$VALHALLA_DATA_DIR/region.osm.pbf" "$VALHALLA_DATA_DIR/scenic-hokkaido.sqlite" \
  "$VALHALLA_DATA_DIR/context-hokkaido-v1.sqlite"

.venv-scenic/bin/python infra/scenic/import_terrain.py \
  "$VALHALLA_DATA_DIR/scenic-hokkaido.sqlite" "$VALHALLA_DATA_DIR/gsi-dem" \
  "$VALHALLA_DATA_DIR/terrain-hokkaido-v1.sqlite" --download --max-tiles 550

.venv-scenic/bin/python infra/scenic/import_worldcover.py \
  "$VALHALLA_DATA_DIR/scenic-hokkaido.sqlite" "$VALHALLA_DATA_DIR/esa-worldcover-2021" \
  "$VALHALLA_DATA_DIR/landcover-hokkaido-v3.sqlite" --download --max-bytes 120000000

export SCENIC_LANDCOVER_FILE=landcover-hokkaido-v3.sqlite
export SCENIC_WEATHER=true
docker compose -f infra/valhalla/compose.yaml \
  -f infra/valhalla/compose.scenic-registry.yaml \
  -f infra/valhalla/compose.scenic-context.yaml \
  --profile scenic up -d --no-deps scenic
```

生成済みのファイルは上書きしない。中断した.buildingも残し、再実行は別の出力名を指定する。
WorldCoverの全7原ファイルが取得済みなら--downloadの代わりに--offlineでネット取得せず再計算できる。
国土地理院は取得済みタイルを再利用するが、404のタイルは再試行する。
地形・周辺情報を使わない起動ではcontext overrideを外す。登録道路DBと水辺DBは従来どおり必要。
compose.yaml単体では予報取得が既定で無効、registry overrideでは既定で有効。上記ではSCENIC_WEATHER=trueを明示し、起動構成による違いを避ける。

## 検証

DEMの符号・無効画素・谷の地形・地形欠損・太陽の遮蔽、カタログ署名、橋への勾配非代入、
古い土地被覆の控えめな補完、水域・市街地の誤分類防止、負の気温・風向・離散天気コード、
取得状況の未完了表示を単体テストする。

```sh
.venv-scenic/bin/python -m unittest discover -s infra/scenic -p 'test_*.py'
SCENIC_EXPECT_DATA=1 SCENIC_EXPECT_ENVIRONMENT=1 \
  SCENIC_CASES=infra/scenic/fixtures/hokkaido-landscape-routing.json \
  node infra/scenic/test-recommendations.mjs
```

実走・季節比較・眺望の検出精度は未検証。
