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
        self.assertGreaterEqual(len(svgs), 6)
        for source in svgs:
            root = ET.parse(source).getroot()
            with Image.open(source.with_suffix('.png')) as image:
                self.assertEqual(image.size, (int(root.attrib['width']), int(root.attrib['height'])))
                image.verify()

    def test_microphone_is_shared_and_model_inference_is_separate(self):
        source = '\n'.join(RENDER.perception().svg)
        for label in ('AndroidMicrophone', 'SharedSensorFeed', 'CabinSpeechMonitor',
                      'BgmPlaybackLoop', 'CabinVolumeSession', '未接続：カメラ・実AIモデル'):
            self.assertIn(label, source)
        self.assertNotIn('共有アダプターは未接続', source)
        self.assertNotIn('CabinMicrophone', source)
        root = ET.fromstring(source + '\n</svg>')
        ns = {'svg': 'http://www.w3.org/2000/svg'}
        capture = root.find("svg:rect[@x='60'][@y='175']", ns)
        extension = root.find("svg:rect[@x='60'][@y='485']", ns)
        self.assertIsNotNone(capture)
        self.assertIsNotNone(extension)
        self.assertNotIn('stroke-dasharray', capture.attrib)
        self.assertIn('stroke-dasharray', extension.attrib)

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
