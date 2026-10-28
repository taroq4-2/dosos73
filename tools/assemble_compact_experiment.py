"""Assemble an UNSIGNED experimental universal APK with locally built engines.
Not a release tool: the output still requires alignment, signing and acceptance.
"""
import argparse
import copy
import io
import json
from pathlib import Path
import zipfile
from trim_universal_apk import ABIS, trim_runtime

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('engines', type=Path, help='Directory containing ABI/ffmpeg and ABI/ffprobe')
p.add_argument('output', type=Path)
a = p.parse_args()
if a.output.exists() or a.output.resolve() == a.apk.resolve():
    raise ValueError('Use a new output file')
for abi in ABIS:
    for binary in ('ffmpeg', 'ffprobe'):
        path = a.engines / abi / binary
        if path.read_bytes()[:4] != b'\x7fELF':
            raise ValueError(f'Not ELF: {path}')
# Shared libraries live in the wrapper's extracted, non-executable library directory.
archives = {}
for abi in ABIS:
    libs = sorted((a.engines / abi / 'usr' / 'lib').glob('*.so'))
    if not libs:
        raise ValueError(f'Missing shared libraries for {abi}')
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for lib in libs:
            z.writestr('usr/lib/' + lib.name, lib.read_bytes())
    archives[abi] = archive.getvalue()
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
                data, _ = trim_runtime(data)
            elif name == prefix + 'libffmpeg.zip.so':
                data = archives[abi]
                seen.add(name)
            elif name in (prefix + 'libffmpeg.so', prefix + 'libffprobe.so'):
                binary = 'ffprobe' if name.endswith('libffprobe.so') else 'ffmpeg'
                data = (a.engines / abi / binary).read_bytes()
                seen.add(name)
        target.writestr(copy.copy(entry), data)
assert len(seen) == 12, seen
print(json.dumps({'unsigned_bytes': a.output.stat().st_size, 'below_80_MB': a.output.stat().st_size < 80000000, 'runtime_verified': False}))
