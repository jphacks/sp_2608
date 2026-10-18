# 景観道路の登録・更新・運用

## 目的

紹介資料と対象範囲を確認した道路を登録し、ルーティングに使う。登録範囲と、区間ごとの景観評価は別に管理する。日帰り・宿泊モデルは登録対象外。

## 本登録の条件

1. 公式機関・自治体・公的機関・観光団体・地域の観光運営主体・道路管理者などが、その道路を紹介している。
2. 道路ごとに対象資料を明示する。周辺の岬・滝・展望台・登山道が紹介されているだけでは、車道の根拠にしない。
3. 道路の名前・対象範囲・対応するOSMの路線名や番号・注意事項を原稿に記録する。全線と対象地域内の区間を区別する。
4. 形状を照合し、通常の道路区間は道路網上で連続している。離れた断片を直線でつないで連続と判定しない。
5. 確認済みの延長に対して明らかに短い照合や、過大な照合は保留とする。
6. 明示的な保留理由・要確認指定がなく、以上の検証に合格したものだけをactiveにする。

出典の発行主体・本文・対象区間は人が確認する。口コミや一般記事は補助資料とする。

## ランク

| 内部値 | 表示 | 重み | 条件 |
| --- | --- | ---: | --- |
| signature | 秀逸な道 | 1.0 | 公式認定済みの対象区間。候補区間や国道全線へ認定を拡張しない |
| panorama | 絶景ロード | 0.6 | 道路紹介の一次資料に加えて、独立した道路紹介の一次資料・編集記事・適格な道路口コミのいずれかで裏づけ |
| recommended | おすすめロード | 0.3 | 道路紹介の一次資料による推薦根拠を持つ |
| scenic | 景観ロード | 0.1 | 道路紹介の一次資料を持つ本登録道路。地形からの自動登録枠ではない |

秀逸な道以外は旅れこ独自の編集区分で、行政の等級ではない。重みは推薦への寄与であり、美しさの客観的な順位ではない。
おすすめロードと景観ロードは、紹介内容を審査して手動で振り分ける。

### 追加・再審査の記録

道路紹介の本文を読み、本登録の条件とランクの根拠を確認する。
原稿の`research_review_required_since`以降に再審査する道路には、`research_review`を必須とする。
道路紹介の本文位置と確認した事実、選定理由、範囲の根拠、重複確認、注意事項、確認日を記録する。
地理スコア・検索順位・アクセス数・URLへの接続成功を採用理由にしない。

- おすすめロード: 一次資料がドライブ・観光道路として積極的に紹介している。
- 景観ロード: 一次資料が車道の沿道・車窓景観を具体的に説明するが、上記の推薦まで確認できない。
- 絶景ロード: 積極的な紹介に加え、上表の独立した裏づけを必要とする。

審査結果を`driving_evidence`に記録する。おすすめ・絶景には積極的な道路紹介を必須とし、同じ発行主体の記事は独立した推薦に数えない。
2026-10-17審査分は旧様式のまま保持する。追加・再審査では実際の確認日と新様式を使う。

絶景ロードの口コミによる裏づけは、道路そのものを対象とし、5点尺度で20件以上かつ
`(件数 × 評点 + 20 × 3.5) / (件数 + 20) >= 3.9`の場合のみ使う。
これは少数の満点評価を抑える編集上の初期ルールで、統計的な信頼下限ではない。
登山・牧場・峠の展望体験を現道全体の評点にしない。同一運営・転載は独立した推薦に数えない。

## 未登録の探索候補

海岸線までの距離、森林・自然の被覆率などは、既存のOSM地理カタログで引き続き評価する。
紹介資料がなくても、時間・道路条件を満たす道路はルートの探索対象にできる。

| 扱い | 本登録道路 | 未登録の探索候補 |
| --- | --- | --- |
| ルートへの採用 | 可能 | 可能 |
| 海・森林などの景観評価 | 使用 | 使用 |
| 推薦ランク・推薦報酬R | 使用 | 付与しない |
| ランク付き道路マーカー | 表示対象 | 表示しない |
| 台帳の本登録件数 | 含める | 含めない |

