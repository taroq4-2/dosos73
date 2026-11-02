"""Collect a built core runtime with static parity/ELF evidence, not acceptance.

Existing third-party Python packages and QuickJS remain separate inputs during
integration. This core candidate alone must not replace a production runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def elf(path, readelf, abi):
    data = path.read_bytes()
    if not data.startswith(b'\x7fELF'):
        return None
    bits, endian = data[4] * 32, {1: '<', 2: '>'}[data[5]]
    expected_bits, expected_machine = {'armeabi-v7a': (32, 40), 'arm64-v8a': (64, 183),
                                       'x86': (32, 3), 'x86_64': (64, 62)}[abi]
    machine = struct.unpack_from(endian + 'H', data, 18)[0]
    if (bits, machine) != (expected_bits, expected_machine):
        raise ValueError('ELF architecture mismatch: ' + str(path))
    phoff = struct.unpack_from(endian + ('Q' if bits == 64 else 'I'), data, 32 if bits == 64 else 28)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54 if bits == 64 else 42)
    loads, interpreter = [], False
    for i in range(count):
        fields = struct.unpack_from(endian + ('IIQQQQQQ' if bits == 64 else 'IIIIIIII'), data, phoff + i * size)
        if fields[0] == 3:
            interpreter = True
        if fields[0] != 1:
            continue
        offset, address, alignment = (fields[2], fields[3], fields[7]) if bits == 64 else (fields[1], fields[2], fields[7])
        loads.append(alignment >= 16384 and offset % 16384 == address % 16384)
    dynamic = subprocess.run([str(readelf), '-d', str(path)], capture_output=True, text=True, check=True).stdout
    soname = re.findall(r'\(SONAME\).*?\[(.*?)\]', dynamic)
    needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    if bits == 64 and (not loads or not all(loads)):
        raise ValueError('64-bit ELF lacks 16KB alignment: ' + str(path))
    return {'bits': bits, 'machine': machine, 'soname': soname[0] if soname else None, 'needed': needed,
            'has_interpreter': interpreter, 'sha256': sha(data), 'bytes': len(data),
            'alignment_16k': bool(loads) and all(loads)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--abi', required=True)
    p.add_argument('--prefix', type=Path, default=Path('/data/data/com.termux/files/usr'))
    p.add_argument('--readelf', type=Path, required=True)
    p.add_argument('--output', type=Path, default=Path('native-runtime-out'))
    args = p.parse_args()
    required = json.loads(Path('native-required-modules.json').read_text())['abis'][args.abi]
    lib = args.prefix / 'lib'
    python = lib / 'python3.12'
    actual_modules = {x.name for x in (python / 'lib-dynload').glob('*.so')}
    missing = set(required['extension_modules']) - actual_modules
    if missing:
        raise ValueError('Missing previously shipped modules: ' + str(sorted(missing)))
    paths = set(x for x in python.rglob('*') if x.is_file() or x.is_symlink())
    # Include all native libraries/links from the rebuilt dependency prefix,
    # preserving dynamic modules/providers as well as the root SONAME aliases.
    for candidate in lib.rglob('*'):
        if candidate.is_file() or candidate.is_symlink():
            if '.so' in candidate.name and 'python3.12' not in candidate.parts:
                paths.add(candidate)
    launcher = args.prefix / 'bin/python3.12'
    launcher_info = elf(launcher, args.readelf, args.abi)
    if not launcher_info or not launcher_info['has_interpreter']:
        raise ValueError('libpython.so must be a launchable Android executable, not a DSO')
    records, sonames, providers = [], set(), set()
    for path in sorted(paths):
        relative = 'usr/' + path.relative_to(args.prefix).as_posix()
        if path.is_symlink():
            target = os.readlink(path)
            if os.path.isabs(target) or not path.resolve().is_relative_to(args.prefix.resolve()):
                raise ValueError('Unsafe relocated runtime symlink: ' + str(path))
            records.append({'path': relative, 'symlink': target})
            providers.add(path.name)
            continue
        info = elf(path, args.readelf, args.abi)
        record = {'path': relative, 'sha256': sha(path.read_bytes()), 'bytes': path.stat().st_size}
        if info:
            record['elf'] = info
            providers.add(path.name)
            if info['soname']:
                sonames.add(info['soname'])
        records.append(record)
    missing_sonames = set(required['sonames']) - sonames
    if missing_sonames:
        raise ValueError('Missing previously shipped SONAMEs: ' + str(sorted(missing_sonames)))
    android = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}
    unresolved = []
    for record in records + [{'path': '@launcher', 'elf': launcher_info}]:
        for needed in record.get('elf', {}).get('needed', []):
            if needed not in providers | sonames | android:
                unresolved.append({'from': record['path'], 'needed': needed})
    if unresolved:
        raise ValueError('Unresolved DT_NEEDED: ' + str(unresolved))
    args.output.mkdir(parents=True, exist_ok=True)
    out = args.output / args.abi
    out.mkdir()
    shutil.copyfile(launcher, out / 'libpython.so')
    archive = out / 'libpython-core.zip.so'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(paths):
            name = 'usr/' + path.relative_to(args.prefix).as_posix()
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            if path.is_symlink():
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                payload = os.readlink(path).encode()
            else:
                info.external_attr = (stat.S_IFREG | (path.stat().st_mode & 0o777)) << 16
                payload = path.read_bytes()
            zipped.writestr(info, payload)
    proof = {'status': 'static-built-core candidate only; not runtime accepted', 'abi': args.abi,
             'lock': json.loads(Path('native-runtime-lock.json').read_text()),
             'launcher': launcher_info, 'module_parity': sorted(required['extension_modules']),
             'soname_parity': sorted(required['sonames']), 'unresolved_needed': unresolved,
             'payload': records, 'archive_sha256': sha(archive.read_bytes()),
             'preserve_at_integration': 'existing site-packages/CA bundle and QuickJS launcher; never replace them with an empty set'}
    (out / 'native-build-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps({'abi': args.abi, 'modules_preserved': len(required['extension_modules']), 'files': len(records), 'bytes': archive.stat().st_size}))


if __name__ == '__main__':
    main()
