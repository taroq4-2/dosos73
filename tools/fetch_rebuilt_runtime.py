"""Fetch the pinned GitHub producer artifacts and verify them before use."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

from trim_universal_apk import ABIS


MANIFEST = Path(__file__).with_name('rebuilt-runtime-artifacts.json')


def file_hash(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def load_manifest(path=MANIFEST):
    pins = json.loads(path.read_text(encoding='utf-8'))
    if (pins.get('schema') != 'dosos73-rebuilt-artifacts-v1'
            or pins.get('repository') != 'taroq4-2/dosos73'
            or type(pins.get('run_id')) is not int or pins['run_id'] <= 0
            or not re.fullmatch('[a-f0-9]{40}', pins.get('producer', ''))):
        raise ValueError('Invalid pinned producer identity')
    rows = pins['artifacts']
    if len(rows) != len(ABIS) or {row['abi'] for row in rows} != set(ABIS):
        raise ValueError('Exactly four unique ABI pins are required')
    for row in rows:
        if (type(row['id']) is not int or row['id'] <= 0
                or type(row['bytes']) is not int or row['bytes'] <= 0
                or not re.fullmatch('[a-f0-9]{64}', row['sha256'])):
            raise ValueError('Invalid artifact pin')
    if len({row['id'] for row in rows}) != len(rows):
        raise ValueError('Artifact IDs must be unique')
    return pins


def gh_json(endpoint):
    result = subprocess.run(['gh', 'api', endpoint], capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError('GitHub metadata request failed')
    return json.loads(result.stdout)


def verify_metadata(pins, run, listing):
    if (run.get('id') != pins['run_id'] or run.get('head_sha') != pins['producer']
            or run.get('repository', {}).get('full_name') != pins['repository']
            or run.get('status') != 'completed' or run.get('conclusion') != 'success'):
        raise ValueError('Pinned producer did not complete successfully')
    expected = {'native-core-candidate-' + row['abi']: row for row in pins['artifacts']}
    artifacts = listing['artifacts']
    if (listing.get('total_count') != len(ABIS) or len(artifacts) != len(ABIS)
            or {item['name'] for item in artifacts} != expected.keys()):
        raise ValueError('Producer artifact set differs from pins')
    for item in artifacts:
        pin = expected[item['name']]
        identity = item['workflow_run']
        if (item['id'] != pin['id'] or item['size_in_bytes'] != pin['bytes']
                or item.get('digest') != 'sha256:' + pin['sha256'] or item.get('expired') is not False
                or identity['id'] != pins['run_id'] or identity['head_sha'] != pins['producer']):
            raise ValueError('Producer artifact metadata differs from pins')


def verified_cached(path, pin):
    return (path.is_file() and not path.is_symlink() and path.stat().st_size == pin['bytes']
            and file_hash(path) == pin['sha256'])


def fetch(output, pins):
    if not os.environ.get('GH_TOKEN'):
        raise ValueError('GH_TOKEN with same-repository Actions read access is required')
    api = 'repos/' + pins['repository'] + '/actions'
    run = gh_json(api + '/runs/' + str(pins['run_id']))
    listing = gh_json(api + '/runs/' + str(pins['run_id']) + '/artifacts?per_page=100')
    verify_metadata(pins, run, listing)
    output.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise ValueError('Artifact output must be an ordinary directory')
    for pin in pins['artifacts']:
        destination = output / (pin['abi'] + '.zip')
        if destination.is_symlink():
            raise ValueError('Artifact output must not be a symlink')
        if not verified_cached(destination, pin):
            for attempt in range(3):
                # gh handles authenticated API redirects. The token stays in the
                # environment and is never a command-line argument or log field.
                with tempfile.NamedTemporaryFile(mode='wb', prefix=pin['abi'] + '-',
                                                 suffix='.part', dir=output, delete=False) as stream:
                    partial = Path(stream.name)
                    try:
                        result = subprocess.run(
                            ['gh', 'api', api + '/artifacts/' + str(pin['id']) + '/zip'],
                            stdout=stream, stderr=subprocess.PIPE, check=False, timeout=240)
                        completed = result.returncode == 0
                    except subprocess.TimeoutExpired:
                        completed = False
                if completed and verified_cached(partial, pin):
                    partial.replace(destination)
                    break
                partial.unlink()
                if attempt == 2:
                    raise RuntimeError('GitHub artifact download or integrity check failed: ' + pin['abi'])
                time.sleep(3)
        print('Verified pinned native artifact ' + pin['abi'], flush=True)
    metadata = output / 'github-artifacts.json'
    if metadata.is_symlink():
        raise ValueError('Artifact metadata output must not be a symlink')
    metadata.write_text(json.dumps(listing, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    fetch(args.output, load_manifest())


if __name__ == '__main__':
    main()
