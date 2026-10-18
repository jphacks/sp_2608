"""Download the versioned public road-map resources from Sapporo CKAN."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import urlopen

API = 'https://ckan.pf-sapporo.jp/api/3/action/package_show?id=scenicbyway'
RESOURCES = {'fee75228-c8ff-43b6-9c53-b67a7389b2ad': 'route',
             '30ca6984-bc52-49b5-8971-e9260d9dbaf5': 'plan'}


def fetch(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    with urlopen(API, timeout=20) as response:
        metadata = json.loads(response.read(1_000_001))
    if not metadata['success'] or metadata['result']['license_id'] != 'CC-BY-4.0':
        raise ValueError('Dataset license or metadata changed; review before importing')
    report = {'acquired_at': datetime.now(timezone.utc).isoformat(), 'dataset': 'scenicbyway',
              'license': 'CC-BY-4.0', 'author': metadata['result']['author'], 'resources': []}
    for resource in metadata['result']['resources']:
        if resource['id'] not in RESOURCES:
            continue
        url = urlsplit(resource['url'])
        if url.scheme != 'https' or url.netloc != 'ckan.pf-sapporo.jp':
            raise ValueError('Resource host changed; review before downloading')
        with urlopen(resource['url'], timeout=30) as response:
            raw = response.read(8_000_001)
        if len(raw) > 8_000_000 or not raw.startswith(b'PK\x03\x04'):
            raise ValueError('Unexpected map archive')
        date = resource['last_modified'][:10].replace('-', '')
        path = directory / (RESOURCES[resource['id']] + '-' + date + '.zip')
        if path.exists():
            if path.read_bytes() != raw:
                raise ValueError('Previously downloaded resource changed without a new version')
        else:
            with path.open('xb') as stream:
                stream.write(raw)
        report['resources'].append({'id': resource['id'], 'url': resource['url'], 'file': path.name,
                                    'last_modified': resource['last_modified'], 'sha256': hashlib.sha256(raw).hexdigest()})
        print(path, flush=True)
    digest = hashlib.sha256(json.dumps(report['resources'], sort_keys=True).encode()).hexdigest()[:16]
    output = directory / f'provenance-{digest}.json'
    if not output.exists():
        with output.open('x') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory')
    fetch(parser.parse_args().directory)
