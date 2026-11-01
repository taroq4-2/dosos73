"""Produce a CycloneDX inventory after checking transformed APK engine evidence.

Maven hashes describe resolved build inputs, not deployed DEX. Engine proof and
license correspondence are local evidence, not a complete build attestation.
"""
import argparse
import datetime
import hashlib
import io
import json
import re
import uuid
import xml.etree.ElementTree as E
import zipfile
from pathlib import Path
from urllib.parse import quote

ABIS = ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
YTDLP_SHA256 = 'b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def properties(**values):
    return [{'name': 'dosos73:' + key.replace('_', '-'), 'value': str(value)}
            for key, value in values.items()]


def zip_entries(archive, include_directories=False):
    infos = archive.infolist()
    require(len({item.filename for item in infos}) == len(infos),
            'Duplicate ZIP names are not valid correspondence evidence')
    return {item.filename: archive.read(item) for item in infos
            if include_directories or not item.is_dir()}


def check_bytes(data, expected, label):
    require(valid_hash(expected.get('sha256')), f'Invalid SHA-256 evidence: {label}')
    require(sha256(data) == expected['sha256'], f'SHA-256 mismatch: {label}')
    require(len(data) == expected.get('bytes'), f'Byte count mismatch: {label}')


def source_reference(url, source_hash):
    return {'type': 'distribution', 'url': url,
            'comment': 'Corresponding source archive, not a deployed binary hash.',
            'hashes': [{'alg': 'SHA-256', 'content': source_hash}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--maven', type=Path, required=True)
    parser.add_argument('--engine-proof', type=Path, required=True,
                        help='Assembler runtime-proof JSON; untransformed APKs are rejected')
    parser.add_argument('--license-evidence', type=Path, required=True,
                        help='Directory with Readline, GDBM and Mutagen correspondence JSON')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    apk_hash = digest(args.apk)
    proof = read_json(args.engine_proof)
    require(proof.get('schema') == 'dosos73-runtime-proof-v1', 'Unsupported engine proof schema')
    require(valid_hash(proof.get('input_apk_sha256')), 'Missing input APK hash in engine proof')
    require(valid_hash(proof.get('output_apk_sha256')), 'Missing assembler output APK hash')
    require(proof.get('ffmpeg_version') == '9.0.2' and proof.get('lame_version') == '3.100'
            and proof.get('python_only') is False, 'Unsupported or untransformed engine identity proof')
    with zipfile.ZipFile(args.apk) as apk:
        entries = zip_entries(apk, include_directories=True)

    # A signed/aligned container can differ from the assembler output; its payload
    # must still match the assembler manifest exactly (signing entries excluded).
    payload = [{'path': name, 'sha256': sha256(data), 'bytes': len(data)}
               for name, data in sorted(entries.items())
               if not (name.upper().startswith('META-INF/') and
                       (name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')) or
                        name.upper() == 'META-INF/MANIFEST.MF'))]
    payload_hash = sha256(json.dumps(payload, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=True).encode('utf-8'))
    require(proof.get('output_payload_sha256') == payload_hash,
            'APK payload differs from assembler engine proof')
    expected_paths = {f'lib/{abi}/{name}' for abi in ABIS
                      for name in ('libffmpeg.so', 'libffprobe.so', 'libffmpeg.zip.so')}
    require(set(proof.get('ffmpeg_files', {})) == expected_paths,
            'Engine proof must cover both executables and the library archive for every ABI')
    for name, expected in proof['ffmpeg_files'].items():
        require(name in entries, f'Missing deployed engine: {name}')
        check_bytes(entries[name], expected, name)
    require(set(proof.get('ffmpeg_libraries', {})) == set(ABIS),
            'Missing per-ABI FFmpeg library evidence')
    require(set(proof.get('ffmpeg_configurations', {})) == set(ABIS),
            'Missing per-ABI FFmpeg configurations')
    engine_properties = []
    for abi in ABIS:
        config = proof['ffmpeg_configurations'][abi]
        for field in ('config_h_sha256', 'config_mak_sha256'):
            require(valid_hash(config.get(field)), f'Missing {abi} {field}')
            engine_properties += properties(**{f'{abi}_{field}': config[field]})
        require(config.get('config_gpl') is False and config.get('config_nonfree') is False
                and config.get('config_libmp3lame') is True,
                f'Unsupported FFmpeg license/LAME configuration: {abi}')
        for binary in ('libffmpeg.so', 'libffprobe.so'):
            data = entries[f'lib/{abi}/{binary}']
            require(data.startswith(b'\x7fELF') and b'\0n9.0.2\0' in data,
                    f'FFmpeg version evidence mismatch: {abi}/{binary}')
        with zipfile.ZipFile(io.BytesIO(entries[f'lib/{abi}/libffmpeg.zip.so'])) as archive:
            libs = zip_entries(archive)
        expected = proof['ffmpeg_libraries'][abi]
        require(set(libs) == set(expected), f'FFmpeg library set mismatch: {abi}')
        for name, descriptor in expected.items():
            check_bytes(libs[name], descriptor, f'{abi}/{name}')
            require(libs[name].startswith(b'\x7fELF'), f'Not an ELF library: {abi}/{name}')
        require('usr/lib/libavcodec.so' in libs and b'\x003.100\x00' in libs['usr/lib/libavcodec.so'],
                f'Missing LAME 3.100 binary identity: {abi}')
    # Android resource shrinking can rename raw/ytdlp (for example to res/Nc).
    ytdlp_paths = [name for name, data in entries.items()
                  if name.startswith('res/') and sha256(data) == YTDLP_SHA256]
    require(len(ytdlp_paths) == 1, 'Pinned yt-dlp raw resource absent, changed or duplicated')

    with zipfile.ZipFile(io.BytesIO(entries['assets/dosos73/python-common.zip'])) as archive:
        common = zip_entries(archive)
    runtimes = {}
    for abi in ABIS:
        with zipfile.ZipFile(io.BytesIO(entries[f'lib/{abi}/libpython.zip.so'])) as archive:
            unique = zip_entries(archive)
        require(not set(common) & set(unique), f'Duplicate shared/ABI Python runtime path: {abi}')
        runtimes[abi] = {**common, **unique}

    evidence = args.license_evidence
    readline_path = evidence / 'readline-deb-inputs.json'
    readline = read_json(readline_path)
    runtime_input_path = Path(__file__).with_name('runtime-inputs.json')
    runtime_input = read_json(runtime_input_path)
    inputs = proof.get('runtime_inputs', {})
    require(inputs.get('manifest_sha256') == digest(runtime_input_path),
            'Pinned runtime-input manifest differs from assembler evidence')
    def portable_manifest(value):
        # Evidence retrieval paths are not part of binary/source correspondence.
        return {**value, 'abis': [{**row, 'files': [
            {key: item for key, item in file.items() if key != 'local_relative_path'}
            for file in row['files']]} for row in value['abis']]}
    require(portable_manifest(runtime_input) == portable_manifest(readline),
            'Readline license evidence differs from pinned runtime inputs')
    require(inputs.get('version') == readline.get('version') == '8.3.6',
            'Readline version mismatch')
    require(inputs.get('recipe_commit') == readline.get('recipe_commit') ==
            'd005fcc8961ca61cd3715555526c324e3d0e68e4', 'Readline recipe mismatch')
    require({row['abi'] for row in readline['abis']} == set(ABIS) and len(readline['abis']) == 4,
            'Readline manifest must cover four ABIs once')
    expected_override_keys = {(abi, f'usr/lib/lib{name}.so.8.3')
                              for abi in ABIS for name in ('readline', 'history')}
    overrides = proof.get('runtime_overrides', [])
    require({(row['abi'], row['path']) for row in overrides} == expected_override_keys
            and len(overrides) == 8, 'Missing or duplicate Readline runtime overrides')
    for row in overrides:
        require(row.get('version') == '8.3.6', 'Unsupported Readline override version')
        check_bytes(runtimes[row['abi']][row['path']],
                    {'sha256': row['after_sha256'], 'bytes': row['after_bytes']},
                    f"Readline override {row['abi']}/{row['path']}")
    for row in readline['abis']:
        require({item['relative_path'] for item in row['files']} ==
                {'usr/lib/libreadline.so.8.3', 'usr/lib/libhistory.so.8.3'},
                f"Incomplete Readline input files: {row['abi']}")
        for item in row['files']:
            check_bytes(runtimes[row['abi']][item['relative_path']], item,
                        f"Readline {row['abi']}/{item['relative_path']}")

    gdbm_path = evidence / 'gdbm-deb-correspondence.json'
    gdbm = read_json(gdbm_path)
    require({(row['abi'], row['runtime_path']) for row in gdbm} ==
            {(abi, f'usr/lib/{name}') for abi in ABIS
             for name in ('libgdbm.so', 'libgdbm_compat.so')} and len(gdbm) == 8,
            'GDBM correspondence must cover both ELF libraries for every ABI')
    for row in gdbm:
        require(row.get('byte_identical') is True and
                row['runtime_sha256'] == row['deb_sha256'] and
                row['official_deb'].startswith('gdbm_1.26-1_'), 'Unproven GDBM correspondence')
        data = runtimes[row['abi']][row['runtime_path']]
        require(data.startswith(b'\x7fELF') and sha256(data) == row['runtime_sha256'],
                f"Deployed GDBM differs from official binary: {row['abi']}/{row['runtime_path']}")
    mutagen_path = evidence / 'mutagen-source-correspondence.json'
    mutagen = read_json(mutagen_path)
    mutagen_paths = {row['runtime_path'] for row in mutagen}
    require(mutagen_paths and len(mutagen_paths) == len(mutagen), 'Empty/duplicate Mutagen evidence')
    for abi in ABIS:
        deployed = {name for name in runtimes[abi]
                    if name.startswith('usr/lib/python3.12/site-packages/mutagen/') and name.endswith('.py')}
        require(deployed == mutagen_paths, f'Mutagen source file set mismatch: {abi}')
        for row in mutagen:
            require(row.get('identical') is True and row['sha256'] == row['source_sha256']
                    and row['source_path'].startswith('mutagen-1.47.0/'), 'Unproven Mutagen source correspondence')
            require(sha256(runtimes[abi][row['runtime_path']]) == row['source_sha256'],
                    f"Deployed Mutagen source changed: {abi}/{row['runtime_path']}")
        require(b'version = (1, 47, 0)' in
                runtimes[abi]['usr/lib/python3.12/site-packages/mutagen/__init__.py'],
                f'Mutagen version mismatch: {abi}')

    components = []
    seen_refs = {}
    maven = read_json(args.maven)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    for row in maven:
        require(valid_hash(row['sha256']), 'Invalid Maven input SHA-256')
        purl = ('pkg:maven/' + quote(row['group'], safe='') + '/' + quote(row['name'], safe='')
                + '@' + quote(row['version'], safe='') + '?type=' + quote(row['type'], safe=''))
        ref = purl
        if ref in seen_refs:
            if seen_refs[ref] == row['sha256']:
                continue
            ref += '#artifact-' + row['sha256']
            if ref in seen_refs:
                continue
        seen_refs[ref] = row['sha256']
        item = {'type': 'library', 'bom-ref': ref, 'group': row['group'], 'name': row['name'],
                'version': row['version'], 'purl': purl,
                'hashes': [{'alg': 'SHA-256', 'content': row['sha256']}],
                'properties': properties(artifact_file=row['file'],
                    hash_scope='Resolved Maven build-input archive; not deployed DEX or transformed APK bytes.',
                    correspondence='Supplied runtime inventory; no complete causal build attestation.')}
        licenses = []
        for pom in (cache / row['group'] / row['name'] / row['version']).glob('*/*.pom'):
            try:
                root = E.parse(pom).getroot()
                ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
                for node in root.findall('./m:licenses/m:license', ns):
                    name = node.findtext('m:name', namespaces=ns)
                    url = node.findtext('m:url', namespaces=ns)
                    if name:
                        value = {'name': name}
                        if url and url.startswith(('http://', 'https://')):
                            value['url'] = url
                        license_item = {'license': value}
                        if license_item not in licenses:
                            licenses.append(license_item)
            except E.ParseError:
                pass
        if licenses:
            item['licenses'] = licenses
        else:
            item['properties'] += properties(license_review='Not established from a directly cached POM; inherited/native licenses require further review.')
        components.append(item)
    maven_count = len(components)
    native_count = 0

    def inspect(files, prefix=''):
        nonlocal native_count
        for name, data in files.items():
            path = prefix + name
            if data.startswith(b'\x7fELF'):
                native_count += 1
                components.append({'type': 'file', 'bom-ref': 'file:' + path, 'name': path,
                    'hashes': [{'alg': 'SHA-256', 'content': sha256(data)}],
                    'properties': properties(uncompressed_bytes=len(data), hash_scope='Actual deployed uncompressed ELF bytes.',
                        license_review='File inventory; complete source/component correspondence remains separate evidence.')})
            elif name.endswith('.zip.so') or name == 'assets/dosos73/python-common.zip':
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    inspect(zip_entries(archive), path + '!/')
    inspect(entries)

    def library(ref, name, version, license_expression, source_url=None, source_hash=None, extra=None):
        item = {'type': 'library', 'bom-ref': ref, 'name': name, 'version': version,
                'licenses': [{'expression': license_expression}], 'properties': extra or []}
        if source_url:
            item['externalReferences'] = [source_reference(source_url, source_hash)]
        components.append(item)
        return item

    engine_properties += properties(license_evidence='Assembler configuration hashes and checked per-ABI ELF payloads; corresponding recipe tools/build_compact_ffmpeg.sh. Configurations accompany the separately supplied corresponding-source bundle. Not a complete causal or legal certification.')
    ffmpeg = library('ffmpeg:9.0.2', 'FFmpeg', '9.0.2', 'LGPL-2.1-or-later', extra=engine_properties)
    ffmpeg['externalReferences'] = [{'type': 'vcs', 'url': 'https://github.com/FFmpeg/FFmpeg/tree/946fcce07b6dcd0331c8cc609192aeff5e1924f8'}]
    library('lame:3.100', 'LAME', '3.100', 'LGPL-2.0-or-later',
            'https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz',
            'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e',
            properties(hash_scope='Source archive hash is on the distribution reference, not a compiled LAME binary hash.',
                       correspondence='CONFIG_LIBMP3LAME enabled and LAME identity present in every checked libavcodec ELF; statically linked into FFmpeg.'))
    components.append({'type': 'library', 'bom-ref': 'yt-dlp:2026.10.21',
        'name': 'yt-dlp application-pinned zipapp', 'version': '2026.10.21',
        'hashes': [{'alg': 'SHA-256', 'content': YTDLP_SHA256}],
        'properties': properties(artifact_file=ytdlp_paths[0], hash_scope='Actual deployed raw-resource zipapp bytes.',
            license_evidence='Original Unlicense plus bundled ISC/MIT notices; see docs/licenses/yt-dlp-2026.10.21.txt.')})
    library('readline:8.3.6', 'GNU Readline and History', '8.3.6', 'GPL-3.0-or-later',
            'https://ftp.gnu.org/gnu/readline/readline-8.3.tar.gz',
            'fe5383204467828cd495ee8d1d3c037a7eba1389c22bc6a041f627976f9061cc',
            properties(correspondence='All eight deployed ELF files match official Termux 8.3.6 package payloads and assembler overrides.',
                source_configuration='Base 8.3 plus GNU patches 001-006; Termux recipe d005fcc8961ca61cd3715555526c324e3d0e68e4/packages/readline, including build.sh, rlconf.h.patch and inputrc. See separately supplied source/notices bundle and docs/launch/NATIVE_SOURCES.md.'))
    library('gdbm:1.26', 'GNU GDBM and compatibility library', '1.26', 'GPL-3.0-or-later',
            'https://ftp.gnu.org/gnu/gdbm/gdbm-1.26.tar.gz',
            '6a24504a14de4a744103dcb936be976df6fbe88ccff26065e54c1c47946f4a5e',
            properties(correspondence='All eight deployed ELF files match official Termux gdbm 1.26-1 package payloads.',
                source_configuration='Termux recipe candidate 7c74f85f3b36991d07d4cfb0d1be458c1b4f0896/packages/gdbm/build.sh, --enable-libgdbm-compat, no local package patches. Producer global recipe commit is not attested; see docs/launch/NATIVE_SOURCES.md and separately supplied source/notices bundle.'))
    library('mutagen:1.47.0', 'Mutagen', '1.47.0', 'GPL-2.0-or-later',
            'https://files.pythonhosted.org/packages/81/e6/64bc71b74eef4b68e61eb921dcf72dabd9e4ec4af1e11891bbd312ccbb77/mutagen-1.47.0.tar.gz',
            '719fadef0a978c31b4cf3c956261b3c58b6948b32023078a2117b1de09f0fc99',
            properties(correspondence=f'Every deployed Mutagen .py source ({len(mutagen)} files per ABI), reconstructed from common and ABI-specific archives, matches upstream source evidence.',
                source_configuration='Unmodified matched Python files, no native configure step; upstream source and COPYING are included in separately supplied source/notices bundle.'))
    metadata_properties = properties(
        scope='Resolved Maven build-input hashes, actual nested ELF bytes and payload-verified engine/component identities. Flat inventory; not a complete dependency graph, native-license audit or vulnerability certification.',
        maven_inventory_sha256=digest(args.maven),
        runtime_input_manifest_sha256=digest(runtime_input_path),
        maven_hash_scope='Input AAR/JAR archive bytes from releaseRuntimeClasspath; not deployed DEX; no complete causal build attestation.',
        engine_proof_sha256=digest(args.engine_proof),
        assembler_input_apk_sha256=proof['input_apk_sha256'],
        assembler_unsigned_output_apk_sha256=proof['output_apk_sha256'],
        checked_output_payload_sha256=payload_hash,
        proof_limit='Engine proof is local assembler evidence; payload hashes survive alignment/signing. It is not an independently signed producer attestation.')
    for path in (readline_path, gdbm_path, mutagen_path):
        metadata_properties += properties(**{f'evidence_{path.name}_sha256': digest(path)})
    bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.6',
           'serialNumber': 'urn:uuid:' + str(uuid.uuid5(uuid.NAMESPACE_URL, apk_hash)), 'version': 1,
           'metadata': {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z'),
               'component': {'type': 'application', 'bom-ref': 'dosos73:' + args.version,
                   'name': 'com.os73.dosos73', 'version': args.version,
                   'hashes': [{'alg': 'SHA-256', 'content': apk_hash}],
                   'licenses': [{'expression': 'GPL-3.0-or-later'}]}, 'properties': metadata_properties},
           'components': components}
    require(len({item['bom-ref'] for item in components}) == len(components), 'Duplicate component references')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bom, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'maven_components': maven_count, 'elf_files': native_count,
                      'total_components': len(components), 'apk_sha256': apk_hash}))


if __name__ == '__main__':
    main()
