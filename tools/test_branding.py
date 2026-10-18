from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class BrandingTests(unittest.TestCase):
    def test_launcher_uses_product_name(self):
        manifest = ET.parse(ROOT / 'androidApp/src/main/AndroidManifest.xml')
        self.assertEqual('@string/app_name', manifest.find('application').get(ANDROID + 'label'))
        strings = ET.parse(ROOT / 'androidApp/src/main/res/values/strings.xml')
        self.assertEqual('旅れこ', strings.find("string[@name='app_name']").text)

    def test_car_notifications_and_guest_page_use_product_name(self):
        surfaces = {
            'platform/android-auto/src/main/kotlin/com/theacar/auto/HomeScreen.kt': 'setTitle("旅れこ")',
            'platform/android-navigation/src/main/kotlin/com/theacar/android/navigation/NavigationService.kt':
                'setContentTitle("旅れこ ナビ")',
            'platform/android-music/src/main/kotlin/com/theacar/android/music/CabinListeningService.kt':
                'setContentTitle("旅れこ 景観BGM")',
            'infra/bgm-api/src/party-page.ts': '<p class="eyebrow">旅れこ</p>',
        }
        for path, label in surfaces.items():
            with self.subTest(path=path):
                self.assertIn(label, (ROOT / path).read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
