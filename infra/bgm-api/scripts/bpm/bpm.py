"""全曲カタログの BPM を、Deezer の30秒試聴から Essentia の TempoCNN で解析する（手元で実行する）。

使い方（infra/bgm-api で実行）:
  python scripts/bpm/bpm.py analyze              catalog/catalog.jsonl の曲を解析し、catalog/bpm.jsonl に追記する
  python scripts/bpm/bpm.py analyze --limit 20   まず20曲だけ試す
  python scripts/bpm/bpm.py evaluate             正解データ（test/fixtures/bpm-truth-jp.csv）で的中率を測る
  python scripts/bpm/bpm.py apply                catalog/bpm.jsonl の結果を catalog/catalog.jsonl の tempo と source_json に反映する

analyze は途中で止めても、解析済みの曲を飛ばして続きから再開する。試聴音源は解析後すぐに消し、BPM の数値だけを残す。
"""

import argparse
import csv
import json
import os
import sys
import tempfile
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
CATALOG = os.path.join(ROOT, 'catalog', 'catalog.jsonl')
BPM_OUT = os.path.join(ROOT, 'catalog', 'bpm.jsonl')
TRUTH = os.path.join(ROOT, 'test', 'fixtures', 'bpm-truth-jp.csv')
MODEL_DIR = os.path.join(ROOT, 'catalog', 'work', 'models')

MODEL = 'deepsquare-k16-3'
MODEL_URL = f'https://essentia.upf.edu/models/tempo/tempocnn/{MODEL}.pb'
METHOD = f'tempocnn/{MODEL}'

DEEZER_INTERVAL_S = 0.25  # Deezer API：1秒に4回まで（公開の上限 50回/5秒 より十分少なく）
DOWNLOAD_WORKERS = 4
UA = {'User-Agent': 'bgm-catalog-bpm'}

# BPM → 6軸の tempo（src/annotate.ts と同じ式）
def tempo_axis(bpm):
    return round(min(1.0, max(0.0, (bpm - 60) / 120)), 3)


class Throttle:
    def __init__(self, interval):
        self.interval, self.lock, self.next = interval, threading.Lock(), 0.0

    def wait(self):
        with self.lock:
            now = time.monotonic()
            if now < self.next:
                time.sleep(self.next - now)
            self.next = max(now, self.next) + self.interval


def get(url, timeout=20):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout).read()


def download_preview(deezer_id, throttle, tmpdir):
    """Deezer の曲情報から試聴 URL を取り、MP3 を一時ファイルに保存する。試聴が無ければ None"""
    throttle.wait()
    info = json.loads(get(f'https://api.deezer.com/track/{deezer_id}', timeout=15))
    if 'error' in info:
        raise RuntimeError(f"deezer: {info['error'].get('message')}")
    if not info.get('preview'):
        return None
    path = os.path.join(tmpdir, f'{deezer_id}.mp3')
    with open(path, 'wb') as f:
        f.write(get(info['preview']))
    return path


class Analyzer:
    def __init__(self):
        import essentia
        import essentia.standard as es
        essentia.log.warningActive = False
        os.makedirs(MODEL_DIR, exist_ok=True)
        model_path = os.path.join(MODEL_DIR, f'{MODEL}.pb')
        if not os.path.exists(model_path):
            print(f'TempoCNN のモデルを取得します: {MODEL_URL}')
            with open(model_path + '.part', 'wb') as f:
                f.write(get(MODEL_URL, timeout=60))
            os.replace(model_path + '.part', model_path)
        self.es = es
        self.tcnn = es.TempoCNN(graphFilename=model_path)

    def bpm(self, path):
        audio = self.es.MonoLoader(filename=path, sampleRate=11025, resampleQuality=4)()
        global_bpm, local_bpm, _ = self.tcnn(audio)
        if not global_bpm > 0:  # 無音やリズムが取れない音源では 0 が返る
            raise ValueError('no tempo detected')
        local = [float(b) for b in local_bpm]
        # 信頼度：区間ごとの推定のうち、全体の BPM と ±4% で一致する割合（曲の途中でテンポの見え方がぶれるほど低い）
        conf = sum(1 for b in local if b > 0 and abs(b / global_bpm - 1) <= 0.04) / len(local) if local else 0.0
        return round(float(global_bpm), 1), round(conf, 2)


