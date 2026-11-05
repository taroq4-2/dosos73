"""Replace only the four private QuickJS executables in an unsigned APK."""

import argparse
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from assemble_rebuilt_runtime import ABIS, load_segment_identity, rewrite_apk, sha, tool_path
from generate_rebuilt_sbom import apk_entries, payload_hash
from generate_sbom import digest


VERSION = '2026-06-04'
SOURCE_NAME = f'quickjs-{VERSION}.tar.xz'
SOURCE_SHA = 'b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a'
SOURCE = {'file': SOURCE_NAME, 'sha256': SOURCE_SHA, 'url': f'https://bellard.org/quickjs/{SOURCE_NAME}'}
ABI = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}
ALLOWED_ANDROID_LIBRARIES = {'libc.so', 'libdl.so', 'libm.so'}
NDK_ARCHIVE_SHA = {
    'linux': 'dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f',
    'win32': '6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b',
}


def verify_executable(binary, readelf, abi):
    data = binary.read_bytes()
    width, endianness, elf_type, machine, headers = load_segment_identity(data)
    if (width, machine) != ABI[abi] or endianness != 1 or elf_type != 3:
        raise ValueError(f'Wrong Android executable type: {abi}')
    loads = [row for row in headers if row[0] == 1]
    for row in loads:
        offset, address = row[2:4] if width == 2 else row[1:3]
        if row[-1] < 16384 or offset % 16384 != address % 16384:
            raise ValueError(f'QuickJS ELF load segment is not 16 KiB compatible: {abi}')
    if not any(row[0] == 0x6474E552 for row in headers):
        raise ValueError(f'QuickJS GNU RELRO segment is missing: {abi}')
    interpreters = [row for row in headers if row[0] == 3]
    if len(interpreters) != 1:
        raise ValueError(f'Expected one Android interpreter: {abi}')
    row = interpreters[0]
    start, size = (row[2], row[5]) if width == 2 else (row[1], row[4])
    expected = b'/system/bin/linker64\x00' if width == 2 else b'/system/bin/linker\x00'
    if data[start:start + size] != expected:
        raise ValueError(f'Wrong Android program interpreter: {abi}')
    dynamic = subprocess.run([str(readelf), '--elf-output-style=GNU', '-W', '-d', '--dyn-syms', str(binary)],
                             check=True, capture_output=True, text=True, timeout=20).stdout
    needed = set(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
    if (not needed or needed - ALLOWED_ANDROID_LIBRARIES or 'BIND_NOW' not in dynamic
            or re.search(r'\((?:RPATH|RUNPATH)\)', dynamic)
            or re.search(r'\b(?:dlopen|android_dlopen_ext|dlsym|qjsc_repl)\b', dynamic)):
        raise ValueError(f'Unexpected dynamic linker dependencies, symbols or flags: {abi}')
    return data, {'bits': width * 32, 'machine': machine, 'loadAlignments': [row[-1] for row in loads]}


def verify_build(folder, readelf, abi, patch_sha):
    root = folder / abi
    receipt_path = root / 'quickjs-build.json'
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    producer = (root / 'producer-commit.txt').read_text(encoding='utf-8-sig').strip()
    ndk_sha = (root / 'ndk-archive-sha256.txt').read_text(encoding='utf-8-sig').split()[0]
    patch = {'file': 'restricted-cli.patch', 'sha256': patch_sha}
    if (receipt['abi'] != abi or receipt['api'] != 29 or receipt['version'] != VERSION
            or receipt['ndk']['version'] != '28.2.13676358'
            or not re.fullmatch('[a-f0-9]{40}', producer) or NDK_ARCHIVE_SHA.get(receipt['ndk']['host']) != ndk_sha
            or receipt['source'] != SOURCE or receipt['patch'] != patch
            or digest(root / SOURCE_NAME) != SOURCE_SHA or digest(root / patch['file']) != patch_sha):
        raise ValueError(f'QuickJS source or Android build receipt mismatch: {abi}')
    binary = root / 'libqjs.so'
    data, elf = verify_executable(binary, readelf, abi)
    if receipt['binary'] != {'file': binary.name, 'sha256': sha(data), 'bytes': len(data), 'elf': elf}:
        raise ValueError(f'QuickJS build receipt does not describe the binary: {abi}')
    return data, receipt, digest(receipt_path), producer, ndk_sha


def assemble(apk, builds, output, proof, readelf, patch_sha):
    if output.exists() or proof.exists() or output.resolve() == proof.resolve():
        raise ValueError('Use distinct new APK and proof paths')
    records, replacements = {}, {}
    with ZipFile(apk) as original:
        for abi in ABIS:
            data, receipt, receipt_sha, producer, ndk_sha = verify_build(builds, readelf, abi, patch_sha)
            name = f'lib/{abi}/libqjs.so'
            old = original.read(name)
            if old == data:
                raise ValueError(f'QuickJS was already replaced: {abi}')
            replacements[name] = data
            records[abi] = {'path': name, 'sha256': sha(data), 'bytes': len(data),
                            'previous_sha256': sha(old), 'previous_bytes': len(old),
                            'receipt_sha256': receipt_sha, 'producer': producer, 'ndk_archive_sha256': ndk_sha,
                            'ndk': receipt['ndk'], 'elf': receipt['binary']['elf']}
    if len({(row['producer'], row['ndk_archive_sha256']) for row in records.values()}) != 1:
        raise ValueError('The four QuickJS variants did not use the same producer and NDK archive')
    preserved = rewrite_apk(apk, output, replacements)
    result = {'schema': 'dosos73-restricted-quickjs-v1', 'source': SOURCE, 'patch_sha256': patch_sha,
              'input_apk_sha256': digest(apk), 'output_unsigned_apk_sha256': digest(output),
              'output_payload_sha256': payload_hash(apk_entries(output)),
              'unchanged_apk_entries': preserved, 'abis': records}
    with proof.open('x', encoding='utf-8') as handle:
        json.dump(result, handle, indent=2)
        handle.write('\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'builds', 'output', 'proof', 'readelf'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--patch-sha256', required=True)
    args = parser.parse_args()
    if not re.fullmatch('[a-f0-9]{64}', args.patch_sha256):
        parser.error('The producer patch SHA-256 must be pinned')
    result = assemble(args.apk, args.builds, args.output, args.proof, tool_path(args.readelf), args.patch_sha256)
    print(json.dumps({abi: row['bytes'] for abi, row in result['abis'].items()}))


if __name__ == '__main__':
    main()
