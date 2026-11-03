# Google Play の公開要件

2026-11-02 に公式のページを読み、旅れこの実装と照らした結果です。Play のルールは変わるので、申請の前にもう一度確かめます。ヘルプのページには更新日が出ないものが多いので、確認日はすべて 2026-11-02 とします。Android Developers のページは、そのページの更新日も書きます。

手順は[Google Play への配布](play-release.md)、残りの作業は[公開までのチェックリスト](play-release-checklist.md)を参照してください。

状態は次の4つで書きます。

| 状態 | 意味 |
| --- | --- |
| 満たす | 実装やビルドの結果で確かめた |
| 不足 | 公開までに直す必要がある |
| 要確認 | 公式のページや手元の情報だけでは決められない |
| 対象外 | 旅れこには当てはまらない |

## 1. アカウント

| 項目 | 要件 | 旅れこの状態 | 出典 |
| --- | --- | --- | --- |
| 新規の個人アカウントのテスト | 2023-11-13 より後に作った個人アカウントは、製品版の前にクローズドテストが要る。申請の時点で12人以上が参加し、直前の14日間続けて参加していること。14日に満たず抜けた人は数えない。抜けて戻った場合は、14日が連続していないと数えない。申請の審査は通常7日以内 | 未着手 | [App testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465) |
| 組織アカウント | D-U-N-S 番号が必須。組織の名称・住所・電話・ウェブサイトなどを登録する | 名義は社長の判断（[チェックリスト](play-release-checklist.md#社長の判断が要ること)） | [Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885)、[Required information](https://support.google.com/googleplay/android-developer/answer/13628312) |
| Play に公開される情報 | 個人は法的氏名・国・デベロッパーのメールアドレス。収益化しなければ住所は出ない（地域の規則で出る場合を除く）。組織は法的名称・住所・メールアドレス・電話番号 | 名義の判断に関わる | [Required information](https://support.google.com/googleplay/android-developer/answer/13628312) |
| 組織アカウントが必須の分野 | 金融、医療などの健康、VPN、行政 | 対象外 | [Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885) |
| アプリの移管 | 別のアカウントへ移せる。利用者・評価・レビューは移り、テストのグループは移らない。登録料とは別の移管の費用は書かれていない | 製品版の権限が移るかは書かれていない（要確認） | [Transfer apps](https://support.google.com/googleplay/android-developer/answer/6230247) |

## 2. アプリ本体

| 項目 | 要件 | 旅れこの状態 | 出典 |
| --- | --- | --- | --- |
| target API | 2026-08-31 以降の新規アプリと更新は Android 16（API 36）以上。ページに Android Auto の行はなく、スマホのアプリの基準を見る | 満たす。targetSdk 36 | [Target API](https://developer.android.com/google/play/requirements/target-sdk)（更新 2026-10-28） |
| 16KB ページサイズ | Android 15 以上を対象にするアプリは対応する。2027-02-01 以降、非対応の更新は出せない | 満たす。AAB の arm64-v8a と x86_64 の `libmaplibre.so`、`libandroidx.graphics.path.so` は、すべて 16KB 境界。AGP 8.9.1、`extractNativeLibs=false` | [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)（更新 2026-10-16） |
| AAB | 署名なしの `bundleRelease` は成功（2026-11-02、35MB）。Play に上げるにはアップロード鍵の署名が要る | 鍵の作成は社長の作業 | [配布の手順](play-release.md) |
| バックグラウンドの位置情報 | `ACCESS_BACKGROUND_LOCATION` を宣言すると申告が要る。フォアグラウンドサービスで同等の使い方をすると同じ扱いになる。利用者の操作の続きとして始め、用が済んだらすぐ止めること | 満たす。権限は manifest で除いた。`NavigationService` は案内の開始で始まり、終了で止まる（`NavigationService.kt:70`） | [Location in the background](https://support.google.com/googleplay/android-developer/answer/9799150) |
| フォアグラウンドサービスの申告 | Android 14 以上を対象にするアプリは、種類ごとに機能の説明、止められたときの影響、動画のリンク、用途を App content で申告する | 未申告。3種類ある。文面と撮影手順は[掲載情報](store-listing.md#フォアグラウンドサービスの申告と動画) | [FGS requirements](https://support.google.com/googleplay/android-developer/answer/13392821) |
| 位置情報の種類 | 用途に「ナビ（走行中の案内の継続）」がある | 満たす | 同上 |
| マイクの種類 | 用途は Background Audio Access。例は保存しない音声コマンドと録音。動画では、利用者がマイクの収音を始める様子を見せる | 要確認。旅れこは会話の量の判定に使い、案内の開始に連動して始まる。例と違うので、審査の結果は読めない | 同上 |
| メディア再生の種類 | バックグラウンドで続く音声や動画の再生。再生は利用者が始めたものに限り、自動で流す音は当たらない | 不足のおそれ。`GuestRequestService` は自分では音を出さない。実際の再生は Spotify アプリが行い、このサービスはリクエストの受け付けを保っている | 同上 |
| アカウント削除 | アプリ内でアカウントを作れる場合に、アプリ内とウェブの削除手段が要る。データセーフティの削除の質問には全員が答える | 対象外。独自のアカウントはない。Spotify の連携がこれに当たるかはページに書かれていない（要確認） | [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) |
| プライバシーポリシー | 公開の URL（PDF 不可、ログイン不要）を、掲載情報とアプリ内の両方にリンクする | 不足。URL は公開済み。アプリ内にリンクがない | [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455) |
| 目立つ開示 | 利用者の想定を超える収集は、許可を求める直前にアプリ内で説明する。ポリシーに書くだけでは足りない。位置情報とマイクは個人情報に当たる | 不足。マイクと位置情報は、説明の画面なしで OS の許可ダイアログを出す（`SpotifyMusicActivity.kt:87`、`NavigationActivity.kt:270`） | [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) |
| 通信の暗号化 | 個人情報は HTTPS などで送る | 満たす。Release には `usesCleartextTraffic` がない | 同上 |

## 3. Android Auto

| 項目 | 要件 | 旅れこの状態 | 出典 |
| --- | --- | --- | --- |
| フォームファクタ | Play Console の詳細設定で Android Auto を追加し、テストのトラックに上げる | 未着手 | [Distribute to cars](https://developer.android.com/training/cars/distribute)（更新 2026-10-06） |
| 審査 | 内部共有と内部テストは審査なし。クローズドテストは審査するが、不適合でも通る。オープンテストと製品版は不適合だと止まる。審査はスマホだけのアプリより時間がかかることがある | 内部テストなら今の実装でも配れる | 同上 |
| 品質の段階 | 運転中に使う種類のアプリは Car optimized の該当項目をすべて満たす。ナビはここに入る | 不足あり（次の節） | [Car app quality](https://developer.android.com/docs/quality-guidelines/car-app-quality)（更新 2026-09-14） |
| 米国で提供しないナビ | 審査のため、模擬 GPS アプリで現在地を変えられること | 満たす見込み。`AndroidLocationSource.kt` は模擬の位置を除いていない。実機では未確認 | [Distribute to cars](https://developer.android.com/training/cars/distribute) |
| ログインが要る機能 | App Access に審査用のアカウントを出す | 音楽機能を残すなら、審査用の Spotify アカウントが要る | 同上 |

## 4. 車載アプリの品質ガイドラインとの照合

ナビに当たる項目のうち、状態が分かるものです。ここにない項目は、DHU で確かめます。

| ID | 内容 | 状態 | 根拠 |
| --- | --- | --- | --- |
| NF-1 | 曲がり角の案内 | 満たす | `GuidanceScreen.kt` |
| NF-3 | 案内の通知 | 満たす | `NavigationService.kt:116`。ongoing、`CATEGORY_NAVIGATION`、`CarAppExtender` |
| NF-4 | 次の曲がり角をクラスタへ送る | 不足 | `TheaCarSession.kt:111` の `Trip` に `Step` を入れていない |
| NF-5 | ほかのナビの案内中は止まる | 満たす見込み | `onStopNavigation` で案内を止める。実機では未確認 |
| NF-6、VC-1 | ほかのアプリの要求と音声コマンドを受ける | 不足 | [#39](https://github.com/jphacks/sp_2608/issues/39) |
| NF-7 | テストドライブ。Play の審査に必須 | 不足 | [#40](https://github.com/jphacks/sp_2608/issues/40)。`onAutoDriveEnabled` がない。[Build a navigation app](https://developer.android.com/training/cars/apps/navigation)（更新 2026-09-08） |
| DD-1 | 案内の音声チャンネルは案内にだけ使う | 不足 | `CabinNoticeSpeaker.kt:30,48` が窓の知らせも同じチャンネルで読み上げる。[#43](https://github.com/jphacks/sp_2608/issues/43) |
| PC-1、IN-1 | 車向けでない機能を入れない。必要な通知だけ出す | 要確認 | [#43](https://github.com/jphacks/sp_2608/issues/43)。景観の知らせ、窓の知らせ、BGM の自動開始 |
| MR-1 | 昼夜の地図 | 満たす | [#41](https://github.com/jphacks/sp_2608/issues/41) の対応。`TheaCarSession.kt:61` |
| DR-2、DR-3 | 起動と読み込みは10秒以内 | 要確認 | Cloud Run は `minScale: 0`（`feat/cloud-run` の `service.yaml`）。止まった状態からの応答を測る |
| VI-1 | スマホを見させるときは、安全なときだけ見るよう伝える | 要確認 | 最近の目的地の案内文には「停車中に」とある。車の画面から許可を求める場面の表示は未確認 |
| EP-1 | 掲載情報のとおりに動く | 要確認 | 掲載情報に北海道限定と書く。Spotify の扱いで文面が変わる |

## 5. 掲載と申告

| 項目 | 要件 | 旅れこの状態 | 出典 |
| --- | --- | --- | --- |
| 文字数 | アプリ名30字、短い説明80字、詳しい説明4,000字。全角と半角は同じ1字 | 下書き済み（[掲載情報](store-listing.md)） | [Store listing](https://support.google.com/googleplay/android-developer/answer/9859152) |
| アイコン | 512 × 512、32ビット PNG、1024KB 以下 | 作成済み（`design/store/icon-512.png`） | [Preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) |
| フィーチャーグラフィック | 1024 × 500、JPEG か24ビット PNG。「Best」「New」「Free」などの語、端末の絵、他社のロゴは使わない | 作成済み（`design/store/feature-graphic-1024x500.png`）。スマホの絵を入れたので、Play の案内とは違う | 同上 |
| スクリーンショット | 種類ごとに8枚まで、公開には2枚以上。長辺は短辺の2倍以内。おすすめ枠には 1080px 以上を4枚（縦は 1080 × 1920 以上） | 5枚作成済み（`design/store/screenshots/`）。スマホの枠を入れたので、Play の案内とは違う | 同上 |
| コンテンツレーティング | 質問票に答える。評価のないアプリは Play から削除される | 回答案を作成済み。設問の文面は Console で確かめる | [Content ratings](https://support.google.com/googleplay/android-developer/answer/9898843)、[Rating system](https://support.google.com/googleplay/android-developer/answer/188189) |
| 対象年齢、広告、ログイン情報 | App content で申告する | 回答案を作成済み | [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455) |
| データセーフティ | 収集と共有を種類ごとに申告する。削除の質問にも答える | 回答案を作成済み（[配布の手順の5番](play-release.md#データセーフティ)） | [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) |

## 6. 外部サービスの条件

| 項目 | 条件 | 旅れこへの影響 | 出典 |
| --- | --- | --- | --- |
| Spotify の開発モード | 所有者に Premium が要る。使えるのは許可リストに入れた5人まで | クローズドテストの12人のうち、音楽を試せるのは5人まで | [Quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) |
| Spotify の拡張モード | 2025-05-15 以降、受け付けは組織だけ。法人であること、公開済みであること、月間25万人以上の利用者などが条件。審査は最大6週間 | 今の規模では申請できない。一般公開では音楽機能を使える人が増やせない | 同上 |
| Spotify の開発者ポリシー | 別のサービスへのデータの受け渡しの制限（III.9）、分析の禁止（III.13）、AI モデルへの利用の禁止（III.14）、連携解除時のデータ削除（I.1.2）、帰属表示とリンク（II.4） | 要確認。プレイリストの曲名とアーティスト名を Gemini へ送っている。思い出のページに再生した曲が残る。詳細は法務の確認が要る | [Developer Policy](https://developer.spotify.com/policy)（2025-05-15 発効） |
| Gemini API | 無料枠の入力は Google の製品改善に使われ、有料枠では使われない | 要確認。`annotate.ts` のコメントは無料枠を前提にしている。今のキーの課金の状態は未確認 | [Gemini API Terms](https://ai.google.dev/gemini-api/terms) |
| 国土地理院の標高データ | 測量成果の複製・使用には承認申請が要る場合がある。申請が要らない場合も出典を書く。標高タイルが対象かは手続きのページに書かれていない。標準の処理期間は土日祝を除く7〜14日 | 要確認（[#36](https://github.com/jphacks/sp_2608/issues/36)）。国土地理院の「地図の利用手続ナビ」で確かめる | [測量成果の利用手続](https://www.gsi.go.jp/LAW/2930-index.html) |

## 7. 確かめきれなかったこと

- 組織アカウントがテスト要件の対象外であることは、公式のページでは確認できていません。ヘルプは個人アカウントだけを対象に書いていて、対象外と明記した記事は第三者のものだけです。
- コンテンツレーティングの設問の文面は公開のページにありません。Console で開いて確かめます。
- フォアグラウンドサービスの動画を置く場所の指定は、読んだページにはありません。Console の入力欄で確かめます。
- データセーフティのアカウントの質問で、Spotify の連携をどう答えるかは、公式のページで確認できていません。