def load_jsonl(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


def analyze_tracks(tracks, on_result):
    """tracks: [{'track_id', 'deezer_id'}]。ダウンロードは並列、解析は1本ずつ（TensorFlow をスレッド間で共有しない）"""
    analyzer = Analyzer()
    throttle = Throttle(DEEZER_INTERVAL_S)
    with tempfile.TemporaryDirectory(prefix='bgm-bpm-') as tmpdir, ThreadPoolExecutor(DOWNLOAD_WORKERS) as ex:
        def fetch(t):
            try:
                return t, download_preview(t['deezer_id'], throttle, tmpdir), None
            except Exception as e:  # 通信の失敗は記録して次へ（再実行で retry できる）
                return t, None, f'download: {e}'

        for t, path, err in ex.map(fetch, tracks):
            row = {'track_id': t['track_id'], 'deezer_id': t['deezer_id'], 'method': METHOD,
                   'analyzed_at': datetime.now(timezone.utc).isoformat(timespec='seconds')}
            if err:
                row['error'] = err
            elif path is None:
                row['error'] = 'no preview'
            else:
                try:
                    row['bpm'], row['bpm_conf'] = analyzer.bpm(path)
                except Exception as e:
                    row['error'] = f'analyze: {e}'
                finally:
                    os.remove(path)
            on_result(row)


def cmd_analyze(args):
    catalog = load_jsonl(CATALOG)
    done = {r['track_id']: r for r in load_jsonl(BPM_OUT)}
    todo = []
    for r in catalog:
        prev = done.get(r['track_id'])
        if prev and ('bpm' in prev or not args.retry_errors):
            continue
        deezer_id = json.loads(r.get('source_json') or '{}').get('deezer_id')
        if deezer_id:
            todo.append({'track_id': r['track_id'], 'deezer_id': deezer_id})
    if args.limit:
        todo = todo[:args.limit]
    print(f'カタログ {len(catalog)}曲  解析済み {sum(1 for r in done.values() if "bpm" in r)}曲  今回 {len(todo)}曲')
    if not todo:
        return
    # 失敗した曲を retry するときは、古い失敗の行を残したまま追記する（apply は track_id ごとに最後の行を使う）
    started, n, ok = time.time(), 0, 0
    with open(BPM_OUT, 'a') as out:
        def on_result(row):
            nonlocal n, ok
            out.write(json.dumps(row, ensure_ascii=False) + '\n')
            out.flush()
            n += 1
            ok += 'bpm' in row
            if n % 50 == 0 or n == len(todo):
                rate = (time.time() - started) / n
                print(f'  {n}/{len(todo)}  成功 {ok}  残り約 {round(rate * (len(todo) - n) / 60)}分', flush=True)
        analyze_tracks(todo, on_result)


def cmd_evaluate(args):
    rows = list(csv.DictReader(open(TRUTH)))
    results = {}
    analyze_tracks([{'track_id': r['track_id'], 'deezer_id': r['deezer_id']} for r in rows],
                   lambda row: results.__setitem__(row['track_id'], row))

    def kind(v, t):
        r = v / t
        if abs(r - 1) <= 0.04: return '一致'
        if abs(r - 2) <= 0.08 or abs(r - 0.5) <= 0.02: return '倍・半分'
        if abs(r - 1.5) <= 0.06 or abs(r - 2 / 3) <= 0.027: return '1.5倍系'
        return '外れ'

    counts = {}
    for r in rows:
        res = results[r['track_id']]
        k = kind(res['bpm'], float(r['true_bpm'])) if 'bpm' in res else '解析できず'
        counts[k] = counts.get(k, 0) + 1
        if k != '一致':
            print(f"  {k}: {r['title']} / {r['artist']}  正解 {r['true_bpm']}  解析 {res.get('bpm')}  信頼度 {res.get('bpm_conf')}")
    print(f'{len(rows)}曲（±4%）:', '  '.join(f'{k} {v}' for k, v in counts.items()))


def cmd_apply(args):
    latest = {}
    for r in load_jsonl(BPM_OUT):
        latest[r['track_id']] = r  # 後の行ほど新しい
    catalog = load_jsonl(CATALOG)
    changed = 0
    for row in catalog:
        res = latest.get(row['track_id'])
        if not res or not res.get('bpm', 0) > 0:
            continue
        source = json.loads(row.get('source_json') or '{}')
        if source.get('bpm_source') == 'llm':
            source['bpm_llm'] = source.get('bpm')  # 比較用に、LLM の推定を残す
        source.update(bpm=res['bpm'], bpm_source=res['method'], bpm_conf=res['bpm_conf'])
        row['source_json'] = json.dumps(source, ensure_ascii=False, separators=(',', ':'))
        row['tempo'] = tempo_axis(res['bpm'])
        changed += 1
    tmp = CATALOG + '.tmp'
    with open(tmp, 'w') as f:
        f.write(''.join(json.dumps(r, ensure_ascii=False, separators=(',', ':')) + '\n' for r in catalog))
    os.replace(tmp, CATALOG)
    print(f'{len(catalog)}曲のうち {changed}曲の BPM を解析値に置き換えました（{CATALOG}）')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    a = sub.add_parser('analyze')
    a.add_argument('--limit', type=int)
    a.add_argument('--retry-errors', action='store_true', help='前回失敗した曲もやり直す')
    sub.add_parser('evaluate')
    sub.add_parser('apply')
    args = p.parse_args()
    {'analyze': cmd_analyze, 'evaluate': cmd_evaluate, 'apply': cmd_apply}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