未登録候補を全件永続化する新しいDBや管理画面は追加していない。
地形条件で選ばれた候補はリクエストごとに抽出する。本登録審査で保留になった道路原稿は、別途pendingとして残す。

## 形状の管理と公開判定

### 秀逸な道

[北海道開発局の認定一覧](https://www.hkd.mlit.go.jp/ky/kn/dou_kei/slo5pa0000009at1.html)と、
[札幌市公開の認定区間線形](https://ckan.pf-sapporo.jp/dataset/scenicbyway/resource/fee75228-c8ff-43b6-9c53-b67a7389b2ad)を使う。
公開線形から30m以内、道路方向差30度以内、道路小区間の80%以上が線形周囲に重なることを条件に照合する。
必要な路線番号も確認し、参照線上の25m間隔のサンプルによる対応率が90%未満なら保留とする。

認定は一つの道路名だけでなく、複数路線の区間群を指定する場合がある。
そのため通常道路の「接続成分は一つ」という制約は一律に課さず、公開線形に対応する部分を別形状として保持する。
公式資料の指定そのものが一本の連続路線である、と言い換えない。
上下線や短い並行路を拾う場合があるため、対応率とは別に形状を確認する。

### 一般の道路・区間

OSMの路線名・別名と対象範囲を使う。明示した路線番号も併用し、トンネルなどの名称欠落で道路が切れないようにする。
路線番号だけで国道全線へ拡大しない。範囲内の小区間の両端が対象範囲に入る場合だけ採用する。
道路網の接続は端点座標を小数7桁に丸めて検査する。橋の立体交差などを近接だけでつながった扱いにしない。
接続成分が複数なら保留。分岐・環状路・上下線は形状部分に分け、すべての道路小区間を一度ずつ保存する。

原稿のextent.minimum_metersを過少照合の検出に使う。
全道路へ一律の最低距離は課さない。これは編集上の検査値で、行政の合格基準や全線一致率ではない。
現在の下限はエサヌカ線12km、江花4.25km、ジェットコースターの路3.8km、白い道2.55km、
ナイタイ高原道路5.1km、天に続く道26.7km。端点を完全に確定できたことを意味せず、資料と実際の端点の確認も必要。
特にジェットコースターの路は公式紹介の道路名・写真の確認と、OSM愛称部分の確認までであり、
全線の端点・延長が未確定のため、保留にしている。
地理的な特徴の弱さで本登録形状を切り取らない。市街地やトンネルは道路の一部として残し、景観評価を別に変化させる。

天に続く道のみ、出典付きの起点・直線方向・28.1kmという条件からOSM接続網を追跡する既存方式を使用する。
これは公式認定線形ではない推定範囲であり、手法と照合延長を台帳に残す。

## 管理ファイル

| ファイル・テーブル | 内容 |
| --- | --- |
| infra/scenic/data/hokkaido-recommended-roads.json | 人が審査する原稿。道路・出典・対象範囲・ランク・保留理由 |
| scenic-hokkaido.sqlite | 道路サンプルの座標・地理特徴・道路属性 |
| 推薦DBのrecommendation | 原稿、active/pending、検査結果 |
| membership | 本登録道路と道路サンプルIDの対応。保留道路は含めない |
| road_part | 接続順に並べた座標と道路サンプルID。保留道路も監査用に保持 |
| source | 出典URL・発行主体・版・利用条件・内容確認メモ |
| metadata | 選定基準・形状検査の版・地図版・生成時刻 |
| docs/scenic-road-registry.md | 実際のDBから生成した全道路の台帳 |

原稿のroad_introduction_source_idsはsource_idsの一部を指す。
紹介資料のsubjectはroad_introduction、publisher_typeは許可した主体区分とする。
公園全体・周辺スポット・管理概要などの参考資料はbackgroundとし、独立した一次紹介の件数にも加えない。

同じ物理区間に複数の推薦が重なれば、最高ランクのみを報酬へ計上する。再通過で推薦報酬を稼げない。

## 再生成と検証

プロジェクトルートで実行する。Python環境はinfra/scenic/requirements.txtの依存関係を使う。
PBFや公開ZIPを取得し直す場合は、内容と利用条件を確認してから採用する。
以下の出力名がすでに存在する場合は、必ず別名を指定する。

```sh
export VALHALLA_DATA_DIR="$PWD/infra/valhalla/data/japan-test"
export SCENIC_REGISTRY_FILE=road-recommendations-research-20261018-v7.sqlite

.venv-scenic/bin/python infra/scenic/import_recommended_roads.py \
  "$VALHALLA_DATA_DIR/region.osm.pbf" \
  "$VALHALLA_DATA_DIR/scenic-hokkaido.sqlite" \
  infra/scenic/data/hokkaido-recommended-roads.json \
  "$VALHALLA_DATA_DIR/$SCENIC_REGISTRY_FILE" \
  --official-sections "$VALHALLA_DATA_DIR/recommendation-sources/route-20260624.zip" \
  --model-courses "$VALHALLA_DATA_DIR/recommendation-sources/plan-20260624.zip" \
  --require-official-complete

.venv-scenic/bin/python -m unittest discover -s infra/scenic -p 'test_*.py'

.venv-scenic/bin/python infra/scenic/check_recommended_routes.py \
  "$VALHALLA_DATA_DIR/scenic-hokkaido.sqlite" \
  "$VALHALLA_DATA_DIR/$SCENIC_REGISTRY_FILE"
```

--require-official-completeは認定15区間の欠落・保留を拒否し、一般道路の保留は許容する。
--require-completeは保留が一件でもあれば生成を拒否する。
失敗時は元DBを上書きせず、.buildingファイルが残る場合がある。原因を確認し、別の出力名で再試行する。

実経路チェックは各本登録道路の地上の短区間をValhallaで計算し、推薦表示・報酬・制約を確認する。
道路全線の走行可能性、当日の開通、長距離ルートの探索成功を証明する検査ではない。

台帳と形状は同じDBから出力できる。

```sh
.venv-scenic/bin/python infra/scenic/export_road_registry.py \
  "$VALHALLA_DATA_DIR/$SCENIC_REGISTRY_FILE" > docs/scenic-road-registry.md

.venv-scenic/bin/python infra/scenic/export_road_registry.py \
  "$VALHALLA_DATA_DIR/$SCENIC_REGISTRY_FILE" --format geojson \
  > "$VALHALLA_DATA_DIR/road-registry-review-v2.geojson"
```

GeoJSONは監査用で、保留道路もstatus=pendingとして含む。本登録だけの地図として無条件に表示しない。
書き出し時に欠落区間を補間しない。写真・地図画像・記事本文・個人口コミ本文は保存・再配布しない。

## サーバーへの適用と起動

生成と検査に成功してから、進行中の検索がないことを確認して適用する。
Valhallaの道路グラフの再生成、トークンの再発行、APKの変更は不要。

同じカタログから地形・水辺・土地被覆などの追加DBを生成済みであることを確認する。[環境構築](environment-setup.md)に従って構築した環境では、`infra/valhalla/.routing.json`の`files.SCENIC_RECOMMENDED_ROADS`を検査済みDBのファイル名へ変更する。他のDB設定・データ保存先は維持する。

```sh
.venv-scenic/bin/python infra/valhalla/manage.py doctor
.venv-scenic/bin/python infra/valhalla/manage.py up
.venv-scenic/bin/python infra/valhalla/manage.py check
```

`up`はAPIコンテナを再作成するため、案内・検索中に実行しない。外部公開は既存のCloudflare Tunnel・認証設定をそのまま使う。単なるrestartでは接続DBの変更は反映されない。
旧DBは選定基準・形状検査の版が合わず、新コードでは読み込めない。
ロールバックは旧DBだけでなく、それに対応する旧コードと設定の組み合わせで行う。

切り替え前に生成した候補は旧DBの内容を持つため、アプリから新しく検索する。
当日の通行規制は道路紹介とは別に確認する。
