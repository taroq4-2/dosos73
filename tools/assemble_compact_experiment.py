"""Assemble an UNSIGNED experimental universal APK with locally built engines.
Not a release tool: the output still requires alignment, signing and acceptance.
"""
import argparse
import copy
import io
import json
import re
import stat
from pathlib import Path
import zipfile
from trim_universal_apk import ABIS, trim_runtime
from fetch_runtime_overrides import MANIFEST, load_manifest, sha256, validate_elf

ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)


def new_zip_info(name, compression=zipfile.ZIP_DEFLATED):
    info = zipfile.ZipInfo(name, date_time=ZIP_EPOCH)
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    info.compress_type = compression
    return info


def digest_record(data):
    return {'sha256': sha256(data), 'bytes': len(data)}


def read_overrides(directory):
    manifest = load_manifest()
    expected = {f"{entry['abi']}/{item['relative_path']}"
                for entry in manifest['abis'] for item in entry['files']}
    actual = set()
    for path in directory.rglob('*'):
        if path.is_symlink():
            raise ValueError(f'Override symlinks are forbidden: {path}')
        if path.is_file():
            actual.add(path.relative_to(directory).as_posix())
    if actual != expected:
        raise ValueError(f'Expected exactly eight pinned overrides; missing={sorted(expected - actual)}, extra={sorted(actual - expected)}')
    payloads = {}
    for entry in manifest['abis']:
        abi = entry['abi']
        for item in entry['files']:
            name = item['relative_path']
            path = directory / abi / name
            data = path.read_bytes()
            if len(data) != item['bytes'] or sha256(data) != item['sha256']:
                raise ValueError(f'Override digest mismatch: {path}')
            validate_elf(data, abi, path)
            payloads[(abi, name)] = data
    return manifest, payloads


