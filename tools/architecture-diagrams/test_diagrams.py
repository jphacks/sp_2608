import importlib.util
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

from PIL import Image


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('architecture_render', HERE / 'render.py')
RENDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RENDER)


class ArchitectureTests(unittest.TestCase):
    def test_all_plates_layout(self):
        plates = [f() for f in (RENDER.system, RENDER.search, RENDER.evaluation,
                                RENDER.data, RENDER.app, RENDER.perception)]
        self.assertEqual(len({p.slug for p in plates}), 6)
        self.assertEqual([p.number for p in plates], list(range(1, 7)))
        for p in plates:
            self.assertEqual(p.image.size, (1600, 1000))
            source = '\n'.join(p.svg + ['</svg>'])
            ET.fromstring(source)
            self.assertNotIn('ARCHITECTURE ATLAS', source)
            self.assertNotIn('THEACAR', source)

    def test_published_images_and_sources(self):
        output = RENDER.ROOT / 'docs/media/architecture'
        svgs = sorted(output.glob('*.svg'))
        self.assertEqual(len(svgs), 6)
        for source in svgs:
            ET.parse(source)
            with Image.open(source.with_suffix('.png')) as image:
                self.assertEqual(image.size, (1600, 1000))
                image.verify()

    def test_system_separates_car_display_phone_and_shared_navigation(self):
        source = '\n'.join(RENDER.system().svg)
        for label in ('Android Auto 車載画面', 'スマートフォン', 'ナビの共有セッション', 'NavigationSession'):
            self.assertIn(label, source)

    def test_pdf_has_six_pages(self):
        pdf = (RENDER.ROOT / 'docs/media/architecture/theacar-architecture.pdf').read_bytes()
        self.assertTrue(pdf.startswith(b'%PDF-'))
        self.assertEqual(len(re.findall(rb'/Type\s*/Page\b', pdf)), 6)


if __name__ == '__main__':
    unittest.main()
