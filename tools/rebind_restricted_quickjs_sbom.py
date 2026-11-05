"""Bind a checked CycloneDX inventory to the APK with restricted QuickJS."""

import argparse
import datetime
import json
from pathlib import Path
import uuid

from assemble_restricted_quickjs import ABIS, SOURCE, VERSION
from assemble_rebuilt_runtime import COMMON, SITE
from assemble_source_crypto import PREFIX, SOURCE_SHA as CRYPTO_SHA, SOURCE_URL as CRYPTO_URL
from assemble_source_crypto import VERSION as CRYPTO_VERSION, differences
from generate_rebuilt_sbom import apk_entries, elf_inventory, hash_value, payload_hash, runtimes
from generate_sbom import digest, properties, read_json, require, sha256, source_reference, valid_hash


def check_crypto(before, after, original_apk, quickjs, crypto):
    paths = {COMMON + '.json', COMMON + '.zip'} | {f'lib/{abi}/libpython.zip.so' for abi in ABIS}
    require(crypto['schema'] == 'dosos73-source-crypto-v1' and crypto['version'] == CRYPTO_VERSION
            and crypto['source_sha256'] == CRYPTO_SHA and valid_hash(crypto['receipt_sha256'])
            and crypto['receipt_baseline_sha256'] == digest(original_apk)
            and crypto['input_apk_sha256'] == quickjs['output_unsigned_apk_sha256']
            and crypto['input_payload_sha256'] == quickjs['output_payload_sha256']
            and paths == set(crypto['outer_paths']) and set(crypto['changes']) == set(ABIS),
            'Crypto integration proof is incomplete')
    restored = {name: before[name] if name in paths else data for name, data in after.items()}
    require(payload_hash(restored) == quickjs['output_payload_sha256'],
            'QuickJS intermediate payload is not reproduced by reversing the Crypto overlay')
    old_runtime, final_runtime = runtimes(before), runtimes(after)
    changed_elf = set()
    for abi in ABIS:
        expected = {path for path in old_runtime[abi] if path.startswith(PREFIX)
                    and path.endswith(('.py', '.abi3.so'))}
        actual = differences(old_runtime[abi], final_runtime[abi], expected, abi)
        require(crypto['changes'][abi] == actual and all(path in actual for path in expected
                if path.endswith('.abi3.so')), f'Crypto proof disagrees with selected runtime: {abi}')
        require(crypto['modules'][abi] == {'python_files': sum(path.endswith('.py') for path in expected),
                'extensions': sum(path.endswith('.abi3.so') for path in expected)},
                f'Crypto module counts are wrong: {abi}')
        changed_elf.update(f'lib/{abi}/libpython.zip.so!/{name}' for name in actual if name.endswith('.abi3.so'))
    return paths, changed_elf


