"""Assemble an UNSIGNED experimental universal APK with locally built engines.
Not a release tool: the output still requires alignment, signing and acceptance.
"""
import argparse
import copy
import io
import json
import hashlib
import stat
from pathlib import Path
import zipfile
from trim_universal_apk import ABIS, trim_runtime

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('engines', type=Path, help='Directory containing ABI/ffmpeg and ABI/ffprobe')
p.add_argument('output', type=Path)
p.add_argument('--python-only', action='store_true', help='Share Python files while preserving upstream FFmpeg; used by device CI')
a = p.parse_args()
if a.output.exists() or a.output.resolve() == a.apk.resolve():
    raise ValueError('Use a new output file')
for abi in (() if a.python_only else ABIS):
    for binary in ('ffmpeg', 'ffprobe'):
        path = a.engines / abi / binary
        if path.read_bytes()[:4] != b'\x7fELF':
            raise ValueError(f'Not ELF: {path}')
# Shared libraries live in the wrapper's extracted, non-executable library directory.
archives = {}
for abi in (() if a.python_only else ABIS):
    libs = sorted((a.engines / abi / 'usr' / 'lib').glob('*.so*'))
    if not libs:
        raise ValueError(f'Missing shared libraries for {abi}')
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for lib in libs:
            z.writestr('usr/lib/' + lib.name, lib.read_bytes())
    archives[abi] = archive.getvalue()
# Only identical regular files with identical modes may be shared. In particular,
# symlink entries remain inside each ABI ZIP for the wrapper's ZIP4J extractor.
with zipfile.ZipFile(a.apk) as source:
    runtimes = {}
    for abi in ABIS:
        data, _ = trim_runtime(source.read(f'lib/{abi}/libpython.zip.so'))
        runtimes[abi] = zipfile.ZipFile(io.BytesIO(data))
first = runtimes[ABIS[0]]
common_names = sorted(n for n in first.namelist() if
    all(n in z.namelist() and stat.S_ISREG(z.getinfo(n).external_attr >> 16)
        and z.getinfo(n).external_attr == first.getinfo(n).external_attr
        and z.read(n) == first.read(n) for z in runtimes.values()))
assert common_names, 'No common regular files: review runtime update'
common_buffer = io.BytesIO()
with zipfile.ZipFile(common_buffer, 'w') as common:
    for n in common_names:
        common.writestr(copy.copy(first.getinfo(n)), first.read(n))
common_data = common_buffer.getvalue()
metadata = {'version': 1, 'sha256': hashlib.sha256(common_data).hexdigest(),
    'files': {n: {'mode': first.getinfo(n).external_attr >> 16 & 0o777,
                  'bytes': first.getinfo(n).file_size,
                  'sha256': hashlib.sha256(first.read(n)).hexdigest()} for n in common_names}}
unique = {}
for abi, runtime in runtimes.items():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as z:
        for info in runtime.infolist():
            if info.filename not in metadata['files']:
                z.writestr(copy.copy(info), runtime.read(info))
    unique[abi] = buffer.getvalue()
    # A complete reconstruction proof covers every byte and POSIX attribute,
    # including ABI-specific binaries and retained symlinks, not just a count.
    with zipfile.ZipFile(io.BytesIO(unique[abi])) as specific, zipfile.ZipFile(io.BytesIO(common_data)) as shared:
        assert not set(specific.namelist()) & set(shared.namelist())
        assert set(specific.namelist()) | set(shared.namelist()) == set(runtime.namelist())
        for n in runtime.namelist():
            z = shared if n in metadata['files'] else specific
            assert z.read(n) == runtime.read(n), (abi, n)
            assert z.getinfo(n).external_attr == runtime.getinfo(n).external_attr, (abi, n)
seen = set()
with zipfile.ZipFile(a.apk) as source, zipfile.ZipFile(a.output, 'w') as target:
    for entry in source.infolist():
        name = entry.filename
        upper = name.upper()
        if upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF'):
            continue
        data = source.read(entry)
        for abi in ABIS:
            prefix = f'lib/{abi}/'
            if name == prefix + 'libpython.zip.so':
                data = unique[abi]
            elif not a.python_only and name == prefix + 'libffmpeg.zip.so':
                data = archives[abi]
                seen.add(name)
            elif not a.python_only and name in (prefix + 'libffmpeg.so', prefix + 'libffprobe.so'):
                binary = 'ffprobe' if name.endswith('libffprobe.so') else 'ffmpeg'
                data = (a.engines / abi / binary).read_bytes()
                seen.add(name)
        target.writestr(copy.copy(entry), data)
    assert not any(n.startswith('assets/dosos73/python-common.') for n in source.namelist())
    target.writestr('assets/dosos73/python-common.zip', common_data, compress_type=zipfile.ZIP_STORED)
    target.writestr('assets/dosos73/python-common.json', json.dumps(metadata, separators=(',', ':')), compress_type=zipfile.ZIP_DEFLATED)
assert len(seen) == (0 if a.python_only else 12), seen
a.output.with_suffix('.runtime-proof.json').write_text(json.dumps({'shared_files': len(common_names), 'common_sha256': metadata['sha256'], 'common_raw_bytes': sum(x['bytes'] for x in metadata['files'].values()), 'reconstruction': {abi: 'all file bytes and POSIX attributes identical' for abi in ABIS}}, indent=2))
print(json.dumps({'shared_files': len(common_names), 'unsigned_bytes': a.output.stat().st_size, 'below_80_MB': a.output.stat().st_size < 80000000, 'runtime_verified': False}))
