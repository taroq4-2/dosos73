"""Remove a build-time QuickJS static archive from embedded runtimes.

Produces an UNSIGNED APK. Run zipalign and apksigner afterward. All runtime
entries are preserved byte-for-byte; this does not remove CPU architectures.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import zipfile

STATIC_ARCHIVE = 'usr/lib/quickjs/libquickjs.a'
ABIS = ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')

def trim_runtime(data):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate runtime entries')
        if STATIC_ARCHIVE not in names:
            raise ValueError('Expected static archive absent: review dependency update')
        removed = source.getinfo(STATIC_ARCHIVE)
        with zipfile.ZipFile(result, 'w') as target:
            for entry in source.infolist():
                if entry.filename != STATIC_ARCHIVE:
                    target.writestr(copy.copy(entry), source.read(entry))
        with zipfile.ZipFile(io.BytesIO(result.getvalue())) as check:
            assert set(check.namelist()) == set(names) - {STATIC_ARCHIVE}
            for name in check.namelist():
                assert check.read(name) == source.read(name), name
    return result.getvalue(), removed.compress_size

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve() or args.output.exists():
        raise ValueError('Output must be a new file distinct from input')
    expected = {f'lib/{abi}/libpython.zip.so' for abi in ABIS}
    report = {'input_sha256': hashlib.file_digest(args.input.open('rb'), 'sha256').hexdigest(), 'removed': [], 'signed': False}
    with zipfile.ZipFile(args.input) as source:
        if not expected.issubset(source.namelist()):
            raise ValueError('A universal four-architecture APK is required')
        with zipfile.ZipFile(args.output, 'w') as target:
            for entry in source.infolist():
                # v1 signature entries must not survive; rebuilding drops v2/v3 signing block.
                upper = entry.filename.upper()
                if upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF'):
                    continue
                data = source.read(entry)
                if entry.filename in expected:
                    data, saved = trim_runtime(data)
                    report['removed'].append({'container': entry.filename, 'path': STATIC_ARCHIVE, 'compressed_bytes': saved})
                target.writestr(copy.copy(entry), data)
    report.update(input_bytes=args.input.stat().st_size, output_unsigned_bytes=args.output.stat().st_size)
    args.report.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()

