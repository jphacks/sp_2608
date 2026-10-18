import sqlite3
import unicodedata
from contextlib import closing
from pathlib import Path


def normalize(value):
    return ' '.join(unicodedata.normalize('NFKC', value).casefold().split())


def search(database, query, limit=5):
    query = normalize(query)
    if not 2 <= len(query) <= 120:
        raise ValueError('Use 2 to 120 characters')
    terms = query.split()
    conditions = ' AND '.join('instr(terms, ?) > 0' for _ in terms)
    with closing(sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True)) as db:
        rows = db.execute(
            f'SELECT id, name, label, lat, lon FROM places WHERE {conditions} '
            'ORDER BY CASE WHEN normalized_name = ? THEN 0 WHEN instr(normalized_name, ?) = 1 THEN 1 ELSE 2 END, '
            'priority DESC, length(name), id LIMIT ?', (*terms, query, query, min(10, max(1, limit)))).fetchall()
    return [dict(place_id=r[0], name=r[1], display_name=r[2], lat=str(r[3]), lon=str(r[4])) for r in rows]
