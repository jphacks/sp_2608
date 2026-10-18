"""Match published road centerlines to the local OSM catalog during import."""

import io
import math
import re
import heapq
import json
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict

from geometry import distance

KML = {'k': 'http://www.opengis.net/kml/2.2'}


def kml_documents(raw, name):
    if raw.startswith(b'PK\x03\x04'):
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if sum(i.file_size for i in archive.infolist()) > 50_000_000:
                raise ValueError('Map archive exceeds size limit')
            for entry in sorted(archive.namelist()):
                if entry.lower().endswith(('.kml', '.kmz')):
                    yield from kml_documents(archive.read(entry), name + '/' + entry)
    else:
        if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
            raise ValueError('KML entity declarations are not supported')
        yield name, ET.fromstring(raw)


def published_sections(path):
    from pathlib import Path
    result = {}
    for name, root in kml_documents(Path(path).read_bytes(), str(path)):
        match = re.search(r'scenicroad(\d{2})\.', name)
        if not match:
            continue
        lines = []
        for placemark in root.findall('.//k:Placemark', KML):
            if 'line-FF5252-' not in placemark.findtext('k:styleUrl', default='', namespaces=KML):
                continue
            for coordinates in placemark.findall('.//k:LineString/k:coordinates', KML):
                points = [tuple(map(float, value.split(',')[:2])) for value in coordinates.text.split()]
                if len(points) < 2 or any(not (-180 <= lon <= 180 and -80 <= lat <= 80) for lon, lat in points):
                    raise ValueError('Invalid published line coordinates')
                lines.append(points)
        result[f'selected-{match[1]}'] = lines
    return result


def published_model_courses(path):
    from pathlib import Path
    result = {}
    for name, root in kml_documents(Path(path).read_bytes(), str(path)):
        match = re.search(r'scenicroad(\d+)_(daytrip|staytrip)\.', name)
        if not match:
            continue
        lines = []
        for coordinates in root.findall('.//k:Placemark/k:LineString/k:coordinates', KML):
            points = [tuple(map(float, value.split(',')[:2])) for value in coordinates.text.split()]
            if len(points) >= 2:
                lines.append(points)
        if lines:
            result[f'model-{match[1]}-{match[2]}'] = lines
    return result