def rebind(original_apk, original_sbom, final_apk, quickjs_proof, crypto_proof=None):
    bom = read_json(original_sbom)
    proof = read_json(quickjs_proof)
    require(bom.get('bomFormat') == 'CycloneDX' and bom.get('specVersion') == '1.6'
            and hash_value(bom['metadata']['component']) == digest(original_apk),
            'Existing SBOM does not describe the original APK')
    require(proof['schema'] == 'dosos73-restricted-quickjs-v1' and proof['source'] == SOURCE
            and valid_hash(proof['patch_sha256']) and proof['input_apk_sha256'] == digest(original_apk)
            and set(proof['abis']) == set(ABIS), 'QuickJS integration proof is incomplete')
    before, after = apk_entries(original_apk), apk_entries(final_apk)
    crypto = read_json(crypto_proof) if crypto_proof else None
    output_payload = crypto['output_payload_sha256'] if crypto else proof['output_payload_sha256']
    require(before.keys() == after.keys() and payload_hash(after) == output_payload,
            'QuickJS delivery payload changed since assembly')
    expected = {f'lib/{abi}/libqjs.so' for abi in ABIS}
    outer_paths, changed_nested = check_crypto(before, after, original_apk, proof, crypto) if crypto else (set(), set())
    require({path for path in before if before[path] != after[path]} == expected | outer_paths,
            'Unexpected top-level APK payload changed')
    for abi in ABIS:
        path = f'lib/{abi}/libqjs.so'
        row = proof['abis'][abi]
        require(row['path'] == path and row['previous_sha256'] == sha256(before[path])
                and row['sha256'] == sha256(after[path]) and row['previous_bytes'] == len(before[path])
                and row['bytes'] == len(after[path]) and valid_hash(row['receipt_sha256']),
                f'QuickJS proof has inconsistent ELF evidence: {abi}')
    original_elf = elf_inventory(before)
    components = bom['components']
    files = {row['bom-ref'].removeprefix('file:'): row for row in components if row['type'] == 'file'}
    require(len({row['bom-ref'] for row in components}) == len(components)
            and {path: hash_value(row) for path, row in files.items()}
            == {path: row['sha256'] for path, row in original_elf.items()}, 'Original ELF inventory mismatch')
    final_elf = elf_inventory(after)
    changed_files = expected | changed_nested
    require(original_elf.keys() == final_elf.keys() and changed_files ==
            {path for path in original_elf if original_elf[path] != final_elf[path]}, 'Unexpected change in ELF inventory')
    for path in sorted(changed_files):
        files[path]['hashes'] = [{'alg': 'SHA-256', 'content': final_elf[path]['sha256']}]
        files[path]['properties'] = properties(uncompressed_bytes=final_elf[path]['bytes'],
                                               hash_scope='Actual deployed uncompressed ELF bytes.')
    quickjs_ref = f'native-source:quickjs:{VERSION}'
    require(all(row['bom-ref'] != quickjs_ref for row in components), 'Duplicate QuickJS source identity')
    components.append({'type': 'library', 'bom-ref': quickjs_ref, 'name': 'QuickJS', 'version': VERSION,
                       'licenses': [{'expression': 'MIT'}],
                       'externalReferences': [source_reference(SOURCE['url'], SOURCE['sha256'])],
                       'properties': properties(restricted_cli_patch_sha256=proof['patch_sha256'],
                                                scope='Source identity and private DOSOS73 CLI patch. Deployed ELF hashes are listed separately.')})
    if crypto:
        crypto_ref = f'native-source:pycryptodomex:{CRYPTO_VERSION}'
        require(all(row['bom-ref'] != crypto_ref for row in components), 'Duplicate Cryptodome source identity')
        components.append({'type': 'library', 'bom-ref': crypto_ref, 'name': 'PyCryptodomex', 'version': CRYPTO_VERSION,
                           'licenses': [{'license': {'name': 'BSD-2-Clause and public domain (see upstream notices)'}}],
                           'externalReferences': [source_reference(CRYPTO_URL, CRYPTO_SHA)],
                           'properties': properties(build_receipt_sha256=crypto['receipt_sha256'],
                                                    scope='Official source distribution; selected Python and Android extension paths verified against the input APK.')})
    final_hash = digest(final_apk)
    bom['serialNumber'] = 'urn:uuid:' + str(uuid.uuid5(uuid.NAMESPACE_URL, final_hash))
    metadata = bom['metadata']
    metadata['timestamp'] = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
    metadata['component']['hashes'] = [{'alg': 'SHA-256', 'content': final_hash}]
    # Retain the previous core and licensing evidence while replacing properties
    # whose values otherwise describe the old APK or inherited QuickJS executable.
    stale = {'checked-output-payload-sha256', 'remaining-producer-scope', 'scope'}
    metadata['properties'] = [row for row in metadata['properties']
                              if row['name'].removeprefix('dosos73:') not in stale]
    metadata['properties'] += properties(
        scope='Flat inventory of checked original inputs, rebuilt component sources, and deployed ELF files.',
        checked_output_payload_sha256=output_payload,
        quickjs_integration_proof_sha256=digest(quickjs_proof), prior_apk_sbom_sha256=digest(original_sbom))
    metadata['properties'] += (properties(crypto_integration_proof_sha256=digest(crypto_proof)) if crypto else properties(
        remaining_producer_scope='Inherited Cryptodome Android build recipe remains outside the source receipts.'))
    return bom


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('original-apk', 'original-sbom', 'final-apk', 'quickjs-proof', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--crypto-proof', type=Path)
    args = parser.parse_args()
    result = rebind(args.original_apk, args.original_sbom, args.final_apk, args.quickjs_proof, args.crypto_proof)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(result, output, indent=2, ensure_ascii=False)
        output.write('\n')
