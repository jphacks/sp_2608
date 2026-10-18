import json
import math
from pathlib import Path

from geometry import distance, segment_distance
from recommendation.models import Anchor, CATEGORIES, Facility, Opportunity, identity, timestamp
from recommended_roads import RecommendedRoads, RANKS
from recommendation.candidate_area import candidate_tiles
from recommendation.corridors import SpatialOpportunities, region
from recommendation.source_database import SourceDatabase


def bearing(a, b):
    dy = math.radians(b[0] - a[0])
    dx = math.radians(b[1] - a[1]) * math.cos(math.radians((a[0] + b[0]) / 2))
    return math.degrees(math.atan2(dx, dy)) % 360


def angle_difference(a, b):
    return abs((a - b + 180) % 360 - 180)


def direction_allowed(road, reverse):
    return road['oneway'] != (1 if reverse else -1)


class ScenicStore:
    def __init__(self, catalog, supplements=None, recommended_roads=None, landscapes=None, terrain=None, context=None, landcover=None):
        self.catalog = catalog
        self.version = catalog.source.get('map_version') or identity(catalog.source)
        self.sources = [{'source_id': 'osm', 'source_url': 'https://www.openstreetmap.org/copyright',
                         'license': 'ODbL-1.0', 'observed_at': catalog.source.get('observed_at') or None,
                         'acquired_at': catalog.source.get('builtAt'), 'version': self.version,
                         'spatial_coverage': catalog.bounds}]
        self.facilities, self.official = {}, []
        self.terrain = SourceDatabase(catalog, terrain, 'terrain') if terrain else None
        self.context = SourceDatabase(catalog, context, 'context') if context else None
        self.landcover = SourceDatabase(catalog, landcover, 'landcover') if landcover else None
        for name, database in [('gsi-terrain', self.terrain), ('osm-context', self.context), ('esa-worldcover', self.landcover)]:
            if database:
                meta = database.metadata
                self.sources.append({'source_id': name, 'source_url': meta['source_url'],
                    'license': meta['license'] + ('; ' + meta['attribution'] if meta.get('attribution') else ''),
                    'version': meta['version'], 'acquired_at': meta.get('acquired_at', meta.get('built_at')),
                    'observed_at': meta.get('observed_at'), 'spatial_coverage': catalog.bounds})
        self.recommendations = RecommendedRoads(recommended_roads, self.version, identity(catalog.source))
        self.recommendation_index_ready = False
        self.has_landscapes = bool(landscapes)
        self.landscape_cache = {}
        self.landscape_version = 'none'
        self.landscape_kinds = set()
        if landscapes:
            catalog.db.execute('ATTACH DATABASE ? AS landscapes', (Path(landscapes).resolve().as_uri() + '?mode=ro',))
            metadata = dict(catalog.db.execute('SELECT key,value FROM landscapes.metadata'))
            if metadata.get('catalog_signature') != identity(catalog.source) or metadata.get('schema') != '1':
                raise ValueError('Landscape database does not match the scenic catalog')
            self.landscape_version = metadata['version']
            self.landscape_kinds = set(json.loads(metadata.get('object_counts', '{"sea":0,"lake":0,"river":0,"water":0,"valley":0}')))
            self.sources.append({'source_id': 'osm-landscape', 'source_url': 'https://www.openstreetmap.org/copyright',
                                 'license': 'ODbL-1.0', 'version': self.landscape_version,
                                 'acquired_at': metadata.get('built_at'), 'observed_at': None,
                                 'spatial_coverage': catalog.bounds})
        existing_sources = {source['source_id'] for source in self.sources}
        self.sources.extend(source for source in self.recommendations.sources if source['source_id'] not in existing_sources)
        self.cache, self.match_cache = {}, {}
        self.attribute_cache = {}
        tables = {r[0] for r in catalog.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.has_attributes = 'way_attributes' in tables
        self.feature_overrides = {}
        self.feature_sources = {}
        if supplements:
            self.load_supplements(supplements)

    def load_supplements(self, path):
        data = json.loads(Path(path).read_text())
        for source in data.get('sources', []):
            if not all(source.get(k) for k in ('source_id', 'source_url', 'license', 'version', 'acquired_at')):
                raise ValueError('Supplementary sources require provenance')
            self.sources.append(source)
        source_ids = {s['source_id'] for s in self.sources}
        for item in data.get('facilities', []):
            if item['source_id'] not in source_ids:
                raise ValueError('Unknown facility source')
            windows = tuple((timestamp(w[0]), timestamp(w[1])) for w in item.get('windows', []))
            if any(end <= start for start, end in windows):
                raise ValueError('Invalid facility hours')
            p = item['entrance']
            if not self.catalog.contains((p['lat'], p['lon'])):
                continue
            facility = Facility(item['id'], item['name'], (p['lat'], p['lon']), frozenset(item.get('equipment', [])),
                                windows, item['source_id'], item.get('category', 'rest_area'))
            self.facilities[facility.id] = facility
        for item in data.get('official_routes', []):
            if item['source_id'] not in source_ids or not item.get('name'):
                raise ValueError('Unknown official route source')
            self.official.append(item)
        for item in data.get('road_features', []):
            if item['source_id'] not in source_ids:
                raise ValueError('Unknown feature source')
            if item.get('map_version') != self.version:
                raise ValueError('Feature map version mismatch')
            self.feature_overrides[item['physical_id']] = item['features']
            self.feature_sources[item['physical_id']] = item['source_id']

    def road(self, row):
        key = row['id']
        if key in self.cache:
            return self.cache[key]
        if row['way'] not in self.attribute_cache:
            attrs = self.catalog.db.execute('SELECT payload FROM way_attributes WHERE way=?', (row['way'],)).fetchone() if self.has_attributes else None
            if len(self.attribute_cache) >= 50000:
                self.attribute_cache.clear()
            self.attribute_cache[row['way']] = {**(self.context.get(str(row['way'])) if self.context else {}),
                                              **(json.loads(attrs[0]) if attrs else {})}
        attrs = self.attribute_cache[row['way']]
        physical = self.physical_id(row)
        overrides = self.feature_overrides.get(physical, {})
        features = overrides
        forest = max(0., min(1., row['forest']))
        open_nature = max(0., row['nature'] - forest)
        values = {'sea': row['sea'], 'forest': forest, 'inland_water': None,
                  'open_nature': open_nature, 'farmland': None, 'townscape': None,
                  'lake': None, 'river': None, 'valley': None, 'urban': min(1., max(0., row['urban']) / .15)}
        landscape = ((json.loads(row['landscape_payload']) if row['landscape_payload'] else {})
                     if 'landscape_payload' in row.keys() else self.landscape_at(key))
        if landscape is not None:
            for kind in ('lake', 'river', 'valley', 'farmland'):
                if kind in self.landscape_kinds:
                    values[kind] = landscape.get(kind, 0.)
            values['inland_water'] = max(landscape.get(kind, 0.) for kind in ('lake', 'river', 'water'))
            features = {**landscape, **features}
        terrain = ((json.loads(row['terrain_payload']) if row['terrain_payload'] else {})
                   if 'terrain_payload' in row.keys() else self.terrain.get(key) if self.terrain else {})
        if terrain.get('terrain_valley') is not None:
            values['valley'] = max(values['valley'] or 0, terrain['terrain_valley'])
        cover = ((json.loads(row['cover_payload']) if row['cover_payload'] else {})
                 if 'cover_payload' in row.keys() else self.landcover.get(key) if self.landcover else {})
        for category in ('forest', 'open_nature', 'farmland'):
            if cover.get(category) is not None:
                remaining = max(0., 1 - row['urban'] - (0 if category == 'forest' else forest))
                values[category] = max(values[category] or 0, .75 * cover[category] * remaining)
        features = {**features, **terrain, **overrides}
        features['satellite_cover'] = cover
        values.update({k: overrides[k] for k in CATEGORIES if k in overrides})
        for value in values.values():
            if value is not None and (not math.isfinite(value) or not 0 <= value <= 1):
                raise ValueError('Invalid feature value')
        width = attrs.get('width_m')
        narrow = min(1, max(0, (5.5 - width) / 2)) if width is not None else None
        rough = attrs.get('rough')
        grade = features.get('grade', attrs.get('osm_grade'))
        if grade is None and not attrs.get('bridge') and not attrs.get('tunnel'):
            grade = terrain.get('terrain_grade')
        features = {**features, 'narrow': narrow, 'rough': rough, 'grade': grade,
                    'sharp': features.get('sharp'),
                    'access_conditions': {k: attrs[k] for k in ('access:conditional', 'motor_vehicle:conditional', 'seasonal', 'winter_service', 'snowplowing', 'ford') if attrs.get(k)}}
        tunnel = attrs.get('tunnel', False)
        if tunnel:
            values = {k: 0. for k in CATEGORIES}
        road = {'id': key, 'physical_id': physical, 'way': row['way'], 'sequence': row['sequence'],
                'a': (row['lat1'], row['lon1']), 'b': (row['lat2'], row['lon2']), 'length': row['length'],
                'qualities': values, 'features': features, 'width': width, 'tunnel': tunnel,
                'unpaved': attrs.get('unpaved'), 'oneway': attrs.get('oneway', 0),
                'confidence': .6 if physical in self.feature_overrides else .3,
                'source_ids': ['osm'] + (['osm-landscape'] if landscape is not None else []) + (['gsi-terrain'] if terrain else []) + (['esa-worldcover'] if cover else []) + (['osm-context'] if self.context else []) + ([self.feature_sources[physical]] if physical in self.feature_sources else []),
                'visibility_verified': features.get('sea_visibility') is not None,
                'coast_bearing': features.get('coast_bearing'), 'sea_visibility': features.get('sea_visibility'),
                'openness': features.get('openness')}
        road['urban_ratio'] = features.get('urban_ratio', row['urban'])
        if road['openness'] is None and terrain.get('terrain_openness') is not None and values['forest'] is not None:
            road['openness'] = terrain['terrain_openness'] * max(0., 1 - values['forest'] - max(road['urban_ratio'], cover.get('built_up', 0)))
        road['certified'] = [o for o in self.official if str(row['way']) in o.get('osm_way_ids', [])]
        road['recommendations'] = self.recommendations.at(key)
        if len(self.cache) >= 25000:
            self.cache.clear()
        self.cache[key] = road
        return road

    def landscape_at(self, road_id):
        if not self.has_landscapes:
            return None
        if road_id not in self.landscape_cache:
            if len(self.landscape_cache) >= 4096:
                self.landscape_cache.clear()
            raw = self.catalog.db.execute('SELECT payload FROM landscapes.road_landscape WHERE road_id=?', (road_id,)).fetchone()
            self.landscape_cache[road_id] = json.loads(raw[0]) if raw else {}
        return self.landscape_cache[road_id]

    def physical_id(self, row):
        return identity([self.version, str(row['way']), row['sequence'],
                         row['lat1'], row['lon1'], row['lat2'], row['lon2']])

    def match(self, point, heading):
        key = (round(point[0], 6), round(point[1], 6), round(heading / 10))
        if key not in self.match_cache:
            rows = self.catalog.match_roads(point)
            choices = []
            for row in rows:
                a, b = (row['lat1'], row['lon1']), (row['lat2'], row['lon2'])
                separation = segment_distance(point, a, b)
                delta = angle_difference(heading, bearing(a, b))
                axis = min(delta, 180 - delta)
                if separation <= 20 and axis <= 40:
                    road, reverse = self.road(row), delta > 90
                    if direction_allowed(road, reverse):
                        choices.append((separation + axis / 8, row['id'], road, reverse))
            choices.sort(key=lambda c: (c[0], c[1]))
            match = (choices[0][2], choices[0][3]) if choices else None
            if len(self.match_cache) >= 50000:
                self.match_cache.clear()
            self.match_cache[key] = match
        return self.match_cache[key]

    def candidates(self, request, parameters, maximum_seconds, now=None, deadline=math.inf, reference_points=None):
        radius = maximum_seconds * (400 / 3.6)
        dy = min(180, radius / 111195)
        dx = min(360, dy / max(.1, math.cos(math.radians(request.origin[0]))))
        west, south, east, north = self.catalog.bounds
        bounds = (max(west, request.origin[1] - dx), max(south, request.origin[0] - dy),
                  min(east, request.origin[1] + dx), min(north, request.origin[0] + dy))
        filters = []
        landscape_join = ' LEFT JOIN landscapes.road_landscape l ON l.road_id=r.id' if self.has_landscapes else ''
        terrain_join = ' LEFT JOIN terrain.road_terrain t ON t.road_id=r.id' if self.terrain else ''
        cover_join = ' LEFT JOIN landcover.land_cover c ON c.road_id=r.id' if self.landcover else ''
        if not self.feature_overrides:
            columns = {'sea': 'r.sea', 'forest': 'r.forest', 'open_nature': 'max(0,r.nature-r.forest)',
                       'urban': 'r.urban/0.15'}
            requested = {k for k, w in zip(CATEGORIES, request.preferences.weights) if w > 0}
            if self.has_landscapes and requested & {'lake', 'river', 'valley', 'farmland', 'inland_water'}:
                for kind in ('lake', 'river', 'valley', 'farmland'):
                    if kind in self.landscape_kinds:
                        columns[kind] = f"coalesce(json_extract(l.payload,'$.{kind}'),0)"
                columns['inland_water'] = "max(coalesce(json_extract(l.payload,'$.lake'),0),coalesce(json_extract(l.payload,'$.river'),0),coalesce(json_extract(l.payload,'$.water'),0))"
            if self.terrain:
                columns['valley'] = 'max(' + columns.get('valley', '0') + ',coalesce(t.valley,0))'
            if self.landcover:
                for category in ('forest', 'open_nature', 'farmland'):
                    columns[category] = 'max(' + columns.get(category, '0') + f',0.75*coalesce(c.{category},0))'
            filters = [f'{columns[k]}>=0.45' for k, w in zip(CATEGORIES, request.preferences.weights) if w > 0 and k in columns]
        quality_filter = ' AND (' + ' OR '.join(filters) + ')' if filters else ''
        if filters and self.recommendations.by_segment:
            if not self.recommendation_index_ready:
                self.catalog.db.execute('CREATE TEMP TABLE IF NOT EXISTS recommended_ids(id INTEGER PRIMARY KEY)')
                self.catalog.db.execute('DELETE FROM recommended_ids')
                self.catalog.db.executemany('INSERT INTO recommended_ids VALUES (?)', ((int(ident),) for ident in self.recommendations.by_segment))
                self.recommendation_index_ready = True
            quality_filter = ' AND (' + ' OR '.join(filters) + ' OR r.id IN (SELECT id FROM recommended_ids))'
        projection = 'r.*' + (',l.payload AS landscape_payload' if self.has_landscapes else '') + (',t.payload AS terrain_payload' if self.terrain else '') + (',c.payload AS cover_payload' if self.landcover else '')
        query = 'SELECT ' + projection + ''' FROM road_bounds b CROSS JOIN road r ON r.id=b.id''' + landscape_join + terrain_join + cover_join + '''
            WHERE b.max_lon>=? AND b.min_lon<=? AND b.max_lat>=? AND b.min_lat<=?''' + quality_filter + ' ORDER BY r.id'
        pool = SpatialOpportunities(request)
        run, active, previous = [], False, None

        def emit():
            minimum = 30 if any(r['recommendations'] for r in run) else 300
            if sum(r['length'] for r in run) < minimum:
                return
            for start in range(0, len(run), 100):
                part = run[start:start + 100]
                if sum(r['length'] for r in part) < minimum:
                    continue
                center = part[len(part) // 2]['a']
                lower = distance(request.origin, center) + distance(center, request.destination)
                # 400 km/h is deliberately permissive for the supported automobile/ferry profile.
                if lower / (400 / 3.6) > maximum_seconds:
                    continue
                anchors = [Anchor(part[0]['a'])]
                accumulated = 0
                for road in part:
                    accumulated += road['length']
                    if accumulated >= 500:
                        anchors.append(Anchor(road['b']))
                        accumulated = 0
                if anchors[-1].coordinate != part[-1]['b']:
                    anchors.append(Anchor(part[-1]['b']))
                category = max(range(len(CATEGORIES)), key=lambda k: sum((r['qualities'][CATEGORIES[k]] or 0) * r['length'] for r in part) * request.preferences.weights[k])
                landscape_value = sum(sum((r['qualities'][k] or 0) * w for k, w in zip(CATEGORIES, request.preferences.weights)) * r['length'] for r in part)
                value = landscape_value + .25 * sum(max((RANKS[item['rank']]['weight'] for item in r['recommendations']), default=0) * r['length'] for r in part)
                estimated_extra = max(0, lower - distance(request.origin, request.destination))
                priority = value / (1 + estimated_extra / 3000)
                opportunity = Opportunity(f"arc:{part[0]['physical_id']}", tuple(anchors),
                    tuple((r['physical_id'], r['length']) for r in part), CATEGORIES[category], value,
                    repair_anchors=tuple(Anchor(r['a']) for r in part[::2]), landscape_value=landscape_value)
                pool.add(priority, opportunity)

        truncated, scanned, tile_count = False, 0, 0
        tiles = candidate_tiles(bounds, reference_points or (request.origin, request.destination))
        seen = set()
        for tile in tiles:
            if now and now() >= deadline:
                truncated = True
                break
            rows = self.catalog.db.execute(query, (tile[0], tile[2], tile[1], tile[3]))
            tile_count += 1
            for index, row in enumerate(rows):
                if index % 128 == 0 and now and now() >= deadline:
                    truncated = True
                    break
                if row['id'] in seen:
                    continue
                seen.add(row['id'])
                scanned += 1
                road = self.road(row)
                quality = max(((road['qualities'][k] or 0) for k, w in zip(CATEGORIES, request.preferences.weights) if w > 0), default=0)
                connected = previous is not None and road['way'] == previous['way'] and road['sequence'] == previous['sequence'] + 1
                if not connected or (quality < .45 and not road['recommendations']) or road['tunnel']:
                    emit()
                    run, active = [], False
                if not road['tunnel'] and (road['recommendations'] or quality >= .65 or (active and quality >= .45)):
                    run.append(road)
                    active = True
                previous = road
            emit()
            run, active, previous = [], False, None
            if truncated:
                break
        self.scan_stats = {'tiles': tile_count, 'total_tiles': len(tiles), 'road_samples': scanned}
        selected = pool.select(parameters.max_arcs)
        self.scan_stats.update(opportunities_offered=pool.offered, regions=len(pool.groups),
                               retained_regions=len({(region(v.anchors[len(v.anchors) // 2].coordinate, request),
                                                      v.category) for v in selected}), retained=len(selected))
        for facility in sorted(self.facilities.values(), key=lambda f: (f.id not in request.stops.required_ids,
                                        distance(request.origin, f.entrance) + distance(f.entrance, request.destination)))[:parameters.max_stops]:
            selected.append(Opportunity('stop:' + facility.id, (Anchor(facility.entrance, 'break', facility_id=facility.id),),
                                        facility=facility, category='stop', estimated_value=1000))
        return selected, truncated
