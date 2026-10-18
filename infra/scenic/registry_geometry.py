"""Describe connected road geometry without inventing links across missing sections."""

from collections import defaultdict


def point(row, end):
    return round(row['lat' + str(end)], 7), round(row['lon' + str(end)], 7)


def describe_geometry(rows):
    edges = {r['id']: r for r in rows}
    adjacent = defaultdict(list)
    for ident, row in edges.items():
        for node in (point(row, 1), point(row, 2)):
            adjacent[node].append(ident)
    remaining = set(edges)
    components, parts = [], []
    while remaining:
        seed = min(remaining)
        pending, members = [seed], set()
        while pending:
            ident = pending.pop()
            if ident not in remaining:
                continue
            remaining.remove(ident)
            members.add(ident)
            for node in (point(edges[ident], 1), point(edges[ident], 2)):
                pending.extend(i for i in adjacent[node] if i in remaining)
        components.append({'segment_count': len(members),
                           'meters': round(sum(edges[i]['length'] for i in members), 2)})
        unused = set(members)
        while unused:
            boundaries = sorted(node for i in unused for node in (point(edges[i], 1), point(edges[i], 2))
                                if len(adjacent[node]) != 2)
            start = boundaries[0] if boundaries else point(edges[min(unused)], 1)
            current, coordinates, ids = start, [list(reversed(start))], []
            while True:
                available = sorted(i for i in adjacent[current] if i in unused)
                if not available:
                    break
                ident = available[0]
                unused.remove(ident)
                ids.append(ident)
                row = edges[ident]
                current = point(row, 2) if current == point(row, 1) else point(row, 1)
                coordinates.append(list(reversed(current)))
                if current == start or len(adjacent[current]) != 2:
                    break
            parts.append({'coordinates': coordinates, 'road_ids': ids,
                          'meters': round(sum(edges[i]['length'] for i in ids), 2)})
    return {'component_count': len(components), 'components': components,
            'segment_count': len(edges), 'matched_meters': round(sum(r['length'] for r in edges.values()), 2),
            'branch_count': sum(len(ids) > 2 for ids in adjacent.values()), 'parts': parts}


def publication_issues(record, geometry, official=False):
    issues = list(record.get('registration_hold_reasons', []))
    if not geometry['segment_count']:
        issues.append('道路形状を照合できていない')
    if not official and geometry['component_count'] > 1:
        issues.append('対象区間の道路網が分断している')
    minimum = record.get('extent', {}).get('minimum_meters', 0)
    if geometry['matched_meters'] < minimum:
        issues.append('紹介された道路の延長に対して照合範囲が不足している')
    if record.get('max_meters') and geometry['matched_meters'] > record['max_meters']:
        issues.append('照合延長が対象区間の異常値上限を超えている')
    if record.get('review_required'):
        issues.append('出典または道路範囲の確認が必要')
    return list(dict.fromkeys(issues))
