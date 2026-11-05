"""Fetch the checksum-pinned QuickJS and Cryptodome producer artifacts."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from zipfile import ZipFile

from assemble_rebuilt_runtime import ABIS
from assemble_restricted_quickjs import SOURCE_NAME
from assemble_source_crypto import ARCHIVE
from fetch_rebuilt_runtime import file_hash, gh_json


API = 'repos/taroq4-2/dosos73/actions'
PINS = Path(__file__).with_name('source-runtime-update-artifacts.json')
QUICKJS_MEMBERS = {'libqjs.so', 'quickjs-build.json', 'elf.txt', 'QuickJS-LICENSE', SOURCE_NAME,
                   'restricted-cli.patch', 'producer-commit.txt', 'ndk-archive-sha256.txt'}
CRYPTO_MEMBERS = {ARCHIVE, 'LICENSE.rst', 'crypto-runtime-lock.json', 'compiler-version.txt',
                  'producer-commit.txt', 'SHA256SUMS.txt'} | {
    f'{abi}/{file}' for abi in ABIS for file in ('cryptodome-runtime.zip', 'native-build-proof.json')}


def matching_artifacts(label, producer, names):
    run, commit, pins = producer['run_id'], producer['producer'], producer['artifacts']
    if type(run) is not int or run < 1 or not re.fullmatch('[a-f0-9]{40}', commit) or pins.keys() != names:
        raise ValueError(f'Invalid {label} producer pins')
    state = gh_json(f'{API}/runs/{run}')
    if (state['id'] != run or state['head_sha'] != commit or state['repository']['full_name'] != 'taroq4-2/dosos73'
            or state['status'] != 'completed' or state['conclusion'] != 'success'):
        raise ValueError(f'{label} producer is not a successful run at the pinned commit')
    artifacts = gh_json(f'{API}/runs/{run}/artifacts?per_page=100')['artifacts']
    selected = {}
    for item in artifacts:
        name = item['name']
        if name not in names:
            continue
        pin = pins[name]
        if (name in selected or item['expired'] or item['id'] != pin['id'] or item['size_in_bytes'] != pin['bytes']
                or item['digest'] != 'sha256:' + pin['sha256'] or item['workflow_run']['id'] != run
                or item['workflow_run']['head_sha'] != commit or not re.fullmatch('[a-f0-9]{64}', pin['sha256'])):
            raise ValueError(f'{label} artifact metadata does not match pin: {name}')
        selected[name] = pin
    if selected.keys() != names:
        raise ValueError(f'Incomplete {label} producer artifacts')
    return selected


def download(pin, output):
    for attempt in range(3):
        with output.open('wb') as stream:
            try:
                transfer = subprocess.run(['gh', 'api', f"{API}/artifacts/{pin['id']}/zip"],
                                          stdout=stream, stderr=subprocess.PIPE, check=False, timeout=90)
                ok = transfer.returncode == 0
            except subprocess.TimeoutExpired:
                ok = False
        if ok and output.stat().st_size == pin['bytes'] and file_hash(output) == pin['sha256']:
            return
        if attempt < 2:
            time.sleep(2)
    raise ValueError(f"Artifact download did not match checksum: {pin['id']}")


def extract_exact(zip_path, target, names, producer):
    with ZipFile(zip_path) as archive:
        files = [row for row in archive.infolist() if not row.is_dir()]
        if len(files) != len(names) or {row.filename for row in files} != names:
            raise ValueError(f'Unexpected files in {zip_path.name}')
        if archive.read('producer-commit.txt').decode().strip() != producer:
            raise ValueError(f'Producer sidecar is inconsistent: {zip_path.name}')
        for item in files:
            path = target / item.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as source, path.open('xb') as destination:
                shutil.copyfileobj(source, destination)


def fetch(output, pins):
    if not os.environ.get('GH_TOKEN'):
        raise ValueError('An Actions read credential is required')
    quickjs, crypto = pins['quickjs'], pins['crypto']
    q_names = {'quickjs-android-' + abi for abi in ABIS}
    q_artifacts = matching_artifacts('QuickJS', quickjs, q_names)
    crypto_name = 'cryptodomex-runtime-candidate'
    crypto_artifacts = matching_artifacts('Cryptodome', crypto, {crypto_name})
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(dir=output) as scratch:
        for name, pin in sorted((q_artifacts | crypto_artifacts).items()):
            path = Path(scratch) / (name + '.zip')
            download(pin, path)
            if name in q_names:
                target = output / 'quickjs' / name.removeprefix('quickjs-android-')
                extract_exact(path, target, QUICKJS_MEMBERS, quickjs['producer'])
            else:
                extract_exact(path, output / 'crypto', CRYPTO_MEMBERS, crypto['producer'])
            print(f'Verified pinned {name}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    fetch(args.output, json.loads(PINS.read_text(encoding='utf-8')))
