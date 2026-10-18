import math

from recommendation.models import CATEGORIES, identity
from recommendation.evaluator import interval_union
from recommended_roads import RANKS
from recommendation.route_environment import build_profile

LABELS = {'sea': '海', 'forest': '森林', 'inland_water': '水辺', 'open_nature': '草地などの自然',
          'farmland': '田園', 'townscape': '街並み', 'lake': '湖・池付近', 'river': '川付近',
          'valley': '谷・渓谷の推定', 'urban': '建物の多い市街地', 'unknown': '未評価'}


def geometry_range(start, end):
    return {'start_m': round(start, 2), 'end_m': round(end, 2)}


class EvidenceBuilder:
    def __init__(self, store, snapshot_id):
        self.store, self.snapshot_id = store, snapshot_id
        self.environment_cache = {}

    def present(self, candidate, baseline, request, counterfactuals=()):
        evaluation, route_id = candidate.evaluation, candidate.route_id
        metrics = evaluation['metrics']
        ledger, reasons, warnings = [], [], []
        warnings.extend(evaluation.get('context_notes', []))

        def reason(template, metric, value, text, ranges, sources, confidence='low'):
            evidence_id = 'evidence_' + identity([route_id, metric, value, ranges, sources])
            ledger.append({'evidence_id': evidence_id, 'route_id': route_id, 'revision': 1,
                'snapshot_id': self.snapshot_id, 'metric': metric, 'value': value, 'geometry_ranges': ranges,
                'source_ids': sources, 'confidence_label': confidence, 'evaluation_version': evaluation['evaluation_version']})
            reasons.append({'reason_id': 'reason_' + identity([route_id, template, evidence_id]),
                'template': template, 'text': text, 'evidence_ids': [evidence_id],
                'geometry_ranges': ranges, 'confidence_label': confidence})

        sea_length = metrics['sea_proximity_m']
        if sea_length >= 300 and request.preferences.weights[0] > 0:
            difference = sea_length - (baseline.evaluation['metrics']['sea_proximity_m'] if baseline else 0)
            ranges = [geometry_range(a, b) for a, b in interval_union([(a, b) for a, b, _ in evaluation['near_sea_ranges']])]
            text = f'海に近い区間が{sea_length / 1000:.1f}km（地図からの推定）'
            if baseline and difference >= 100:
                text += f'。基準より{difference / 1000:.1f}km多く通ります'
            reason('sea_proximity', 'sea_proximity_m', sea_length, text, ranges, ['osm'])
        for k, weight in sorted(zip(CATEGORIES, request.preferences.weights), key=lambda p: -p[1]):
            runs = metrics['continuous'][k]
            if not runs or weight <= 0:
                continue
            a, b = runs[0]
            if b - a < 300:
                continue
            source_ids = sorted({s for block in evaluation['timeline'] if block['start_m'] < b and block['end_m'] > a for s in block['source_ids']})
            # Visibility claims require a verified feature on every contributing block.
            visible = k == 'sea' and all(block['visibility_verified'] for block in evaluation['timeline'] if block['start_m'] < b and block['end_m'] > a)
            noun = '海の見通しがあると推定される道' if visible else '海に近い道' if k == 'sea' else LABELS[k] + 'の特徴がある道'
            reason('continuous_' + k, k + '_longest_continuous_m', b - a,
                   f'{noun}が{(b - a) / 1000:.1f}km続くと推定されます', [geometry_range(a, b)], source_ids,
                   'medium' if visible else 'low')
            if len(reasons) >= 2:
                break
        for official_id, official in evaluation['certified'].items():
            if official['meters'] < 100:
                continue
            reason('certified_road', 'certified:' + official_id, official['meters'],
                   f'認定区間「{official["name"]}」を{official["meters"] / 1000:.1f}km走ります',
                   [geometry_range(*r) for r in official['ranges']], [official['source_id']], 'high')
        for ident, road in sorted(evaluation.get('recommended', {}).items(), key=lambda pair: -RANKS[pair[1]['rank']]['order']):
            if road['meters'] < 20:
                continue
            label = RANKS[road['rank']]['label']
            reason('recommended_road', 'recommended:' + ident, road['meters'],
                   f'{label}「{road["name"]}」の対応区間を{road["meters"] / 1000:.1f}km走る計画です',
                   [geometry_range(a, b) for a, b in interval_union(road['ranges'])], road['source_ids'], 'medium')
            warnings.append(f'{road["name"]}: {road["scope_note"]}')
        for reveal in evaluation['reveals']:
            reason('forest_to_sea_reveal', 'reveal_strength', reveal['strength'],
                   '森林から海の見通しがある区間へ移ると推定されます',
                   [geometry_range(reveal['start_m'], reveal['end_m'])], reveal['source_ids'], 'medium')
        for stop in evaluation['schedule']:
            text = f'{stop["name"]}に{stop["dwell_seconds"] // 60}分立ち寄る計画です'
            if not stop['hours_verified']:
                text += '（利用時間は未確認）'
            reason('actual_stop', 'stop:' + stop['facility_id'], stop['dwell_seconds'], text,
                   [geometry_range(stop['distance_m'], stop['distance_m'])], [stop['source_id']],
                   'high' if stop['hours_verified'] else 'low')
        if metrics['coverage'] < .99:
            warnings.append('道路特徴へ対応づけられない区間があります。未評価を景観なしと断定していません。')
        if metrics['unknown_load_rate'] > .01:
            warnings.append(f'運転負荷の代理指標には未確認情報が含まれます（欠損率{metrics["unknown_load_rate"]:.0%}）。安全度ではありません。')
        for category, weight in zip(CATEGORIES, request.preferences.weights):
            if weight > 0 and metrics['missingness'][category] > .01:
                warnings.append(f'{LABELS[category]}の特徴が未取得の区間があります。未取得を景観なしと断定していません。')
        if request.preferences.weights[CATEGORIES.index('valley')] > 0:
            warnings.append('谷・渓谷はOSMの登録と利用可能なDEMからの地形推定です。崖の眺望・峡谷の美しさ・全域の検出精度は未確認です。')
        if self.store.context:
            warnings.append('周辺施設はOSM由来の未登録探索候補です。駐車場入口・営業時間・利用可否は確認済みではなく、自動の立ち寄り先にはしていません。実況の通行規制も未接続です。')
        if self.store.landcover:
            warnings.append('土地被覆の補完には2021年のESA衛星分類を弱めて使用します。現在の植生・市街地の状態を確認したものではありません。水域を海・湖・川へ自動分類していません。')
        if any(request.preferences.weights[CATEGORIES.index(k)] > 0 for k in ('inland_water', 'lake', 'river')):
            warnings.append('湖・川は近接と推定の見通しを評価します。水面が見えることや立ち寄り・停車可能性は保証しません。')
        if not any(b['visibility_verified'] for b in evaluation['timeline']):
            warnings.append('海の可視性は未確認です。海岸への近さと、実際に海が見えることは異なります。')
        if not reasons:
            warnings.append('根拠区間を伴う景観の改善は確認できませんでした。')
        if len(evaluation['schedule']) < request.stops.desired_count:
            warnings.append('希望する休憩回数を満たす施設は確保できませんでした。施設データや条件を確認してください。')
        role = '海を楽しむ' if request.preferences.weights[0] >= .5 and sea_length >= 300 else '森林を楽しむ' if request.preferences.weights[1] >= .5 and metrics['threshold_m']['forest'] >= 300 else '景観を比較する'
        if candidate.visits and evaluation['schedule']:
            role += '・休憩付き'
        timing = {k: math.ceil(v) for k, v in evaluation['timing'].items()}
        # Round total once from its components so the displayed arithmetic remains exact.
        timing['total_seconds'] = timing['driving_seconds'] + timing['stop_seconds'] + timing['waiting_seconds']
        environment = None
        if hasattr(self, 'forecast'):
            if route_id not in self.environment_cache:
                self.environment_cache[route_id] = build_profile(self.store, self.forecast, candidate, request)
            environment = self.environment_cache[route_id]
        return {'route_id': route_id, 'revision': 1, 'snapshot_id': self.snapshot_id, 'role': role,
                'environment': environment,
                'route': candidate.route, 'anchors': [{'lat': a.coordinate[0], 'lon': a.coordinate[1], 'type': a.kind,
                            'facility_id': a.facility_id} for a in candidate.anchors[1:-1]],
                'timing': timing, 'distance_m': round(metrics['total_m']), 'metrics': metrics,
                'score': evaluation['score'], 'components': evaluation['components'],
                'reasons': reasons, 'warnings': warnings, 'evidence': ledger,
                'timeline': evaluation['timeline'], 'schedule': evaluation['schedule'],
                'directed_segments': evaluation['directed_segments'],
                'evaluation_version': evaluation['evaluation_version']}

    def response(self, planner, request, selected, baseline, counterfactuals=(), status=None):
        self.forecast = planner.forecast
        search = planner.budget.status()
        warnings = ['最新の通行止めを網羅した案内ではありません。現地の規制を優先してください。']
        warnings.append('夜間補正ON：通過時刻の暗さで海や開けた景色の評価を下げています。' if request.night_correction else
                        '夜間補正OFF：暗さによる景観の減点をしていません。夜間の眺望を保証するものではありません。')
        if self.store.recommendations.pending_count:
            warnings.append('おすすめ道路は対応確認済みの部分のみ使用しています。未照合の秀逸な道などは評価に含まれていません。')
        if not planner.forecast.cells:
            warnings.append('予報データは未使用です。天気を根拠とした推薦理由は生成していません。')
        warnings.append('地理照合は道路形状への近接と方向による推定です。高架・平行道路の完全なエッジ同定ではありません。')
        warnings.append('往復の不可避性は未確認のため、重複の罰点を軽減していません。')
        comparisons = []
        selected_ids = {c.route_id for c in selected}
        for comparison in counterfactuals:
            if comparison['route_id'] not in selected_ids:
                continue
            alternate = next((c for c in planner.archive.values() if c.route_id == comparison['comparison_route_id']), None)
            if alternate:
                comparisons.append({**comparison, 'comparison_route': self.present(alternate, baseline, request)})
        return {'status': status or ('limited' if search['limited'] else 'complete'),
                'night_correction': request.night_correction,
                'snapshot_id': self.snapshot_id, 'revision': 1,
                'baseline': self.present(baseline, baseline, request) if baseline and baseline.route_id else {
                    'driving_seconds': math.ceil(planner.baseline_seconds), 'selectable': False},
                'routes': [self.present(c, baseline, request, counterfactuals) for c in selected],
                'portfolio_roles': {c.route_id: c.portfolio_role for c in selected if c.portfolio_role},
                'search': search, 'warnings': warnings, 'sources': self.store.sources,
                'counterfactuals': comparisons,
                'parameters_version': planner.parameters.version}
