"""Create a pinned runtime-only input from a verified APK and source-built OpenSSL.

Retains the compressed bytes of unchanged ZIP entries and removes only CPython's
eight extension test modules. It does not remove codecs, extractors or ABIs.
"""
import argparse
from copy import copy
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import struct
import zlib
from zipfile import ZipFile, ZIP_DEFLATED

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
TESTS = frozenset(('_ctypes_test', '_testbuffer', '_testcapi', '_testclinic',
                  '_testimportmultiple', '_testinternalcapi', '_testmultiphase', '_testsinglephase'))


def sha(data): return hashlib.sha256(data).hexdigest()


class RawZip(ZipFile):
    def member(self, info, compressed, data=None):
        entry = copy(info)
        entry.flag_bits &= ~8
        entry.compress_size = len(compressed)
        if data is not None:
            entry.CRC = zlib.crc32(data)
            entry.file_size = len(data)
        self.fp.seek(self.start_dir)
        entry.header_offset = self.fp.tell()
        self.fp.write(entry.FileHeader(zip64=False))
        self.fp.write(compressed)
        self.start_dir = self.fp.tell()
        self.filelist.append(entry)
        self.NameToInfo[entry.filename] = entry
        self._didModify = True


def raw(source, entry):
    source.fp.seek(entry.header_offset + 26)
    names, extras = struct.unpack('<HH', source.fp.read(4))
    source.fp.seek(names + extras, io.SEEK_CUR)
    return source.fp.read(entry.compress_size)


def prepare(base, expected, openssl, output):
    assert sha(base.read_bytes()) == expected, 'Unverified donor APK'
    report = {'base_sha256': expected, 'removed': [], 'replaced': []}
    with ZipFile(base) as apk:
        payload = {n: apk.read(n) for n in apk.namelist() if n.startswith(('lib/', 'assets/dosos73/'))}
    for abi in ABIS:
        name = f'lib/{abi}/libpython.zip.so'
        before = payload[name]
        buffer = io.BytesIO()
        kept = {}
        with ZipFile(io.BytesIO(before)) as source, RawZip(buffer, 'w') as target:
            assert len(source.namelist()) == len(set(source.namelist()))
            for entry in source.infolist():
                assert entry.compress_type == ZIP_DEFLATED
                module = Path(entry.filename).name.split('.')[0]
                if '/lib-dynload/' in entry.filename and module in TESTS and entry.filename.endswith('.so'):
                    report['removed'].append({'abi':abi, 'path':entry.filename, 'sha256':sha(source.read(entry))})
                    continue
                original = source.read(entry)
                if entry.filename in ('usr/lib/libcrypto.so.3', 'usr/lib/libssl.so.3'):
                    data = (openssl / abi / Path(entry.filename).name).read_bytes()
                    assert data.startswith(b'\x7fELF') and stat.S_ISREG(entry.external_attr >> 16)
                    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
                    target.member(entry, compressor.compress(data) + compressor.flush(), data)
                    report['replaced'].append({'abi':abi, 'path':entry.filename,
                        'before_sha256':sha(original), 'after_sha256':sha(data)})
                else:
                    data = original
                    target.member(entry, raw(source, entry))
                kept[entry.filename] = (data, entry.external_attr)
        payload[name] = buffer.getvalue()
        with ZipFile(io.BytesIO(payload[name])) as check:
            assert {i.filename: (check.read(i), i.external_attr) for i in check.infolist()} == kept
    assert len(report['removed']) == 32 and len(report['replaced']) == 8
    with ZipFile(output, 'x', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(payload.items()): archive.writestr(name, data)
    report['runtime_sha256'] = sha(output.read_bytes())
    report['runtime_bytes'] = output.stat().st_size
    report['members'] = {n: sha(data) for n,data in sorted(payload.items())}
    output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n', encoding='utf-8')
    print(f"Runtime bundle: {report['runtime_bytes']} bytes; 32 test modules removed; 8 libraries replaced")


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-apk', type=Path, required=True)
    parser.add_argument('--base-sha256', required=True)
    parser.add_argument('--openssl', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    a=parser.parse_args()
    prepare(a.base_apk,a.base_sha256,a.openssl,a.output)
