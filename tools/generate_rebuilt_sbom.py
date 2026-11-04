"""Generate a payload-checked CycloneDX inventory for the rebuilt Python core.

The baseline inventory supplies identities for unchanged Maven/FFmpeg/vendor
inputs. Source receipts and transformation records are local build evidence.
They do not establish reproducible builds or complete license compliance.
"""

import argparse
import copy
import datetime
import io
import json
from pathlib import Path
import re
import stat
import uuid
from zipfile import ZipFile

from assemble_rebuilt_runtime import ABIS, COMMON, read_zip, select_runtime, verified_artifact
from generate_sbom import (check_bytes, digest, properties, read_json, require,
                           sha256, source_reference, valid_hash, zip_entries)


HOSTS = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'arm-linux-androideabi',
         'x86': 'i686-linux-android', 'x86_64': 'x86_64-linux-android'}
CORE_COMPONENTS = {
    'python': ('CPython', 'PSF-2.0'), 'openssl': ('OpenSSL', 'Apache-2.0'),
    'libexpat': ('Expat', 'MIT'), 'liblzma': ('liblzma', '0BSD'),
    'libsqlite': ('SQLite', None),
}


def signature_entry(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF'))
                                             or upper == 'META-INF/MANIFEST.MF')


def apk_entries(path):
    with ZipFile(path) as archive:
        return {name: data for name, data in zip_entries(archive, include_directories=True).items()
                if not signature_entry(name)}


