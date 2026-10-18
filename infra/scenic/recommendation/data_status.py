"""Operational source inventory: configured is not the same as complete."""

import json


EXTERNAL_GAPS = (
    {'id': 'jaxa-land-cover', 'status': 'excluded_by_scope',
     'source_url': 'https://www.eorc.jaxa.jp/ALOS/jp/dataset/lulc_j.htm',
     'reason': '登録不要の公開データだけを使う方針により除外。ESA WorldCoverを代替として利用。'},
    {'id': 'live-road-regulations', 'status': 'integration_unconfigured',
     'source_url': 'https://www.hkd.mlit.go.jp/ky/kn/dou_iji/ud49g7000000b2dd.html',
     'reason': '再利用条件・取得方式・更新周期を確認した公式フィードが未接続。OSMの条件付きタグで代用しない。'},
    {'id': 'seasonal-colours', 'status': 'unavailable',
     'reason': '紅葉・開花・実際の積雪景観の観測データは未取得。日付だけで発生を断定しない。'},
    {'id': 'verified-facility-access', 'status': 'unverified',
     'reason': 'POIの代表点・営業時間文字列は収集したが、車の入口・全営業時間構文・利用可否は未検証。'},
    {'id': 'vehicle-window-visibility', 'status': 'unverified',
     'reason': 'DEMとOSMは近似。植生・防潮堤・車種・車窓からの眺望の現地検証は未実施。'},
)


def source_status(store, weather_enabled):
    road_count = store.catalog.db.execute('SELECT count(*) FROM road').fetchone()[0]
    items = [{'id': 'osm-roads', 'status': 'loaded', 'version': store.version,
              'source_url': 'https://www.openstreetmap.org/copyright', 'road_samples': road_count,
              'observed_at': store.catalog.source.get('observed_at') or None,
              'note': '北海道PBFの道路サンプル。OSMに未登録の地物は含まない。'},
             {'id': 'reviewed-scenic-roads', 'status': 'loaded' if store.recommendations.version != 'none' else 'unconfigured',
              'version': store.recommendations.version,
              'active_count': store.recommendations.active_count,
              'rank_counts': store.recommendations.rank_counts,
              'pending_count': store.recommendations.pending_count},
             {'id': 'osm-landscapes', 'status': 'loaded' if store.has_landscapes else 'unconfigured',
              'version': store.landscape_version},
             {'id': 'open-meteo', 'status': 'configured' if weather_enabled else 'disabled',
              'source_url': 'https://open-meteo.com/en/docs',
              'note': '位置・時刻ごとの取得成否は検索snapshotで確認。実況や全経路の完全な予報被覆ではない。'}]
    for name, database in [('gsi-terrain', store.terrain), ('osm-context', store.context), ('esa-worldcover', store.landcover)]:
        if database:
            metadata = database.metadata
            items.append({'id': name, 'status': 'loaded', 'version': metadata['version'],
                          'source_url': metadata['source_url'], 'license': metadata['license'],
                          'built_at': metadata.get('built_at'), 'observed_at': metadata.get('observed_at'),
                          'counts': json.loads(metadata.get('counts', '{}'))})
        else:
            items.append({'id': name, 'status': 'unconfigured'})
    return {'scope': 'hokkaido-driving-2026-10-17', 'complete': False, 'sources': items,
            'gaps': list(EXTERNAL_GAPS), 'warning': '収集済みの件数と全地物の網羅率は異なります。取得・接続・検証を分けて表示しています。'}