def configuration_record(directory):
    config_h = (directory / 'config.h').read_bytes()
    config_mak = (directory / 'config.mak').read_bytes()
    h_text = config_h.decode('utf-8')
    mak_text = config_mak.decode('utf-8')
    result = {'config_h_sha256': sha256(config_h), 'config_mak_sha256': sha256(config_mak)}
    for symbol in ('GPL', 'NONFREE', 'LIBMP3LAME'):
        h_values = re.findall(r'^#define CONFIG_' + symbol + r' ([01])$', h_text, re.MULTILINE)
        mak_values = re.findall(r'^(!?)CONFIG_' + symbol + r'=yes$', mak_text, re.MULTILINE)
        if len(h_values) != 1 or len(mak_values) != 1:
            raise ValueError(f'Missing or duplicate configuration symbol CONFIG_{symbol}: {directory}')
        enabled = h_values[0] == '1'
        if enabled != (mak_values[0] == ''):
            raise ValueError(f'Contradictory configuration symbol CONFIG_{symbol}: {directory}')
        result['config_' + symbol.lower()] = enabled
    if result['config_gpl'] or result['config_nonfree'] or not result['config_libmp3lame']:
        raise ValueError(f'Expected GPL=0, NONFREE=0 and LIBMP3LAME=1 configuration: {directory}')
    return result

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk', type=Path)
    p.add_argument('engines', type=Path, help='Directory containing ABI/ffmpeg and ABI/ffprobe')
    p.add_argument('output', type=Path)
    p.add_argument('--python-only', action='store_true', help='Share Python files while preserving upstream FFmpeg; used by device CI')
    p.add_argument('--runtime-overrides', type=Path, help='Pinned ABI/relative_path Readline replacements from fetch_runtime_overrides.py')
    p.add_argument('--ffmpeg-version', help='Declared pinned FFmpeg source version, required with replacement engines')
    p.add_argument('--lame-version', help='Declared pinned LAME source version, required with replacement engines')
    a = p.parse_args()
    if not a.python_only and any(value is None or re.fullmatch(r'[0-9]+(?:\.[0-9]+)+', value) is None for value in (a.ffmpeg_version, a.lame_version)):
        p.error('--ffmpeg-version and --lame-version must name the pinned numeric source versions')
    if a.output.exists() or a.output.resolve() == a.apk.resolve():
        raise ValueError('Use a new output file')
    proof_path = a.output.with_suffix('.runtime-proof.json')
    if proof_path.exists() or proof_path.resolve() == a.apk.resolve():
        raise ValueError('Use a new runtime proof file')
    manifest, overrides = read_overrides(a.runtime_overrides) if a.runtime_overrides else (None, {})
    input_digest = sha256(a.apk.read_bytes())
    engine_data = {}
    library_records = {}
    configuration_records = {}
    for abi in (() if a.python_only else ABIS):
        configuration_records[abi] = configuration_record(a.engines / abi)
        has_lame_version = False
        for binary in ('ffmpeg', 'ffprobe'):
            path = a.engines / abi / binary
            data = path.read_bytes()
            validate_elf(data, abi, path)
            if b'\x00n' + a.ffmpeg_version.encode('ascii') + b'\x00' not in data:
                raise ValueError(f'Expected declared FFmpeg n{a.ffmpeg_version} identity: {path}')
            engine_data[f"lib/{abi}/lib{binary}.so"] = data
        # These libraries live in the wrapper's extracted library directory.
        libs = sorted((a.engines / abi / 'usr' / 'lib').glob('*.so*'))
        if not libs:
            raise ValueError(f'Missing shared libraries for {abi}')
        archive = io.BytesIO()
        library_records[abi] = {}
        with zipfile.ZipFile(archive, 'w') as z:
            for lib in libs:
                data = lib.read_bytes()
                validate_elf(data, abi, lib)
                if lib.name.startswith('libavcodec.so') and b'\x00' + a.lame_version.encode('ascii') + b'\x00' in data:
                    has_lame_version = True
                name = 'usr/lib/' + lib.name
                library_records[abi][name] = digest_record(data)
                z.writestr(new_zip_info(name), data, compresslevel=9)
        if not has_lame_version:
            raise ValueError(f'Expected declared LAME {a.lame_version} identity in libavcodec: {abi}')
        engine_data[f'lib/{abi}/libffmpeg.zip.so'] = archive.getvalue()
    # Override only existing regular entries; preserve all ZipInfo attributes and
    # every symlink, then identify identical regular files for the shared ZIP.
    runtimes = {}
    override_records = []
    with zipfile.ZipFile(a.apk) as source:
        for abi in ABIS:
            data, _ = trim_runtime(source.read(f'lib/{abi}/libpython.zip.so'))
            with zipfile.ZipFile(io.BytesIO(data)) as original:
                buffer = io.BytesIO()
                names = set(original.namelist())
                for override_abi, name in overrides:
                    if override_abi == abi and (name not in names or not stat.S_ISREG(original.getinfo(name).external_attr >> 16)):
                        raise ValueError(f'Override target must be an existing regular runtime file: {abi}/{name}')
                with zipfile.ZipFile(buffer, 'w') as modified:
                    for info in original.infolist():
                        before = original.read(info)
                        after = overrides.get((abi, info.filename), before)
                        if (abi, info.filename) in overrides:
                            override_records.append({
                                'abi': abi, 'path': info.filename,
                                'before_sha256': sha256(before), 'after_sha256': sha256(after),
                                'before_bytes': len(before), 'after_bytes': len(after),
                                'mode': info.external_attr >> 16,
                                'version': manifest['version'],
                            })
                        modified.writestr(copy.copy(info), after)
            runtimes[abi] = zipfile.ZipFile(io.BytesIO(buffer.getvalue()))
    first = runtimes[ABIS[0]]
    common_names = sorted(n for n in first.namelist() if
        all(n in z.namelist() and stat.S_ISREG(z.getinfo(n).external_attr >> 16)
            and z.getinfo(n).external_attr == first.getinfo(n).external_attr
            and z.read(n) == first.read(n) for z in runtimes.values()))
    if not common_names:
        raise ValueError('No common regular files: review runtime update')
    common_buffer = io.BytesIO()
    with zipfile.ZipFile(common_buffer, 'w') as common:
        for n in common_names:
            common.writestr(copy.copy(first.getinfo(n)), first.read(n))
    common_data = common_buffer.getvalue()
    metadata = {'version': 1, 'sha256': sha256(common_data),
        'files': {n: {'mode': first.getinfo(n).external_attr >> 16 & 0o777,
                      'bytes': first.getinfo(n).file_size,
                      'sha256': sha256(first.read(n))} for n in common_names}}
    unique = {}
    for abi, runtime in runtimes.items():
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as z:
            for info in runtime.infolist():
                if info.filename not in metadata['files']:
                    z.writestr(copy.copy(info), runtime.read(info))
        unique[abi] = buffer.getvalue()
        # Reconstruct the intentionally overridden runtime, including symlinks.
        with zipfile.ZipFile(io.BytesIO(unique[abi])) as specific, zipfile.ZipFile(io.BytesIO(common_data)) as shared:
            assert not set(specific.namelist()) & set(shared.namelist())
            assert set(specific.namelist()) | set(shared.namelist()) == set(runtime.namelist())
            for n in runtime.namelist():
                z = shared if n in metadata['files'] else specific
                assert z.read(n) == runtime.read(n), (abi, n)
                assert z.getinfo(n).external_attr == runtime.getinfo(n).external_attr, (abi, n)
    seen = set()
    ffmpeg_records = {}
    output_payload = []
    with zipfile.ZipFile(a.apk) as source, zipfile.ZipFile(a.output, 'w') as target:
        if any(n.startswith('assets/dosos73/python-common.') for n in source.namelist()):
            raise ValueError('Input APK already contains a shared Python runtime')
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
                elif name in (prefix + 'libffmpeg.zip.so', prefix + 'libffmpeg.so', prefix + 'libffprobe.so'):
                    if not a.python_only:
                        data = engine_data[name]
                    elif name.endswith(('libffmpeg.so', 'libffprobe.so')):
                        validate_elf(data, abi, name)
                    ffmpeg_records[name] = digest_record(data)
                    seen.add(name)
            target.writestr(copy.copy(entry), data)
            output_payload.append({'path': name, **digest_record(data)})
        shared_assets = {
            'assets/dosos73/python-common.zip': common_data,
            'assets/dosos73/python-common.json': json.dumps(metadata, separators=(',', ':')).encode('utf-8'),
        }
        for name, data in shared_assets.items():
            compression = zipfile.ZIP_STORED if name.endswith('.zip') else zipfile.ZIP_DEFLATED
            target.writestr(new_zip_info(name, compression), data, compresslevel=9)
            output_payload.append({'path': name, **digest_record(data)})
    if len(seen) != 12:
        raise ValueError(f'Expected exactly 12 FFmpeg APK inputs, found: {sorted(seen)}')
    output_payload.sort(key=lambda item: item['path'])
    if len(output_payload) != len({item['path'] for item in output_payload}):
        raise ValueError('Duplicate output APK entries')
    payload_digest = sha256(json.dumps(output_payload, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8'))
    proof = {
        'schema': 'dosos73-runtime-proof-v1',
        'input_apk_sha256': input_digest,
        'output_apk_sha256': sha256(a.output.read_bytes()),
        'output_payload_sha256': payload_digest,
        'output_payload': output_payload,
        'output_payload_serialization': 'UTF-8 JSON list sorted by path; sort_keys=True,separators=(comma,colon),ensure_ascii=True; v1 signature entries excluded',
        'python_only': a.python_only,
        'ffmpeg_version': None if a.python_only else a.ffmpeg_version,
        'lame_version': None if a.python_only else a.lame_version,
        'source_version_scope': 'declared pinned build inputs; runtime acceptance separate',
        'ffmpeg_configurations': configuration_records,
        'shared_files': len(common_names),
        'common_sha256': metadata['sha256'],
        'common_raw_bytes': sum(x['bytes'] for x in metadata['files'].values()),
        'ffmpeg_files': ffmpeg_records,
        'ffmpeg_libraries': library_records,
        'runtime_overrides': override_records,
        'runtime_inputs': None if manifest is None else {
            'version': manifest['version'], 'recipe_commit': manifest['recipe_commit'],
            'manifest_sha256': sha256(MANIFEST.read_bytes())},
        'reconstruction': {abi: 'all overridden-runtime file bytes and POSIX attributes identical' for abi in ABIS},
    }
    proof_path.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    for runtime in runtimes.values():
        runtime.close()
    print(json.dumps({'shared_files': len(common_names), 'unsigned_bytes': a.output.stat().st_size,
                      'below_80_MB': a.output.stat().st_size < 80000000, 'runtime_verified': False}))


if __name__ == '__main__':
    main()