def payload_hash(entries):
    records = [{'path': name, 'sha256': sha256(data), 'bytes': len(data)}
               for name, data in sorted(entries.items())]
    return sha256(json.dumps(records, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode())


def elf_inventory(entries, prefix=''):
    result = {}
    for name, data in entries.items():
        path = prefix + name
        if data.startswith(b'\x7fELF'):
            result[path] = {'sha256': sha256(data), 'bytes': len(data)}
        elif name.endswith('.zip.so') or name == COMMON + '.zip':
            with ZipFile(io.BytesIO(data)) as archive:
                nested = elf_inventory(zip_entries(archive), path + '!/')
            require(not result.keys() & nested.keys(), 'Duplicate nested ELF path')
            result.update(nested)
    return result


def hash_value(component):
    values = [item['content'] for item in component.get('hashes', []) if item.get('alg') == 'SHA-256']
    require(len(values) == 1 and valid_hash(values[0]), 'Expected exactly one valid component SHA-256')
    return values[0]


def validate_baseline(baseline, bom, proof):
    require(bom.get('bomFormat') == 'CycloneDX' and bom.get('specVersion') == '1.6',
            'Unsupported baseline SBOM format')
    require(hash_value(bom['metadata']['component']) == digest(baseline),
            'Baseline SBOM does not describe the supplied APK')
    entries = apk_entries(baseline)
    require(proof.get('schema') == 'dosos73-runtime-proof-v1'
            and proof.get('output_payload_sha256') == payload_hash(entries),
            'Baseline engine proof does not describe its APK payload')
    expected = elf_inventory(entries)
    actual = {row['bom-ref'].removeprefix('file:'): hash_value(row)
              for row in bom.get('components', []) if row.get('type') == 'file'}
    require(actual == {name: item['sha256'] for name, item in expected.items()},
            'Baseline SBOM ELF inventory is incomplete or mismatched')
    refs = [row['bom-ref'] for row in bom.get('components', [])]
    require(len(refs) == len(set(refs)), 'Duplicate baseline component references')
    return entries


def runtimes(entries):
    require(COMMON + '.json' in entries and COMMON + '.zip' in entries,
            'The compact shared Python runtime is required')
    require(len(entries[COMMON + '.json']) <= 512 * 1024, 'Shared manifest exceeds app limit')
    common = read_zip(entries[COMMON + '.zip'])
    manifest = json.loads(entries[COMMON + '.json'])
    require(manifest.get('version') == 1 and manifest.get('sha256') == sha256(entries[COMMON + '.zip'])
            and 1 <= len(common) <= 5000 and manifest['files'].keys() == common.keys(),
            'Shared Python runtime manifest mismatch')
    for name, entry in common.items():
        require(stat.S_ISREG(entry.mode), 'Shared runtime must contain regular files')
        check_bytes(entry.data, manifest['files'][name], name)
        require(entry.mode & 0o777 == manifest['files'][name]['mode'], 'Shared runtime mode mismatch')
    result = {}
    for abi in ABIS:
        unique = read_zip(entries[f'lib/{abi}/libpython.zip.so'])
        require(not common.keys() & unique.keys(), f'Common runtime shadows {abi} payload')
        result[abi] = common | unique
    return result


def source_receipt(archive, abi, proof):
    receipt = json.loads(archive.read('source-receipt.json'))
    lock = json.loads(archive.read('native-runtime-lock.json'))
    require(lock == proof['lock'], f'Artifact lock differs from core proof: {abi}')
    require(receipt['abi'] == abi and receipt['target'] == HOSTS[abi], f'Wrong receipt ABI: {abi}')
    inputs = receipt['termux_files']
    require(len(inputs) == 27, f'Unexpected source/tool receipt count: {abi}')
    for name, item in inputs.items():
        require(valid_hash(item.get('sha256')) and type(item.get('bytes')) is int and item['bytes'] > 0,
                f'Invalid source/tool input record: {abi}/{name}')
    for package in CORE_COMPONENTS:
        matches = [row for name, row in inputs.items() if name.startswith(package + '/cache/')]
        require(len(matches) == 1 and matches[0]['sha256'] == lock['sources'][package]['sha256'],
                f'Source receipt differs from lock: {abi}/{package}')
        if package == 'python':
            require(receipt['host_python_source'] == matches[0], f'Host Python input differs: {abi}')
    for name, archived in (('python/build/config.log', 'target-python-config.log'),
                           ('python/build/pyconfig.h', 'target-python-pyconfig.h')):
        check_bytes(archive.read(archived), inputs[name], abi + '/' + archived)
    config = archive.read('target-python-config.log').decode(errors='replace')
    require('--host=' + HOSTS[abi] in config and '--with-build-python=python3.12' in config,
            f'Unexpected target configure log: {abi}')
    properties_record = receipt['ndk']['source_properties']
    check_bytes(properties_record['content'].encode(), properties_record, abi + '/ndk-source-properties')
    require('Pkg.Revision = 28.2.13676358' in properties_record['content'], 'Unexpected NDK revision')
    cxx = receipt['ndk']['libcxx']
    require(cxx['installed_path'] == 'lib/libc++_shared.so', 'Unexpected NDK library install path')
    with ZipFile(io.BytesIO(archive.read(f'native-runtime-out/{abi}/libpython-core.zip.so'))) as runtime:
        check_bytes(runtime.read('usr/' + cxx['installed_path']), cxx, abi + '/NDK-libc++')
    require(receipt['installed_ca_bundle']['path'] == 'etc/tls/cert.pem', 'Unexpected CA bundle path')
    ca = receipt['installed_ca_bundle']
    require(valid_hash(ca.get('sha256')) and type(ca.get('bytes')) is int and ca['bytes'] > 0,
            f'Invalid producer CA record: {abi}')
    return receipt, lock


def validate_replacement(entries, original, proof, artifacts):
    require(proof.get('schema') == 'dosos73-rebuilt-runtime-proof-v1', 'Unsupported rebuilt proof schema')
    require(re.fullmatch('[a-f0-9]{40}', proof.get('producer', '')) and proof.get('run_id', 0) > 0,
            'Invalid producer identity')
    require(proof.get('output_payload_sha256') == payload_hash(entries), 'Rebuilt APK payload mismatch')
    paths = {COMMON + '.zip', COMMON + '.json'} | {
        f'lib/{abi}/{name}' for abi in ABIS for name in ('libpython.so', 'libpython.zip.so')}
    require(entries.keys() == original.keys(), 'APK delivery paths changed')
    require(proof.get('replacement_sha256', {}).keys() == paths, 'Rebuilt proof must cover four ABIs')
    for name, data in entries.items():
        if name in paths:
            require(sha256(data) == proof['replacement_sha256'][name], f'Replacement hash mismatch: {name}')
        else:
            require(data == original[name], f'Non-Python APK payload changed: {name}')
    require(set(proof.get('abis', {})) == set(ABIS), 'Rebuilt proof must identify four ABIs')
    old_runtime = runtimes(original)
    deployed = runtimes(entries)
    metadata = read_json(artifacts / 'github-artifacts.json')
    descriptors = metadata['artifacts']
    require(len(descriptors) == 4 and {row['name'] for row in descriptors}
            == {'native-core-candidate-' + abi for abi in ABIS}, 'Four unique native artifacts required')
    descriptors = {row['name'].removeprefix('native-core-candidate-'): row for row in descriptors}
    sources = {}
    locks = []
    for abi in ABIS:
        descriptor = descriptors[abi]
        require(proof['abis'][abi]['artifact_sha256'] == descriptor['digest'], 'Artifact proof digest mismatch')
        path = artifacts / (abi + '.zip')
        core, records, launcher, core_proof = verified_artifact(
            path, abi, descriptor, proof['run_id'], proof['producer'])
        with ZipFile(path) as archive:
            receipt, lock = source_receipt(archive, abi, core_proof)
        retained, selected, superseded = select_runtime(old_runtime[abi], core, records, core_proof['launcher'])
        require(deployed[abi].keys() == (retained | selected).keys(), f'Rebuilt runtime paths differ: {abi}')
        require(proof['abis'][abi]['superseded'] == superseded, f'Superseded file list differs: {abi}')
        require(entries[f'lib/{abi}/libpython.so'] == launcher, f'Rebuilt launcher differs: {abi}')
        for name, entry in retained.items():
            require(deployed[abi][name] == entry, f'Inherited runtime file changed: {abi}/{name}')
        changed = set()
        strips = proof['abis'][abi]['stripped']
        for name, entry in selected.items():
            final = deployed[abi][name]
            require(final.mode == entry.mode, f'Runtime mode changed: {abi}/{name}')
            if final.data != entry.data:
                require(entry.data.startswith(b'\x7fELF') and final.data.startswith(b'\x7fELF')
                        and name in strips, f'Unproven runtime transformation: {abi}/{name}')
                descriptor = strips[name]
                check_bytes(entry.data, {'sha256': descriptor['source_sha256'], 'bytes': descriptor['source_bytes']}, name)
                check_bytes(final.data, descriptor, name)
                changed.add(name)
        require(changed == strips.keys(), f'Unexpected stripped file evidence: {abi}')
        require('usr/etc/tls/cert.pem' in retained and retained['usr/etc/tls/cert.pem'].data,
                f'The selected inherited trust store is missing: {abi}')
        sources[abi] = receipt
        locks.append(lock)
    require(all(lock == locks[0] for lock in locks), 'Source locks differ across ABIs')
    for abi, receipt in sources.items():
        first = sources[ABIS[0]]
        require(receipt['installed_ca_bundle'] == first['installed_ca_bundle'],
                'Producer CA receipts differ across ABIs')
        for name, record in receipt['termux_files'].items():
            if '/cache/' in name or name.startswith('_cache/'):
                require(first['termux_files'].get(name) == record, f'Source input differs across ABIs: {name}')
    return deployed, sources, locks[0]


def native_components(lock, receipts, original_components):
    components = []
    for key, (name, license_id) in CORE_COMPONENTS.items():
        source = lock['sources'][key]
        version = source['version'].split(':')[-1]
        item = {'type': 'library', 'bom-ref': 'native-source:' + key + ':' + version,
                'name': name, 'version': version,
                'externalReferences': [source_reference(source['url'], source['sha256'])],
                'properties': properties(correspondence='Verified producer source receipt and selected APK files; ELF symbols may be stripped by the local assembler.',
                                         scope='Component source identity. Actual deployed ELF hashes are listed separately.')}
        item['licenses'] = ([{'expression': license_id}] if license_id else [
            {'license': {'name': 'Public domain', 'url': 'https://sqlite.org/copyright.html'}}])
        components.append(item)
    for ref, package in (('readline:8.3.6', 'readline'), ('gdbm:1.26', 'gdbm')):
        base, = [row for row in original_components if row['bom-ref'] == ref]
        item = copy.deepcopy(base)
        references = item.get('externalReferences', [])
        receipt_hashes = {record['sha256'] for name, record in receipts[ABIS[0]]['termux_files'].items()
                          if name.startswith(package + '/cache/')}
        require(any(row.get('type') == 'distribution' and any(value.get('content') in receipt_hashes
                    for value in row.get('hashes', [])) for row in references), f'Unmatched GNU source reference: {package}')
        item['properties'] = properties(correspondence='Rebuilt from receipt-verified source inputs for four ABIs; local symbol stripping is recorded in integration proof.',
                                         scope='Source license identity from baseline, matched to current source receipt. Old official-DEB byte correspondence does not apply.')
        components.append(item)
    return components


def generate(apk, baseline_apk, baseline_bom, baseline_engine_proof, integration_proof, artifacts, version):
    bom = read_json(baseline_bom)
    original = validate_baseline(baseline_apk, bom, read_json(baseline_engine_proof))
    proof = read_json(integration_proof)
    require(proof.get('input_apk_sha256') == digest(baseline_apk), 'Integration proof baseline hash differs')
    entries = apk_entries(apk)
    deployed, receipts, lock = validate_replacement(entries, original, proof, artifacts)
    result = copy.deepcopy(bom)
    inherited = [row for row in result['components'] if row.get('type') != 'file'
                 and row['bom-ref'] not in ('readline:8.3.6', 'gdbm:1.26')]
    for row in inherited:
        row.setdefault('properties', []).extend(properties(identity_scope='Inherited from the baseline SBOM bound to the byte-identical APK payload or retained vendor package.'))
    for name, record in elf_inventory(entries).items():
        inherited.append({'type': 'file', 'bom-ref': 'file:' + name, 'name': name,
                          'hashes': [{'alg': 'SHA-256', 'content': record['sha256']}],
                          'properties': properties(uncompressed_bytes=record['bytes'],
                                                   hash_scope='Actual deployed uncompressed ELF bytes.')})
    inherited += native_components(lock, receipts, bom['components'])
    refs = [row['bom-ref'] for row in inherited]
    require(len(refs) == len(set(refs)), 'Duplicate rebuilt component references')
    result['components'] = inherited
    apk_sha = digest(apk)
    result['serialNumber'] = 'urn:uuid:' + str(uuid.uuid5(uuid.NAMESPACE_URL, apk_sha))
    result['metadata']['timestamp'] = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
    result['metadata']['component'].update({'bom-ref': 'dosos73:' + version, 'version': version,
                                         'hashes': [{'alg': 'SHA-256', 'content': apk_sha}]})
    result['metadata']['properties'] = properties(
        scope='Flat inventory of inherited checked build inputs, selected receipt-backed native sources and actual deployed ELF files.',
        baseline_apk_sha256=digest(baseline_apk), baseline_sbom_sha256=digest(baseline_bom),
        baseline_engine_proof_sha256=digest(baseline_engine_proof), integration_proof_sha256=digest(integration_proof),
        checked_output_payload_sha256=payload_hash(entries), native_producer=proof['producer'], native_run_id=proof['run_id'],
        proof_limit='Source receipts and local stripping records are not an independent signed attestation or proof of bit-for-bit rebuild reproducibility.',
        remaining_producer_scope='Inherited Cryptodome and QuickJS original Android build pins are outside the rebuilt-core receipts.',
        license_scope='Selected component identities and license references. Complete corresponding source/notices delivery and legal review remain separate.')
    for abi in ABIS:
        result['metadata']['properties'] += properties(**{abi + '_source_receipt_sha256': sha256(
            json.dumps(receipts[abi], sort_keys=True, separators=(',', ':')).encode())})
        result['metadata']['properties'] += properties(**{
            abi + '_selected_ca_bundle_sha256': sha256(deployed[abi]['usr/etc/tls/cert.pem'].data),
            abi + '_producer_build_ca_bundle_sha256': receipts[abi]['installed_ca_bundle']['sha256']})
    result['metadata']['properties'] += properties(
        selected_trust_store='Preserved byte-for-byte from the baseline APK. Producer-build CA inputs are recorded separately and are not substituted into the selected APK.')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'baseline-apk', 'baseline-bom', 'baseline-engine-proof', 'integration-proof', 'artifacts', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    result = generate(args.apk, args.baseline_apk, args.baseline_bom, args.baseline_engine_proof,
                      args.integration_proof, args.artifacts, args.version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'components': len(result['components']), 'apk_sha256': digest(args.apk)}))


if __name__ == '__main__':
    main()
