"""Replace only Cryptodome Python sources and ABI extensions in a compact APK."""

import argparse
import io
import json
from pathlib import Path
import re
import stat
import struct
import tarfile
from zipfile import ZipFile

from assemble_rebuilt_runtime import ABIS, COMMON, SITE, Entry, load_segment_identity, read_zip, rewrite_apk, split_runtime
from assemble_restricted_quickjs import ABI
from generate_rebuilt_sbom import apk_entries, payload_hash, runtimes
from generate_sbom import check_bytes, digest, sha256


VERSION = '3.24.1'
ARCHIVE = f'pycryptodomex-{VERSION}.tar.gz'
SOURCE_SHA = '09081666ffc599976c0b8b29caf2cf82212c0f05bed233c8f8ed53e4f6e1d356'
SOURCE_URL = 'https://pypi.org/project/pycryptodomex/3.24.1/#files'
PREFIX = SITE + 'Cryptodome/'
OFFICIAL_PRODUCER = '694dcae218409d1c12d05871ce787b12682640d7'
LICENSE_SHA = '4e04660d77c1c64e89d79537919fb8240fa21484a7e3db29f358b2c7f84ea073'
LINUX_NDK_SHA = 'dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f'
TARGETS = {'arm64-v8a': 'aarch64-linux-android29', 'armeabi-v7a': 'armv7a-linux-androideabi29',
           'x86': 'i686-linux-android29', 'x86_64': 'x86_64-linux-android29'}
RUNTIME_ZIP = 'cryptodome-runtime.zip'
ABI_PROOF = 'native-build-proof.json'


def upstream_python(sdist):
    if digest(sdist) != SOURCE_SHA:
        raise ValueError('PyCryptodomex source SHA-256 differs from the pinned release')
    root = f'pycryptodomex-{VERSION}/lib/'
    prefix = root + 'Cryptodome/'
    with tarfile.open(sdist, 'r:gz') as source:
        python_files = {}
        for member in source:
            name = member.name
            if (member.isfile() and name.startswith(prefix) and name.endswith('.py')
                    and not name.startswith(prefix + 'SelfTest/')):
                if name.removeprefix(root) in python_files:
                    raise ValueError(f'Duplicate Cryptodome source path: {name}')
                python_files[name.removeprefix(root)] = source.extractfile(member).read()
    return python_files


def loader_dependencies(data, width, headers):
    offset, address, size = (2, 3, 5) if width == 2 else (1, 2, 4)
    dyn, = [row for row in headers if row[0] == 2]
    entry = struct.Struct('<qQ' if width == 2 else '<iI')
    start, length = dyn[offset], dyn[size]
    if start + length > len(data) or length % entry.size:
        raise ValueError('Malformed Cryptodome ELF dynamic segment')
    tags = {}
    for tag, value in entry.iter_unpack(data[start:start + length]):
        if tag == 0:
            break
        tags.setdefault(tag, []).append(value)
    # DT_RPATH, DT_TEXTREL and DT_RUNPATH must be absent. Require immediate binding.
    flags = tags.get(30, [0])[0]
    if {15, 22, 29} & tags.keys() or flags & 4 or not (24 in tags or flags & 8 or tags.get(0x6ffffffb, [0])[0] & 1):
        raise ValueError('Unsafe Cryptodome ELF loader flags')
    strings, = tags[5]
    strings_size, = tags[10]
    for load in headers:
        if load[0] == 1 and load[address] <= strings and strings + strings_size <= load[address] + load[size]:
            base = load[offset] + strings - load[address]
            if base + strings_size <= len(data):
                names = data[base:base + strings_size]
                break
    else:
        raise ValueError('Missing Cryptodome ELF dynamic string table')
    return sorted(names[index:names.index(b'\x00', index)].decode('ascii') for index in tags.get(1, []))


def verified_extension(data, abi, evidence, name, exports):
    width, endian, elf_type, machine, headers = load_segment_identity(data)
    loads = [row for row in headers if row[0] == 1]
    if ((width, machine) != ABI[abi] or endian != 1 or elf_type != 3
            or any(row[0] == 3 for row in headers)
            or not any(row[0] == 0x6474E552 for row in headers)
            or any(row[-1] < 16384 or row[width] % 16384 != row[width+1] % 16384 for row in loads)
            or evidence['load_alignment'] != [row[-1] for row in loads]
            or evidence['sha256'] != sha256(data) or evidence['bytes'] != len(data) or exports <= 0):
        raise ValueError(f'Crypto extension receipt or ELF mismatch: {abi}/{name}')
    needed = loader_dependencies(data, width, headers)
    if needed != sorted(evidence['needed']) or not set(needed) <= {'libc.so', 'libdl.so', 'libm.so'}:
        raise ValueError(f'Crypto extension has unapproved loader dependencies: {abi}/{name}')


