"""Checks for merge remnants and broken documentation references."""

from pathlib import Path
import re
import subprocess
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {'.md', '.kt', '.kts', '.py', '.ts', '.tsx', '.json', '.jsonc',
                 '.yaml', '.yml', '.xml', '.toml', '.properties', '.ino', '.h'}


def repository_files():
    names = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT,
    ).decode().split('\0')
    return sorted({ROOT / name for name in names if name and (ROOT / name).is_file()})


class RepositoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = repository_files()

    def test_no_merge_markers(self):
        marker = re.compile(r'^(?:<{7}(?: |$)|={7}$|>{7}(?: |$)|\|{7}(?: |$))', re.M)
        failures = []
        for path in self.files:
            if path.suffix not in TEXT_SUFFIXES:
                continue
            text = path.read_text(encoding='utf-8')
            for match in marker.finditer(text):
                line = text.count('\n', 0, match.start()) + 1
                failures.append(f'{path.relative_to(ROOT)}:{line}')
        self.assertEqual([], failures, 'Unresolved merge markers: ' + ', '.join(failures))

    def test_documentation_links(self):
        failures = []
        for path in self.files:
            if path.suffix != '.md':
                continue
            text = re.sub(r'```.*?```', '', path.read_text(encoding='utf-8'), flags=re.S)
            for reference in re.findall(r'\]\(([^)]+)\)', text):
                url = reference.strip().split(' "')[0].strip('<>')
                if urlsplit(url).scheme or url.startswith('//'):
                    continue
                name, _, fragment = url.partition('#')
                target = (path.parent / unquote(name)).resolve() if name else path
                if not target.exists():
                    failures.append(f'{path.relative_to(ROOT)}: {url}')
                    continue
                if fragment and target.suffix == '.md':
                    content = re.sub(r'```.*?```', '', target.read_text(encoding='utf-8'), flags=re.S)
                    headings = re.findall(r'^#{1,6}\s+(.+?)\s*#*\s*$', content, re.M)
                    anchors = {re.sub(r'[^\w -]', '', h.lower()).replace(' ', '-') for h in headings}
                    anchors.update(re.findall(r'''(?:id|name)=["']([^"']+)''', content))
                    if unquote(fragment) not in anchors:
                        failures.append(f'{path.relative_to(ROOT)}: {url}')
        self.assertEqual([], failures, 'Broken links: ' + ', '.join(failures))


if __name__ == '__main__':
    unittest.main()