def trace_straight_road(catalog, origin, heading, meters, bounds):
    rows = catalog.roads(bounds)
    adjacency, points = defaultdict(list), {}
    direction = math.radians(heading)
    axis = (math.sin(direction), math.cos(direction))
    scale = 111195 * math.cos(math.radians(origin[0]))
    projected = lambda point: ((point[1] - origin[1]) * scale, (point[0] - origin[0]) * 111195)
    lateral = lambda point: abs(projected(point)[0] * axis[1] - projected(point)[1] * axis[0])
    tables = {r[0] for r in catalog.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    oneway = {}
    for row in rows:
        a, b = (row['lat1'], row['lon1']), (row['lat2'], row['lon2'])
        if max(lateral(a), lateral(b)) > 200:
            continue
        if row['way'] not in oneway:
            raw = catalog.db.execute('SELECT payload FROM way_attributes WHERE way=?', (row['way'],)).fetchone() if 'way_attributes' in tables else None
            oneway[row['way']] = json.loads(raw[0]).get('oneway', 0) if raw else 0
        key_a, key_b = tuple(round(v, 7) for v in a), tuple(round(v, 7) for v in b)
        cost = row['length'] * (1 + (max(lateral(a), lateral(b)) / 25) ** 2)
        if oneway[row['way']] != -1:
            adjacency[key_a].append((key_b, row['id'], row['length'], cost))
        if oneway[row['way']] != 1:
            adjacency[key_b].append((key_a, row['id'], row['length'], cost))
        points[key_a], points[key_b] = a, b
    if not points:
        return [], {'coverage': 0, 'method': 'connected_straight_osm_trace'}
    start = min(points, key=lambda key: distance(origin, points[key]))
    if distance(origin, points[start]) > 50:
        return [], {'coverage': 0, 'method': 'connected_straight_osm_trace'}
    goal = min(points, key=lambda key: math.hypot(projected(points[key])[0] - axis[0] * meters,
                                                 projected(points[key])[1] - axis[1] * meters))
    best, previous, queue = {start: 0.}, {}, [(0., start)]
    while queue:
        cost, current = heapq.heappop(queue)
        if cost != best.get(current):
            continue
        if current == goal:
            break
        for end, ident, length, penalty in adjacency[current]:
            candidate = cost + penalty
            if candidate < best.get(end, math.inf):
                best[end], previous[end] = candidate, (current, ident, length)
                heapq.heappush(queue, (candidate, end))
    if goal not in previous:
        return [], {'coverage': 0, 'method': 'connected_straight_osm_trace'}
    selected, total, current = [], 0., goal
    while current != start:
        current, ident, length = previous[current]
        selected.append(ident)
        total += length
    return selected, {'coverage': max(0, 1 - abs(total - meters) / meters), 'matched_meters': round(total), 'trace_end': points[goal],
                      'reference_meters': meters, 'method': 'connected_straight_osm_trace'}


def match_lines(catalog, lines, way_refs=None, required_refs=(), tolerance=30, scenic_only=False):
    from shapely.geometry import LineString
    import numpy as np
    import shapely
    if not lines:
        return [], {'reference_meters': 0, 'covered_meters': 0, 'coverage': 0}
    latitude = sum(p[1] for line in lines for p in line) / sum(map(len, lines))
    scale = 111195 * math.cos(math.radians(latitude))
    project = lambda p: (p[0] * scale, p[1] * 111195)
    reference, covered_lengths, matched = [], [], {}
    for line in lines:
        geometry = LineString([project(p) for p in line])
        if geometry.length == 0:
            continue
        reference.append(geometry)
        corridor = geometry.buffer(tolerance)
        matched_lines = []
        west, south = min(p[0] for p in line), min(p[1] for p in line)
        east, north = max(p[0] for p in line), max(p[1] for p in line)
        pad_x, pad_y = tolerance / scale, tolerance / 111195
        for row in catalog.roads((west - pad_x, south - pad_y, east + pad_x, north + pad_y), limit=1_000_000):
            if scenic_only and max(row['sea'], row['forest'], row['nature']) < .65:
                continue
            if required_refs and not set(required_refs).intersection((way_refs or {}).get(str(row['way']), ())):
                continue
            road = LineString([project((row['lon1'], row['lat1'])), project((row['lon2'], row['lat2']))])
            if road.length == 0:
                continue
            center = road.interpolate(.5, normalized=True)
            if geometry.distance(center) > tolerance:
                continue
            at = geometry.project(center)
            before, after = geometry.interpolate(max(0, at - 10)), geometry.interpolate(min(geometry.length, at + 10))
            dx, dy = after.x - before.x, after.y - before.y
            a, b = road.coords
            rx, ry = b[0] - a[0], b[1] - a[1]
            denominator = math.hypot(dx, dy) * math.hypot(rx, ry)
            if not denominator or abs(dx * rx + dy * ry) / denominator < math.cos(math.radians(30)):
                continue
            if road.intersection(corridor).length / road.length < .8:
                continue
            matched[row['id']] = row['length']
            matched_lines.append(road)
        if matched_lines:
            coordinates = shapely.get_coordinates(shapely.segmentize(geometry, 25))
            centers = shapely.points((coordinates[:-1] + coordinates[1:]) / 2)
            lengths = np.linalg.norm(np.diff(coordinates, axis=0), axis=1)
            tree = shapely.STRtree(matched_lines)
            separations = shapely.distance(centers, tree.geometries[tree.nearest(centers)])
            covered_lengths.append(float(lengths[separations <= tolerance].sum()))
    covered = sum(covered_lengths)
    total = sum(g.length for g in reference)
    return sorted(matched), {'reference_meters': round(total), 'covered_meters': round(covered),
                             'coverage': min(1., max(0., covered / total)) if total else 0., 'matched_meters': round(sum(matched.values())),
                             'tolerance_meters': tolerance, 'method': 'published_centerline_osm_overlap_heading'}