def verified_producer(builds):
    manifest = builds / 'SHA256SUMS.txt'
    expected = {'producer-commit.txt', 'compiler-version.txt', 'LICENSE.rst', ARCHIVE, 'crypto-runtime-lock.json'}
    expected.update(f'{abi}/{name}' for abi in ABIS for name in (ABI_PROOF, RUNTIME_ZIP))
    records = {}
    for line in manifest.read_text(encoding='utf-8').splitlines():
        row = re.fullmatch(r'([0-9a-f]{64})  \./(.+)', line)
        if not row or row[2] not in expected or row[2] in records:
            raise ValueError('Unexpected Cryptodome artifact manifest member')
        records[row[2]] = row[1]
    if records.keys() != expected:
        raise ValueError('Incomplete Cryptodome artifact manifest')
    root = builds.resolve()
    for name, checksum in records.items():
        path = builds / name
        if path.resolve().relative_to(root).as_posix() != name or not path.is_file() or digest(path) != checksum:
            raise ValueError(f'Cryptodome artifact member mismatch: {name}')
    commit = (builds / 'producer-commit.txt').read_text(encoding='utf-8').strip()
    lock = json.loads((builds / 'crypto-runtime-lock.json').read_text(encoding='utf-8'))
    if (commit != OFFICIAL_PRODUCER or lock['schema'] != 'dosos73-cryptodomex-lock-v1'
            or lock['version'] != VERSION or lock['source']['filename'] != ARCHIVE
            or lock['source']['sha256'] != SOURCE_SHA or records[ARCHIVE] != SOURCE_SHA
            or lock['ndk']['revision'] != '28.2.13676358' or lock['ndk']['api_level'] != 29
            or lock['ndk']['linux_sha256'] != LINUX_NDK_SHA
            or lock['license_sha256'] != LICENSE_SHA or records['LICENSE.rst'] != LICENSE_SHA):
        raise ValueError('Unexpected official Cryptodome producer or source lock')
    return lock, manifest


def official_replacement(builds, abi, lock, python, expected_elf):
    root = builds / abi
    receipt = json.loads((root / ABI_PROOF).read_text(encoding='utf-8'))
    expected_python = {SITE + name for name in python}
    expected_native = {SITE + name for name in expected_elf}
    if (receipt['schema'] != 'dosos73-cryptodomex-build-v1' or receipt['abi'] != abi
            or receipt['target'] != TARGETS[abi] or receipt['lock'] != lock
            or receipt['python'].keys() != expected_python or receipt['elf'].keys() != expected_native):
        raise ValueError(f'Cryptodome producer receipt does not match the APK inventory: {abi}')
    archive = (root / RUNTIME_ZIP).read_bytes()
    check_bytes(archive, receipt['archive'], abi)
    expected = expected_python | expected_native
    with ZipFile(io.BytesIO(archive)) as package:
        members = package.infolist()
        if (len(members) != len(expected) or {item.filename for item in members} != expected
                or sum(item.file_size for item in members) > 8 * 1024 * 1024):
            raise ValueError(f'Unexpected Cryptodome producer ZIP members: {abi}')
    contents = read_zip(archive)
    if any(item.mode != stat.S_IFREG | 0o644 for item in contents.values()):
        raise ValueError(f'Invalid Cryptodome producer file modes: {abi}')
    for name, data in python.items():
        if contents[SITE + name].data != data:
            raise ValueError(f'Cryptodome Python code differs from locked source: {abi}/{name}')
        check_bytes(data, receipt['python'][SITE + name], name)
    for name in expected_native:
        evidence = receipt['elf'][name]
        verified_extension(contents[name].data, abi, evidence, name, len(evidence['exports']))
    return {name: item.data for name, item in contents.items()}


