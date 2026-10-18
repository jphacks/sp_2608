from collections import OrderedDict
import json
from pathlib import Path

from recommendation.models import identity


class SourceDatabase:
    TABLES = {'terrain': ('road_terrain', 'road_id'), 'context': ('way_context', 'way_id'),
              'landcover': ('land_cover', 'road_id')}

    def __init__(self, catalog, path, name):
        if name not in self.TABLES:
            raise ValueError('Unknown source database')
        self.db, self.name, self.cache = catalog.db, name, OrderedDict()
        self.db.execute(f'ATTACH DATABASE ? AS {name}', (Path(path).resolve().as_uri() + '?mode=ro',))
        self.metadata = dict(self.db.execute(f'SELECT key,value FROM {name}.metadata'))
        if self.metadata.get('schema') != '1' or self.metadata.get('catalog_signature') != identity(catalog.source):
            raise ValueError(f'{name} database does not match the road catalog')

    def get(self, key):
        if key not in self.cache:
            table, column = self.TABLES[self.name]
            row = self.db.execute(f'SELECT payload FROM {self.name}.{table} WHERE {column}=?', (key,)).fetchone()
            self.cache[key] = json.loads(row[0]) if row else {}
            if len(self.cache) > 8192:
                self.cache.popitem(last=False)
        self.cache.move_to_end(key)
        return self.cache[key]
