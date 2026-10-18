"""Check curated source URLs without copying articles or automatically approving road extents."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def audit(manifest, output):
    output = Path(output)
    if output.exists():
        raise ValueError('Choose a new audit output filename')
    report = {'checked_at': datetime.now(timezone.utc).isoformat(), 'sources': []}
    for source in json.loads(Path(manifest).read_text())['sources']:
        item = {'source_id': source['source_id'], 'source_url': source['source_url']}
        parsed = urlsplit(source['source_url'])
        if parsed.scheme != 'https' or parsed.username or parsed.password:
            raise ValueError('Source URLs must be public HTTPS URLs without credentials')
        try:
            request = Request(source['source_url'], headers={'User-Agent': 'TheaCar-source-audit/1.0'})
            with urlopen(request, timeout=15) as response:
                content = response.read(4_000_001)
                if len(content) > 4_000_000:
                    raise ValueError('Source exceeds audit size limit')
                item.update(status=response.status, sha256=hashlib.sha256(content).hexdigest(),
                            final_url=response.url, content_type=response.headers.get_content_type())
        except Exception as error:
            item.update(status='review_required', error=type(error).__name__)
        report['sources'].append(item)
        print(f'{item["source_id"]}: {item["status"]}', flush=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest')
    parser.add_argument('output')
    args = parser.parse_args()
    audit(args.manifest, args.output)