def differences(before, after, names, abi):
    if before.keys() != after.keys():
        raise ValueError(f'Cryptodome overlay changed runtime paths: {abi}')
    changed = {}
    for name in sorted(before):
        old, new = before[name], after[name]
        if old == new:
            continue
        if name not in names or old.mode != new.mode:
            raise ValueError(f'Cryptodome overlay changed unrelated runtime data: {abi}/{name}')
        changed[name] = {'previous_sha256': sha256(old.data), 'previous_bytes': len(old.data),
                         'sha256': sha256(new.data), 'bytes': len(new.data)}
    return changed


def assemble(apk, receipt_baseline, builds, sdist, output, proof):
    if output.exists() or proof.exists() or output.resolve() == proof.resolve():
        raise ValueError('Use distinct new APK and proof paths')
    official = any((builds / abi / ABI_PROOF).is_file() for abi in ABIS)
    if official:
        lock, receipt_path = verified_producer(builds)
        sdist = sdist or builds / ARCHIVE
    else:
        if not receipt_baseline or not sdist:
            raise ValueError('Local Cryptodome builds require --receipt-baseline and --sdist')
        receipt_path = builds / 'receipt.json'
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        if (receipt['source_sha256'] != SOURCE_SHA or receipt['apk_sha256'] != digest(receipt_baseline)
                or set(receipt['modules']) != set(ABIS)):
            raise ValueError('Wrong cryptography producer, baseline or source identity')
    python = upstream_python(sdist)
    before = apk_entries(apk)
    original = runtimes(before)
    if receipt_baseline and original != runtimes(apk_entries(receipt_baseline)):
        raise ValueError('The assembly input runtime differs from the producer baseline')
    new, changes, modules = {}, {}, {}
    for abi in ABIS:
        old = original[abi]
        expected_python = {name.removeprefix(SITE) for name in old if name.startswith(PREFIX) and name.endswith('.py')}
        expected_elf = {name.removeprefix(SITE) for name in old if name.startswith(PREFIX) and name.endswith('.abi3.so')}
        if set(python) != expected_python:
            raise ValueError(f'Cryptodome extension or source parity failed: {abi}')
        if official:
            selected = official_replacement(builds, abi, lock, python, expected_elf)
        else:
            evidence = receipt['modules'][abi]
            if evidence.keys() != expected_elf:
                raise ValueError(f'Cryptodome extension inventory mismatch: {abi}')
            selected = {SITE + name: data for name, data in python.items()}
            for name, row in evidence.items():
                previous = old[SITE + name]
                if row['old_bytes'] != len(previous.data):
                    raise ValueError(f'Cryptodome baseline length mismatch: {abi}/{name}')
                data = (builds / abi / name).read_bytes()
                verified_extension(data, abi, row, name, row['retained_exports'])
                selected[SITE + name] = data
        if any(not stat.S_ISREG(old[name].mode) for name in selected):
            raise ValueError(f'Cryptodome path is not a regular file: {abi}')
        new[abi] = old | {name: Entry(data, old[name].mode) for name, data in selected.items()}
        changes[abi] = differences(old, new[abi], selected, abi)
        modules[abi] = {'python_files': len(expected_python), 'extensions': len(expected_elf)}
    shared, metadata, splits = split_runtime(new)
    overlays = {COMMON + '.zip': shared, COMMON + '.json': metadata}
    overlays.update({f'lib/{abi}/libpython.zip.so': splits[abi] for abi in ABIS})
    rewrite_apk(apk, output, overlays)
    final_entries = apk_entries(output)
    if runtimes(final_entries) != new or final_entries.keys() != before.keys():
        raise ValueError('Cryptodome APK overlay did not reconstruct the verified runtime')
    result = {'schema': 'dosos73-source-crypto-v1', 'version': VERSION,
              'source_sha256': SOURCE_SHA, 'receipt_sha256': digest(receipt_path),
              'receipt_baseline_sha256': digest(receipt_baseline or apk), 'input_apk_sha256': digest(apk),
              'input_payload_sha256': payload_hash(before), 'output_unsigned_apk_sha256': digest(output),
              'output_payload_sha256': payload_hash(final_entries),
              'outer_paths': sorted(overlays), 'changes': changes, 'modules': modules}
    if official:
        result['producer'] = OFFICIAL_PRODUCER
    with proof.open('x', encoding='utf-8') as handle:
        json.dump(result, handle, indent=2)
        handle.write('\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'builds', 'output', 'proof'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('receipt-baseline', 'sdist'):
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    result = assemble(args.apk, args.receipt_baseline, args.builds, args.sdist, args.output, args.proof)
    print(json.dumps(result['modules']))
