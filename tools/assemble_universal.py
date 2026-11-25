"""Portable, fail-closed packaging of Gradle output with the pinned runtime bundle.

No historical APK or machine-specific path is needed. Alignment, production
signing and acceptance operate on the resulting unsigned APK separately.

With --abi (repeatable), the same verified bundle is packaged for the chosen CPU
architectures only (for example arm64-v8a, or armeabi-v7a + arm64-v8a + x86_64):
the whole bundle hash and every member hash are still checked against the lock
first; only lib/<other ABIs>/ members are then left out. The app must be built
for exactly those ABIs (Gradle -PtargetAbi=<abi>[,<abi>...]).
"""
import argparse
from copy import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, ZIP_DEFLATED
from generate_rebuilt_sbom import signature_entry, apk_entries, payload_hash


def runtime(name): return name.startswith(('lib/', 'assets/dosos73/'))
def sha(data): return hashlib.sha256(data).hexdigest()
ABIS = ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')


def selected(abi):
    """None (all ABIs), one ABI name, or a list of distinct supported ABI names."""
    if abi is None: return None
    abis = (abi,) if isinstance(abi, str) else tuple(abi)
    assert abis and len(set(abis)) == len(abis) and all(a in ABIS for a in abis), 'Unsupported ABI'
    return abis


def for_abi(name, abi):
    """A runtime member kept in the package: the chosen ABIs' libraries and the shared assets."""
    abis = selected(abi)
    return abis is None or not name.startswith('lib/') or any(name.startswith('lib/' + a + '/') for a in abis)


def assemble(app, bundle, lock, output, abi=None):
    abis = selected(abi)
    manifest=json.loads(lock.read_text(encoding='utf-8'))
    assert sha(bundle.read_bytes()) == manifest['runtime_sha256'], 'Unverified runtime input'
    with ZipFile(bundle) as native:
        assert len(native.namelist()) == len(set(native.namelist())), 'Duplicate runtime entry'
        for name in native.namelist():
            assert runtime(name) and '\\' not in name and '..' not in PurePosixPath(name).parts
        payload={n:native.read(n) for n in native.namelist()}
    assert {n:sha(data) for n,data in payload.items()} == manifest['members']
    # Only after the complete bundle verified: keep this ABI's members (hashes already proven).
    payload={n:data for n,data in payload.items() if for_abi(n, abis)}
    assert abis is None or all(any(n.startswith('lib/' + a + '/') for n in payload) for a in abis), 'ABI missing from runtime'
    with ZipFile(app) as original:
        assert len(original.namelist()) == len(set(original.namelist())), 'Duplicate app entry'
        app_runtime={n for n in original.namelist() if runtime(n)}
        expected=app_runtime | {'assets/dosos73/python-common.zip','assets/dosos73/python-common.json'}
        assert expected == payload.keys(), 'Unexpected Gradle/runtime path layout'
        with ZipFile(output, 'x', compression=ZIP_DEFLATED, compresslevel=9) as result:
            for entry in original.infolist():
                if not runtime(entry.filename) and not signature_entry(entry.filename):
                    result.writestr(copy(entry), original.read(entry))
            for name,data in sorted(payload.items()): result.writestr(name,data)
    report={'abi':'+'.join(abis) if abis else 'universal','app_sha256':sha(app.read_bytes()),'runtime_sha256':manifest['runtime_sha256'],
            'unsigned_apk_sha256':sha(output.read_bytes()),'payload_sha256':payload_hash(apk_entries(output))}
    output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for arg in ('app','runtime','lock','output'): parser.add_argument('--'+arg,type=Path,required=True)
    parser.add_argument('--abi',choices=ABIS,action='append',help='package only these CPU architectures (repeatable; app built with -PtargetAbi)')
    a=parser.parse_args()
    assemble(a.app,a.runtime,a.lock,a.output,a.abi)
