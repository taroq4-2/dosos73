"""Portable, fail-closed packaging of Gradle output with the pinned runtime bundle.

No historical APK or machine-specific path is needed. Alignment, production
signing and acceptance operate on the resulting unsigned APK separately.
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


def assemble(app, bundle, lock, output):
    manifest=json.loads(lock.read_text(encoding='utf-8'))
    assert sha(bundle.read_bytes()) == manifest['runtime_sha256'], 'Unverified runtime input'
    with ZipFile(bundle) as native:
        assert len(native.namelist()) == len(set(native.namelist())), 'Duplicate runtime entry'
        for name in native.namelist():
            assert runtime(name) and '\\' not in name and '..' not in PurePosixPath(name).parts
        payload={n:native.read(n) for n in native.namelist()}
    assert {n:sha(data) for n,data in payload.items()} == manifest['members']
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
    report={'app_sha256':sha(app.read_bytes()),'runtime_sha256':manifest['runtime_sha256'],
            'unsigned_apk_sha256':sha(output.read_bytes()),'payload_sha256':payload_hash(apk_entries(output))}
    output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for arg in ('app','runtime','lock','output'): parser.add_argument('--'+arg,type=Path,required=True)
    a=parser.parse_args()
    assemble(a.app,a.runtime,a.lock,a.output)
