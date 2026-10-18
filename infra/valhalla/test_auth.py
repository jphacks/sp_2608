from pathlib import Path
import re
import tempfile
import unittest

from auth import initialize, ensure_credentials, validate_credentials


class AuthTest(unittest.TestCase):
    def test_init_is_idempotent_without_changing_token(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertTrue(ensure_credentials(root))
            original = (root / 'routing.token').read_bytes()
            self.assertFalse(ensure_credentials(root))
            self.assertEqual(original, (root / 'routing.token').read_bytes())

    def test_missing_half_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'routing.token').write_text('a' * 64 + '\n')
            with self.assertRaises(ValueError):
                ensure_credentials(root)
            self.assertFalse((root / 'routing-auth.conf').exists())

    def test_mismatch_does_not_print_token(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            initialize(root)
            token = validate_credentials(root)
            (root / 'routing.token').write_text('a' * 64 + '\n')
            with self.assertRaises(ValueError) as caught:
                validate_credentials(root)
            self.assertNotIn(token, str(caught.exception))
            self.assertNotIn('a' * 64, str(caught.exception))

    def test_random_credentials_and_private_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'secrets'
            initialize(root)
            token = (root / 'routing.token').read_text().strip()
            self.assertTrue(re.fullmatch('[0-9a-f]{64}', token))
            self.assertIn(f'"Bearer {token}" 1;', (root / 'routing-auth.conf').read_text())
            self.assertEqual(0o600, (root / 'routing.token').stat().st_mode & 0o777)
            self.assertEqual(0o600, (root / 'routing-auth.conf').stat().st_mode & 0o777)

    def test_refuses_to_overwrite_existing_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            initialize(root)
            original = (root / 'routing.token').read_text()
            with self.assertRaises(FileExistsError):
                initialize(root)
            self.assertEqual(original, (root / 'routing.token').read_text())


if __name__ == '__main__':
    unittest.main()
