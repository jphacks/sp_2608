# 曲の BPM を試聴から解析する

全曲カタログ（`catalog/catalog.jsonl`）の BPM を、Deezer の30秒試聴から [Essentia](https://essentia.upf.edu/) の TempoCNN で解析します。結果は `catalog/bpm.jsonl` に保存し、6軸の「速さ」（`tempo`）に使います。

以前は Gemini が曲名から推定した BPM を使っていましたが、邦楽では外れが多いため置き換えました。

## 精度

邦楽40曲の正解データ（[test/fixtures/bpm-truth-jp.csv](../../test/fixtures/bpm-truth-jp.csv)）で、正解との差が ±4% 以内の曲数です。正解は音楽ゲームの登録値・コード譜などから取りました。

| 方法 | 一致 | 倍・半分 | 1.5倍・2/3倍 | 外れ |
| --- | --- | --- | --- | --- |
| **TempoCNN（採用）** | **38** | 2 | 0 | 0 |
| Essentia RhythmExtractor2013（multifeature） | 31 | 5 | 3 | 1 |
| librosa | 27 | 5 | 7 | 1 |
| DeepRhythm | 27 | 11 | 0 | 2 |
| Gemini の推定（以前の方法） | 22 | 0 | 0 | 18 |

TempoCNN の外れ2曲は「炎 / LiSA」（正解76 → 152）と「Subtitle / Official髭男dism」（正解130 → 65、体感はハーフタイムの65）です。

## 準備

Python 3.12 で確認しています。

```sh
python3.12 -m venv .venv-bpm
.venv-bpm/bin/pip install -r scripts/bpm/requirements.txt
```

初回の実行時に、TempoCNN のモデル（約5MB）を `catalog/work/models/` に取得します（Git 対象外）。

## 使い方

`infra/bgm-api` で実行します。

```sh
.venv-bpm/bin/python scripts/bpm/bpm.py evaluate   # 正解データで的中率を測る
.venv-bpm/bin/python scripts/bpm/bpm.py analyze    # カタログの未解析の曲を解析して catalog/bpm.jsonl に追記
.venv-bpm/bin/python scripts/bpm/bpm.py apply      # catalog/bpm.jsonl の BPM を catalog/catalog.jsonl に反映
```

- `analyze` は途中で止めても、解析済みの曲を飛ばして続きから再開します。試聴が無い曲・失敗した曲も記録し、`--retry-errors` を付けるとやり直します。
- 試聴音源は一時フォルダに保存し、解析後すぐに消します。残すのは BPM の数値だけです。
- `npm run catalog -- export` も `catalog/bpm.jsonl` を読み、解析値があれば Gemini の推定より優先します。

`catalog/bpm.jsonl` の1行:

| 項目 | 内容 |
| --- | --- |
| `bpm` | TempoCNN が推定した曲全体の BPM |
| `bpm_conf` | 区間ごとの推定のうち、全体の BPM と ±4% で一致した割合（0〜1）。曲の途中でテンポの見え方がぶれるほど低い |
| `method` | `tempocnn/deepsquare-k16-3` |
| `error` | 解析できなかった理由（`no preview` など）。このときは `bpm` が無い |

`apply` のあと、`source_json` の `bpm_source` が `tempocnn/deepsquare-k16-3` になり、Gemini の推定は `bpm_llm` に残ります。`tempo` は `(BPM − 60) ÷ 120` を 0〜1 に収めた値です（[src/annotate.ts](../../src/annotate.ts) と同じ式）。

## ライセンス

- Essentia は AGPL-3.0（非商用の場合。商用は MTG との別契約）、TempoCNN のモデルは [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) です。
- 旅れこは非商用として、この解析値を使います。**広告・課金などで収益化する場合は、MTG（Music Technology Group, Universitat Pompeu Fabra）の商用ライセンスが必要です。**
- 出典の表示: Tempo estimation by TempoCNN (Schreiber & Müller, 2018), Essentia models by MTG-UPF, licensed under CC BY-NC-SA 4.0.
- Deezer の試聴音源は解析のために一時的に取得し、保存・再配布しません。
