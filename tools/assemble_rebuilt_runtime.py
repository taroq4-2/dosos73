"""Assemble an unsigned compact APK with a source-built Python 3.12 core.

App/FFmpeg/QuickJS payloads and inherited vendor modules must remain identical.
Alignment, signing, device acceptance and corresponding sources are separate.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import stat
import struct
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


from trim_universal_apk import ABIS
PLATFORM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}
STDLIB = 'usr/lib/python3.12/'
SITE = STDLIB + 'site-packages/'
COMMON = 'assets/dosos73/python-common'
REBUILT_ROOT = 'native-runtime-out'
REBUILT_SHA = '490c5ba3be12d770edc8d3361c7d0453ed2f1534'


def sha(data):
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class Entry:
    data: bytes
    mode: int


def read_zip(source):
    entries = {}
    with ZipFile(io.BytesIO(source)) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = PurePosixPath(item.filename)
            mode = item.external_attr >> 16
            if (name.is_absolute() or '..' in name.parts or '\\' in item.orig_filename
                    or item.orig_filename != item.filename
                    or item.filename != name.as_posix() or not item.filename.startswith('usr/')
                    or item.filename in entries or not (stat.S_ISREG(mode) or stat.S_ISLNK(mode))):
                raise ValueError(f'Unsafe or duplicate runtime member: {item.filename}')
            entries[item.filename] = Entry(archive.read(item), mode)
    return entries


def encode_zip(entries):
    output = io.BytesIO()
    with ZipFile(output, 'w') as archive:
        for path, entry in sorted(entries.items()):
            info = ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = entry.mode << 16
            archive.writestr(info, entry.data, compress_type=ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def verified_artifact(path, abi, expected, run_id=41715765777, producer=REBUILT_SHA):
    if (expected['workflow_run']['id'] != run_id or expected['workflow_run']['head_sha'] != producer):
        raise ValueError(f'Unexpected GitHub producer identity: {abi}')
    with path.open('rb') as handle:
        digest = hashlib.file_digest(handle, 'sha256').hexdigest()
    if 'sha256:' + digest != expected['digest']:
        raise ValueError(f'GitHub artifact digest changed: {abi}')
    prefix = f'{REBUILT_ROOT}/{abi}'
    with ZipFile(path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError(f'Duplicate GitHub artifact members: {abi}')
        runtime = archive.read(prefix + '/libpython-core.zip.so')
        launcher = archive.read(prefix + '/libpython.so')
        proof_data = archive.read(prefix + '/native-build-proof.json')
        proof = json.loads(proof_data)
        receipt = json.loads(archive.read('source-receipt.json'))
        if (receipt['abi'] != abi or receipt['core_archive_sha256'] != proof['archive_sha256']
                or receipt['core_proof'] != {'bytes': len(proof_data), 'sha256': sha(proof_data)}
                or receipt['host_python_source']['sha256'] != proof['lock']['sources']['python']['sha256']
                or receipt['ndk']['archive_sha256'] != proof['lock']['ndk_archive_sha256']):
            raise ValueError(f'Native source receipt mismatch: {abi}')
        ndk_digest = archive.read('ndk-archive-sha256.txt').decode().split()[0]
        if ndk_digest != receipt['ndk']['archive_sha256']:
            raise ValueError(f'NDK archive receipt mismatch: {abi}')
        for field, name in (('python/build/config.log', 'target-python-config.log'),
                            ('python/build/pyconfig.h', 'target-python-pyconfig.h')):
            data = archive.read(name)
            if receipt['termux_files'][field] != {'bytes': len(data), 'sha256': sha(data)}:
                raise ValueError(f'Target Python configuration receipt mismatch: {abi}')
    if (proof['abi'] != abi or proof['unresolved_needed'] or sha(runtime) != proof['archive_sha256']
            or sha(launcher) != proof['launcher']['sha256'] or len(launcher) != proof['launcher']['bytes']):
        raise ValueError(f'Native candidate proof mismatch: {abi}')
    entries = read_zip(runtime)
    records = {item['path']: item for item in proof['payload']}
    if entries.keys() != records.keys() or len(records) != len(proof['payload']):
        raise ValueError(f'Native member list mismatch: {abi}')
    for name, entry in entries.items():
        item = records[name]
        if stat.S_ISLNK(entry.mode):
            if entry.data != item['symlink'].encode():
                raise ValueError(f'Native symlink mismatch: {name}')
        elif sha(entry.data) != item['sha256'] or len(entry.data) != item['bytes']:
            raise ValueError(f'Native file mismatch: {name}')
    return entries, records, launcher, proof


def resolve_link(entries, name):
    seen = set()
    while name in entries and stat.S_ISLNK(entries[name].mode):
        if name in seen:
            raise ValueError(f'Runtime symlink cycle: {name}')
        seen.add(name)
        target = entries[name].data.decode()
        name = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
        if target.startswith('/') or '\\' in target or not name.startswith('usr/'):
            raise ValueError(f'Runtime symlink escapes root: {target}')
    return name


def select_runtime(old, new, records, launcher_record):
    retained = {name: value for name, value in old.items()
                if not name.startswith('usr/lib/') or name.startswith(SITE)}
    requested = set(old) - set(retained)
    superseded = {name for name in requested - set(new)
                  if name == STDLIB + 'tempfile.py.orig'
                  or re.fullmatch(STDLIB + r'lib2to3/(Pattern)?Grammar3\.12\.3\.final\.0\.pickle', name)}
    for alias in requested.intersection(new):
        if not stat.S_ISLNK(old[alias].mode):
            continue
        prior = resolve_link(old, alias)
        replacement = resolve_link(new, alias)
        replacement_elf = records.get(replacement, {}).get('elf')
        if (prior in requested and prior not in new and old[prior].data.startswith(b'\x7fELF')
                and replacement_elf and replacement_elf.get('soname') in (None, PurePosixPath(alias).name)):
            superseded.add(prior)
    missing = requested - set(new) - superseded
    if missing:
        raise ValueError(f'Unaccounted baseline runtime paths: {sorted(missing)}')
    selected = requested.intersection(new)
    packages = {name.removeprefix(STDLIB).split('/')[0] for name in requested
                if name.startswith(STDLIB) and '/' in name.removeprefix(STDLIB)}
    for name in new:
        if (name.startswith(STDLIB) and name.endswith('.py')
                and name.removeprefix(STDLIB).split('/')[0] in packages
                and not name.startswith(SITE)):
            selected.add(name)

    pending = deque(selected)

    def include(path):
        if path not in new:
            raise ValueError(f'Rebuilt runtime dependency missing: {path}')
        if path not in selected:
            selected.add(path)
            pending.append(path)

    def include_needed(elf):
        for needed in elf.get('needed', []):
            if needed not in PLATFORM:
                include('usr/lib/' + needed)

    include_needed(launcher_record)
    while pending:
        name = pending.popleft()
        if stat.S_ISLNK(new[name].mode):
            link = new[name].data.decode()
            target = posixpath.normpath(posixpath.join(posixpath.dirname(name), link))
            if link.startswith('/') or '\\' in link or not target.startswith('usr/'):
                raise ValueError(f'Rebuilt runtime symlink outside its root: {name}')
            if resolve_link(new, name) not in new:
                raise ValueError(f'Rebuilt runtime symlink does not reach a member: {name}')
            include(target)
        include_needed(records[name].get('elf', {}))

    return retained, {name: new[name] for name in sorted(selected)}, sorted(superseded)


def dynamic_identity(readelf, path):
    output = subprocess.run([str(readelf), '--elf-output-style=GNU', '-d', str(path)],
                            check=True, capture_output=True, text=True).stdout
    return re.findall(r'\((NEEDED|SONAME|RPATH|RUNPATH)\).*?\[(.*?)\]', output)


def load_segment_identity(data):
    if len(data) < 52 or data[:4] != b'\x7fELF' or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError('Invalid ELF header')
    order = '<' if data[5] == 1 else '>'
    if data[4] == 2:
        if len(data) < 64:
            raise ValueError('Truncated ELF64 header')
        offset = struct.unpack_from(order + 'Q', data, 32)[0]
        size, count = struct.unpack_from(order + 'HH', data, 54)
        layout = order + 'IIQQQQQQ'
    else:
        offset = struct.unpack_from(order + 'I', data, 28)[0]
        size, count = struct.unpack_from(order + 'HH', data, 42)
        layout = order + 'IIIIIIII'
    if not count or size < struct.calcsize(layout) or offset + size * count > len(data):
        raise ValueError('Missing or truncated ELF program headers')
    headers = [struct.unpack_from(layout, data, offset + size * i) for i in range(count)]
    segments = [header for header in headers if header[0] in (1, 2, 3, 0x6474E552)]
    if not any(header[0] == 1 for header in segments):
        raise ValueError('ELF has no load segment')
    elf_type, machine = struct.unpack_from(order + 'HH', data, 16)
    return data[4], data[5], elf_type, machine, segments


def runtime_identity(readelf, path):
    output = subprocess.run([str(readelf), '--elf-output-style=GNU', '-W', '-d', '--dyn-syms', str(path)],
                            check=True, capture_output=True, text=True).stdout
    dependencies = re.findall(r'\((NEEDED|SONAME|RPATH|RUNPATH)\).*?\[(.*?)\]', output)
    symbols = re.findall(
        r'^\s*\d+:\s+([0-9a-fA-F]+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)([^\r\n]*)',
        output, re.MULTILINE)
    symbols = [(value, size, kind, binding, visibility, 'DEF' if section.isdigit() else section, name.strip())
               for value, size, kind, binding, visibility, section, name in symbols]
    return dependencies, symbols, load_segment_identity(path.read_bytes())


def strip_symbols(data, scratch, strip, readelf, mode, label):
    with tempfile.TemporaryDirectory(dir=scratch) as folder:
        path = Path(folder) / PurePosixPath(label).name
        path.write_bytes(data)
        before = runtime_identity(readelf, path)
        subprocess.run([str(strip), '--strip-' + mode, str(path)], check=True, capture_output=True)
        if before != runtime_identity(readelf, path):
            raise ValueError('Stripping symbols changed the dynamic loader, symbols or load segments')
        return path.read_bytes()


def verify_retained_dependencies(runtime, retained, scratch, readelf):
    with tempfile.TemporaryDirectory(dir=scratch) as folder:
        path = Path(folder) / 'retained.so'
        for name, entry in retained.items():
            if not entry.data.startswith(b'\x7fELF'):
                continue
            path.write_bytes(entry.data)
            for kind, value in dynamic_identity(readelf, path):
                if kind != 'NEEDED' or value in PLATFORM:
                    continue
                dependency = runtime.get(resolve_link(runtime, 'usr/lib/' + value))
                if not dependency or not dependency.data.startswith(b'\x7fELF'):
                    raise ValueError(f'Missing provider for retained {name}: {value}')


def split_runtime(runtimes):
    first = runtimes[ABIS[0]]
    common = {name: entry for name, entry in first.items() if stat.S_ISREG(entry.mode)
              and all(runtime.get(name) == entry for runtime in runtimes.values())}
    common_data = encode_zip(common)
    metadata = json.dumps({'version': 1, 'sha256': sha(common_data),
                           'files': {name: {'mode': entry.mode & 0o777, 'bytes': len(entry.data),
                                            'sha256': sha(entry.data)}
                                     for name, entry in sorted(common.items())}},
                          separators=(',', ':')).encode()
    if not 1 <= len(common) <= 5000 or len(metadata) > 512 * 1024:
        raise ValueError('Shared runtime exceeds app metadata limits')
    splits = {abi: encode_zip({name: entry for name, entry in runtime.items() if name not in common})
              for abi, runtime in runtimes.items()}
    for abi, data in splits.items():
        specific = read_zip(data)
        if specific.keys() & common.keys() or (specific | read_zip(common_data)) != runtimes[abi]:
            raise ValueError(f'Compact runtime reconstruction mismatch: {abi}')
    return common_data, metadata, splits


def apk_replacements(launchers, runtimes):
    common_data, metadata, splits = split_runtime(runtimes)
    replacement = {COMMON + '.zip': common_data, COMMON + '.json': metadata}
    for abi in ABIS:
        replacement[f'lib/{abi}/libpython.so'] = launchers[abi]
        replacement[f'lib/{abi}/libpython.zip.so'] = splits[abi]
    return replacement


def rewrite_apk(source, output, replacement):
    if output.exists():
        raise ValueError(f'Use a new APK output: {output}')
    preserved = 0
    with ZipFile(source) as old, ZipFile(output, 'w') as new:
        if not replacement.keys() <= set(old.namelist()) or len(old.namelist()) != len(set(old.namelist())):
            raise ValueError('Source APK does not contain exact compact runtime paths')
        for info in old.infolist():
            if info.filename.upper().startswith('META-INF/') and (
                    info.filename.upper().endswith(('.SF', '.RSA', '.DSA', '.EC'))
                    or info.filename.upper() == 'META-INF/MANIFEST.MF'):
                continue
            if info.filename in replacement:
                data = replacement[info.filename]
            else:
                data = old.read(info)
                preserved += 1
            new.writestr(info, data)
    with ZipFile(source) as old, ZipFile(output) as new:
        for name in new.namelist():
            if name not in replacement and old.read(name) != new.read(name):
                raise ValueError(f'Unexpected APK code/assets change: {name}')
    return preserved


def tool_path(path):
    # LLVM chooses readelf/strip compatibility from the invoked filename.
    # Resolving its final symlink can change the executable's command mode.
    tool = path.absolute()
    if not tool.is_file():
        raise FileNotFoundError(tool)
    return tool


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True, help='Previously compacted universal APK')
    parser.add_argument('--debug-apk', type=Path, help='Optional matching compact debug APK for instrumentation')
    parser.add_argument('--strip-mode', choices=('debug', 'unneeded'), default='unneeded')
    parser.add_argument('--artifacts', type=Path, required=True, help='GitHub metadata and four ABI.zip artifacts')
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--producer', required=True, help='Exact successful producer commit')
    parser.add_argument('--strip-tool', type=Path, required=True)
    parser.add_argument('--readelf-tool', type=Path, required=True, help='NDK llvm-readelf executable')
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    args = parser.parse_args()
    if not re.fullmatch('[a-f0-9]{40}', args.producer) or args.run_id <= 0:
        parser.error('Producer must be a full commit hash and run ID must be positive')
    strip = tool_path(args.strip_tool)
    readelf = tool_path(args.readelf_tool)
    baseline = args.apk.resolve(strict=True)
    debug = args.debug_apk.resolve(strict=True) if args.debug_apk else None
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    artifact_root = args.artifacts
    artifacts = json.loads((artifact_root / 'github-artifacts.json').read_text(encoding='utf-8-sig'))['artifacts']
    if (len(artifacts) != len(ABIS) or
            {artifact['name'] for artifact in artifacts} != {f'native-core-candidate-{abi}' for abi in ABIS}):
        raise ValueError('Unexpected native artifact list')
    expected = {artifact['name'].removeprefix('native-core-candidate-'): artifact for artifact in artifacts}
    runtimes = {}
    launchers = {}
    with baseline.open('rb') as handle:
        input_sha = hashlib.file_digest(handle, 'sha256').hexdigest()
    report = {'schema': 'dosos73-rebuilt-runtime-proof-v1', 'input_apk_sha256': input_sha,
              'producer': args.producer, 'run_id': args.run_id,
              'strip_mode': args.strip_mode, 'abis': {},
              'assembler_sha256': sha(Path(__file__).read_bytes()),
              'strip_tool_sha256': sha(strip.read_bytes()), 'readelf_tool_sha256': sha(readelf.read_bytes())}
    with ZipFile(baseline) as apk:
        shared = read_zip(apk.read(COMMON + '.zip'))
        for abi in ABIS:
            previous = read_zip(apk.read(f'lib/{abi}/libpython.zip.so'))
            if previous.keys() & shared.keys():
                raise ValueError(f'Existing common runtime shadows an ABI member: {abi}')
            old = previous | shared
            core, records, launcher, proof = verified_artifact(
                artifact_root / f'{abi}.zip', abi, expected[abi], args.run_id, args.producer)
            retained, candidate, superseded = select_runtime(old, core, records, proof['launcher'])
            stripped = {}
            for name, entry in candidate.items():
                if 'elf' not in records[name]:
                    continue
                compact = strip_symbols(entry.data, output, strip, readelf, args.strip_mode, name)
                candidate[name] = Entry(compact, entry.mode)
                if compact != entry.data:
                    stripped[name] = {'source_sha256': sha(entry.data), 'sha256': sha(compact),
                                      'source_bytes': len(entry.data), 'bytes': len(compact)}
            runtimes[abi] = retained | candidate
            verify_retained_dependencies(runtimes[abi], retained, output, readelf)
            launchers[abi] = launcher
            report['abis'][abi] = {'artifact_sha256': expected[abi]['digest'], 'core_files': len(core),
                                  'selected_core_files': len(candidate), 'retained_files': len(retained),
                                  'superseded': superseded, 'stripped': stripped}
            print(abi, len(old), len(core), len(candidate), len(retained),
                  sum(e['source_bytes'] - e['bytes'] for e in stripped.values()), flush=True)
    replacement = apk_replacements(launchers, runtimes)
    for source, name in ((baseline, 'release-unsigned.apk'), (debug, 'debug-unsigned.apk')):
        if source:
            destination = output / name
            count = rewrite_apk(source, destination, replacement)
            report[name] = {'unchanged_apk_entries': count, 'bytes': destination.stat().st_size}
            with ZipFile(destination) as assembled:
                payload = [{'path': entry.filename, 'sha256': sha(assembled.read(entry)), 'bytes': entry.file_size}
                           for entry in sorted(assembled.infolist(), key=lambda item: item.filename)]
            digest = sha(json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode())
            report[name]['output_payload_sha256'] = digest
            if name == 'release-unsigned.apk':
                report['output_payload_sha256'] = digest
    report['replacement_sha256'] = {name: sha(data) for name, data in replacement.items()}
    (output / 'integration-proof.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print({name: result['bytes'] for name, result in report.items() if name.endswith('.apk')}, flush=True)


if __name__ == '__main__':
    main()
