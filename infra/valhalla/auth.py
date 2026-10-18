import argparse
import os
from pathlib import Path
import secrets
import re


def validate_credentials(directory: Path):
    token_path, config_path = directory / 'routing.token', directory / 'routing-auth.conf'
    if not token_path.is_file() or not config_path.is_file():
        raise ValueError('Both routing.token and routing-auth.conf are required. Existing credentials were not changed.')
    token = token_path.read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}', token):
        raise ValueError('Invalid routing.token format. Its value has not been printed.')
    expected = ('map $http_authorization $routing_authorized {\n'
                '    default 0;\n' + f'    "Bearer {token}" 1;\n' + '}\n')
    if config_path.read_text() != expected:
        raise ValueError('routing.token and routing-auth.conf do not match. Restore the matching pair; do not rotate just one file.')
    return token


def ensure_credentials(directory: Path):
    if any((directory / name).exists() for name in ('routing.token', 'routing-auth.conf')):
        validate_credentials(directory)
        return False
    initialize(directory)
    validate_credentials(directory)
    return True


def initialize(directory: Path):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    paths = [directory / 'routing.token', directory / 'routing-auth.conf']
    if any(path.exists() for path in paths):
        raise FileExistsError('Credentials already exist; they were not changed.')
    token = secrets.token_hex(32)
    contents = [token + '\n', 'map $http_authorization $routing_authorized {\n'
                '    default 0;\n'
                f'    "Bearer {token}" 1;\n'
                '}\n']
    for path, content in zip(paths, contents):
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            output.write(content)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Create credentials for a private routing API.')
    parser.add_argument('command', choices=['init'])
    parser.parse_args()
    try:
        created = ensure_credentials(Path(__file__).resolve().parent / '.secrets')
    except (FileExistsError, ValueError) as error:
        raise SystemExit(str(error))
    print('Created private routing credentials.' if created else 'Existing routing credentials validated; unchanged.')
