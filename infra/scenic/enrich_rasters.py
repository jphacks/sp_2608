import argparse
import json
import math
from pathlib import Path

from catalog import Catalog
from recommendation.store import ScenicStore
from recommendation.terrain import line_of_sight


class Raster:
    def __init__(self, path):
        import rasterio
        self.dataset = rasterio.open(path)
        if self.dataset.crs is None:
            raise ValueError('Raster CRS is required')

    def value(self, coordinate):
        from rasterio.warp import transform
        x, y = transform('EPSG:4326', self.dataset.crs, [coordinate[1]], [coordinate[0]])
        if not (self.dataset.bounds.left <= x[0] <= self.dataset.bounds.right and
                self.dataset.bounds.bottom <= y[0] <= self.dataset.bounds.top):
            return None
        value = next(self.dataset.sample([(x[0], y[0])], masked=True))[0]
        if bool(getattr(value, 'mask', False)):
            return None
        return float(value) if math.isfinite(float(value)) else None

    def close(self):
        self.dataset.close()


def coverage(raster, coordinate, radius, classes):
    counts, total, valid = {}, 0, 0
    step = 50 if radius >= 500 else 25
    sx = 111195 * math.cos(math.radians(coordinate[0]))
    for x in range(-radius, radius + 1, step):
        for y in range(-radius, radius + 1, step):
            if x*x + y*y > radius*radius:
                continue
            total += 1
            value = raster.value((coordinate[0] + y / 111195, coordinate[1] + x / sx))
            if value is None or str(int(value)) not in classes:
                continue
            valid += 1
            category = classes[str(int(value))]
            counts[category] = counts.get(category, 0) + 1
    if valid < total * .8:
        return None
    return {key: count / valid for key, count in counts.items()}


def enrich(args):
    output = Path(args.output)
    if output.exists():
        raise ValueError('Choose a new output filename')
    manifest = json.loads(Path(args.sources).read_text())
    source = manifest['source']
    if not all(source.get(k) for k in ('source_id', 'source_url', 'license', 'version', 'acquired_at')):
        raise ValueError('Source metadata and confirmed usage rights are required')
    if manifest.get('usage_approved') is not True:
        raise ValueError('Confirm data usage terms before importing')
    classes = manifest.get('classes', {})
    dem = Raster(args.dem) if args.dem else None
    land = Raster(args.land_cover) if args.land_cover else None
    coast = json.loads(Path(args.sea_targets).read_text()) if args.sea_targets else None
    if coast and not all(item.get('verified_sea') is True for item in coast['targets']):
        raise ValueError('Terrain targets must be verified sea points, not arbitrary water pixels')
    catalog = Catalog(args.catalog)
    store = ScenicStore(catalog)
    records = []
    try:
        for row in catalog.db.execute('SELECT * FROM road ORDER BY id'):
            road = store.road(row)
            point = ((row['lat1'] + row['lat2']) / 2, (row['lon1'] + row['lon2']) / 2)
            feature = {}
            if land:
                near, around = coverage(land, point, 100, classes), coverage(land, point, 500, classes)
                if near:
                    feature['forest'] = near.get('forest', 0)
                if around:
                    for category in ('inland_water', 'open_nature', 'farmland'):
                        feature[category] = around.get(category, 0)
                    feature['urban_ratio'] = around.get('built_up', 0)
                    feature['openness'] = max(0, 1 - around.get('forest', 0) - around.get('built_up', 0))
            attrs = store.attribute_cache.get(row['way'], {})
            if dem and not road['tunnel'] and not attrs.get('bridge'):
                first, last = dem.value(road['a']), dem.value(road['b'])
                if first is not None and last is not None and road['length'] >= 10:
                    feature['grade'] = (last - first) / road['length']
                if coast and road['qualities']['sea'] > .05:
                    from geometry import distance
                    from recommendation.store import bearing
                    targets = sorted(coast['targets'], key=lambda t: distance(point, (t['lat'], t['lon'])))[:5]
                    outcomes = [(t, line_of_sight(point, (t['lat'], t['lon']), dem.value)) for t in targets
                                if distance(point, (t['lat'], t['lon'])) <= 10000]
                    known = [(t, value) for t, value in outcomes if value is not None]
                    if len(known) >= 3:
                        feature['sea_visibility'] = sum(value for _, value in known) / len(known)
                        visible = [t for t, value in known if value]
                        if visible:
                            feature['coast_bearing'] = bearing(point, (visible[0]['lat'], visible[0]['lon']))
            if feature:
                records.append({'physical_id': road['physical_id'], 'map_version': store.version,
                                'features': feature, 'source_id': source['source_id']})
        bundle = json.loads(Path(args.base).read_text()) if args.base else {'sources': [], 'road_features': []}
        bundle.setdefault('sources', []).append(source)
        bundle.setdefault('road_features', []).extend(records)
        temporary = output.with_suffix(output.suffix + '.building')
        if temporary.exists():
            raise ValueError('Unfinished output exists')
        temporary.write_text(json.dumps(bundle, allow_nan=False))
        temporary.rename(output)
        print(f'Wrote {len(records)} source-backed feature records')
    finally:
        catalog.close()
        if land:
            land.close()
        if dem:
            dem.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('catalog')
    parser.add_argument('output')
    parser.add_argument('--sources', required=True)
    parser.add_argument('--dem')
    parser.add_argument('--land-cover')
    parser.add_argument('--sea-targets')
    parser.add_argument('--base')
    enrich(parser.parse_args())
