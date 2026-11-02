"""Apply a verified compact APK runtime to its matching UNSIGNED AGP AAB.

Manifest/resources, dex, delivery metadata and mapping remain AGP-generated.
The result still requires jarsigner, bundletool validation and device testing.
"""
import argparse
import copy
import json
from pathlib import Path
import zipfile

from assemble_compact_experiment import ABIS, digest_record, new_zip_info


def signature(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (
        upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF')


def assemble(bundle, original, compact, output):
    proof_path = output.with_suffix('.bundle-proof.json')
    if output.exists() or proof_path.exists():
        raise ValueError('Output and proof must be new files')
    with zipfile.ZipFile(bundle) as source, zipfile.ZipFile(original) as before, zipfile.ZipFile(compact) as after:
        for archive in (source, before, after):
            if len(archive.namelist()) != len(set(archive.namelist())) or archive.testzip():
                raise ValueError('Duplicate or corrupt ZIP entries')
        native = {n for n in before.namelist() if n.startswith('lib/') and not n.endswith('/')}
        bundle_native = {n[5:] for n in source.namelist() if n.startswith('base/lib/') and not n.endswith('/')}
        if native != bundle_native or native != {n for n in after.namelist() if n.startswith('lib/') and not n.endswith('/')}:
            raise ValueError('Native delivery paths changed')
        if {n.split('/')[1] for n in native} != set(ABIS):
            raise ValueError('All four ABIs are required')
        shared = {'assets/dosos73/python-common.zip', 'assets/dosos73/python-common.json'}
        old_names = {n for n in before.namelist() if not signature(n)}
        new_names = {n for n in after.namelist() if not signature(n)}
        if new_names != old_names | shared or old_names & shared:
            raise ValueError('Unexpected compact APK entries')
        for name in old_names - native:
            if before.read(name) != after.read(name):
                raise ValueError(f'Non-runtime APK payload changed: {name}')
        for name in native:
            if source.read('base/' + name) != before.read(name):
                raise ValueError(f'AAB and original APK build mismatch: {name}')
        # Bind this bundle to the precise application build as well as its runtime.
        for name in old_names:
            if name.endswith('.dex'):
                other = 'base/dex/' + name
            elif name.startswith('assets/dexopt/'):
                other = 'BUNDLE-METADATA/com.android.tools.build.profiles/' + name.rsplit('/', 1)[1]
            elif name.startswith('assets/'):
                other = 'base/' + name
            else:
                continue
            if source.read(other) != before.read(name):
                raise ValueError(f'AAB and APK source mismatch: {name}')
        if any('base/' + n in source.namelist() for n in shared):
            raise ValueError('Bundle already compacted')
        records = []
        with zipfile.ZipFile(output, 'w') as target:
            for info in source.infolist():
                if signature(info.filename):
                    continue
                name = info.filename
                data = after.read(name[5:]) if name.startswith('base/') and name[5:] in native else source.read(info)
                target.writestr(copy.copy(info), data)
                if name.startswith('base/lib/'):
                    records.append({'path': name, **digest_record(data)})
            for name in sorted(shared):
                data = after.read(name)
                target.writestr(new_zip_info('base/' + name), data, compresslevel=9)
                records.append({'path': 'base/' + name, **digest_record(data)})
    proof = {'signed': False, 'input_bundle': digest_record(bundle.read_bytes()),
             'original_apk': digest_record(original.read_bytes()), 'compact_apk': digest_record(compact.read_bytes()),
             'output_bundle': digest_record(output.read_bytes()), 'runtime_payload': records,
             'unchanged': 'AGP manifest, resources, DEX, delivery metadata, mapping and other assets'}
    proof_path.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('bundle', 'original_apk', 'compact_apk', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assemble(args.bundle, args.original_apk, args.compact_apk, args.output)
