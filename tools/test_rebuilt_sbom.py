"""Tamper controls for rebuilt runtime source and deployed-byte correspondence."""

import json
from pathlib import Path
import stat
import tempfile
import unittest
from zipfile import ZipFile

from assemble_rebuilt_runtime import ABIS, COMMON, Entry, encode_zip, split_runtime
from generate_rebuilt_sbom import (HOSTS, apk_entries, elf_inventory, generate,
                                   payload_hash, sha256)


class RebuiltSbomTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.baseline = self.root / 'baseline.apk'
        self.final = self.root / 'final.apk'
        self.baseline_bom = self.root / 'baseline.json'
        self.baseline_proof = self.root / 'baseline-proof.json'
        self.proof_path = self.root / 'integration.json'
        self.producer = 'a' * 40
        self.run = 123
        self.native = b'\x7fELFraw-native'
        self.stripped = b'\x7fELFstripped-native'
        regular = stat.S_IFREG | 0o644
        executable = stat.S_IFREG | 0o755
        self.vendor = 'usr/lib/python3.12/site-packages/vendor/__init__.py'
        self.old = {'usr/etc/tls/cert.pem': Entry(b'CA', regular),
                    'usr/lib/python3.12/os.py': Entry(b'old python', regular),
                    'usr/lib/libc++_shared.so': Entry(b'\x7fELFold', executable),
                    self.vendor: Entry(b'inherited vendor', regular)}
        self.core = {'usr/lib/python3.12/os.py': Entry(b'new python', regular),
                     'usr/lib/libc++_shared.so': Entry(self.native, executable)}
        self.new = {**self.old, **self.core,
                    'usr/lib/libc++_shared.so': Entry(self.stripped, executable)}
        self.immutable = {'classes.dex': b'app-code', 'assets/quickjs': b'launcher'}
        self.launcher = b'\x7fELFnew-launcher'
        old_entries = self.apk_runtime({abi: self.old for abi in ABIS}, b'\x7fELFold-launcher')
        self.write_zip(self.baseline, old_entries)
        self.final_entries = self.apk_runtime({abi: self.new for abi in ABIS}, self.launcher)
        self.write_zip(self.final, self.final_entries)
        native_sources = {name: {'version': version, 'url': 'https://example.test/' + name,
                                  'sha256': sha256(name.encode())}
                          for name, version in (('python', '3.12.16'), ('openssl', '1:3.5.9'),
                                                ('libexpat', '2.9.0'), ('liblzma', '5.8.4'),
                                                ('libsqlite', '3.54.1'))}
        self.lock = {'sources': native_sources, 'ndk_archive_sha256': sha256(b'NDK')}
        self.metadata = {'artifacts': []}
        self.proof = {'schema': 'dosos73-rebuilt-runtime-proof-v1', 'producer': self.producer,
                      'run_id': self.run, 'input_apk_sha256': sha256(self.baseline.read_bytes()),
                      'abis': {}}
        for abi in ABIS:
            self.create_artifact(abi)
        self.save_proof()
        components = [{'type': 'file', 'bom-ref': 'file:' + name, 'name': name,
                       'hashes': [{'alg': 'SHA-256', 'content': record['sha256']}]}
                      for name, record in elf_inventory(old_entries).items()]
        for name, version in (('readline', '8.3.6'), ('gdbm', '1.26')):
            components.append({'type': 'library', 'bom-ref': name + ':' + version,
                               'name': name, 'version': version,
                               'licenses': [{'expression': 'GPL-3.0-or-later'}],
                               'externalReferences': [{'type': 'distribution', 'url': 'https://example.test/' + name,
                                   'hashes': [{'alg': 'SHA-256', 'content': sha256(name.encode())}]}]})
        self.save_json(self.baseline_bom, {'bomFormat': 'CycloneDX', 'specVersion': '1.6',
            'metadata': {'component': {'hashes': [{'alg': 'SHA-256', 'content': sha256(self.baseline.read_bytes())}]}},
            'components': components})
        self.save_json(self.baseline_proof, {'schema': 'dosos73-runtime-proof-v1',
                                             'output_payload_sha256': payload_hash(old_entries)})

    def save_json(self, path, value):
        path.write_text(json.dumps(value), encoding='utf-8')

    def write_zip(self, path, entries):
        with ZipFile(path, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def apk_runtime(self, runtimes, launcher):
        common, manifest, unique = split_runtime(runtimes)
        entries = {**self.immutable, COMMON + '.zip': common, COMMON + '.json': manifest}
        for abi in ABIS:
            entries[f'lib/{abi}/libpython.so'] = launcher
            entries[f'lib/{abi}/libpython.zip.so'] = unique[abi]
            entries[f'lib/{abi}/libffmpeg.so'] = b'\x7fELFffmpeg'
        return entries

    def create_artifact(self, abi):
        core_zip = encode_zip(self.core)
        prefix = f'native-runtime-out/{abi}/'
        records = []
        for name, entry in self.core.items():
            row = {'path': name, 'bytes': len(entry.data), 'sha256': sha256(entry.data)}
            if entry.data.startswith(b'\x7fELF'):
                row['elf'] = {'needed': []}
            records.append(row)
        proof = {'abi': abi, 'archive_sha256': sha256(core_zip), 'unresolved_needed': [],
                 'launcher': {'bytes': len(self.launcher), 'sha256': sha256(self.launcher), 'needed': []},
                 'payload': records, 'lock': self.lock}
        proof_bytes = json.dumps(proof).encode()
        config = ('--host=' + HOSTS[abi] + ' --with-build-python=python3.12').encode()
        header = b'target-header'
        inputs = {name + '/cache/source': {'sha256': source['sha256'], 'bytes': 1}
                  for name, source in self.lock['sources'].items()}
        for name in ('readline', 'gdbm'):
            inputs[name + '/cache/source'] = {'sha256': sha256(name.encode()), 'bytes': 1}
        inputs['ca-certificates/cache/keyutil.jar'] = {'sha256': sha256(b'keyutil'), 'bytes': len(b'keyutil')}
        for number in range(17):
            inputs[f'other{number}/cache/source'] = {'sha256': sha256(str(number).encode()), 'bytes': 1}
        inputs['python/build/config.log'] = {'bytes': len(config), 'sha256': sha256(config)}
        inputs['python/build/pyconfig.h'] = {'bytes': len(header), 'sha256': sha256(header)}
        properties = 'Pkg.Revision = 28.2.13676358\n'
        receipt = {'abi': abi, 'target': HOSTS[abi], 'termux_files': inputs,
                   'core_archive_sha256': proof['archive_sha256'],
                   'core_proof': {'bytes': len(proof_bytes), 'sha256': sha256(proof_bytes)},
                   'host_python_source': {'bytes': 1, 'sha256': self.lock['sources']['python']['sha256']},
                   'installed_ca_bundle': {'path': 'etc/tls/cert.pem', 'bytes': len(b'producer CA'),
                                           'sha256': sha256(b'producer CA')},
                   'ndk': {'archive_sha256': self.lock['ndk_archive_sha256'],
                           'source_properties': {'content': properties, 'bytes': len(properties),
                                                 'sha256': sha256(properties.encode())},
                           'libcxx': {'installed_path': 'lib/libc++_shared.so', 'bytes': len(self.native),
                                      'sha256': sha256(self.native)}}}
        archive = {prefix + 'libpython-core.zip.so': core_zip, prefix + 'libpython.so': self.launcher,
                   prefix + 'native-build-proof.json': proof_bytes,
                   'native-runtime-lock.json': json.dumps(self.lock).encode(),
                   'source-receipt.json': json.dumps(receipt).encode(),
                   'ndk-archive-sha256.txt': (self.lock['ndk_archive_sha256'] + '  ndk.zip').encode(),
                   'target-python-config.log': config, 'target-python-pyconfig.h': header}
        self.write_zip(self.root / (abi + '.zip'), archive)
        descriptor = {'name': 'native-core-candidate-' + abi,
                      'workflow_run': {'id': self.run, 'head_sha': self.producer},
                      'digest': 'sha256:' + sha256((self.root / (abi + '.zip')).read_bytes())}
        self.metadata['artifacts'].append(descriptor)
        self.proof['abis'][abi] = {'artifact_sha256': descriptor['digest'], 'superseded': [],
            'stripped': {'usr/lib/libc++_shared.so': {'source_sha256': sha256(self.native),
                'source_bytes': len(self.native), 'sha256': sha256(self.stripped), 'bytes': len(self.stripped)}}}

    def save_proof(self):
        self.proof['output_payload_sha256'] = payload_hash(self.final_entries)
        self.proof['replacement_sha256'] = {name: sha256(data) for name, data in self.final_entries.items()
            if name in (COMMON + '.zip', COMMON + '.json') or name.endswith(('libpython.so', 'libpython.zip.so'))}
        self.save_json(self.proof_path, self.proof)
        self.save_json(self.root / 'github-artifacts.json', self.metadata)

    def generate(self):
        return generate(self.final, self.baseline, self.baseline_bom, self.baseline_proof,
                        self.proof_path, self.root, 'test-version')

    def replace_receipt(self, abi, mutate):
        path = self.root / (abi + '.zip')
        with ZipFile(path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        receipt = json.loads(entries['source-receipt.json'])
        mutate(receipt)
        entries['source-receipt.json'] = json.dumps(receipt).encode()
        self.write_zip(path, entries)
        descriptor = next(row for row in self.metadata['artifacts'] if row['name'].endswith(abi))
        descriptor['digest'] = 'sha256:' + sha256(path.read_bytes())
        self.proof['abis'][abi]['artifact_sha256'] = descriptor['digest']
        self.save_proof()

    def test_valid_source_bound_inventory_replaces_deb_claims(self):
        result = self.generate()
        self.assertEqual('test-version', result['metadata']['component']['version'])
        libraries = {row['name']: row for row in result['components'] if row['type'] == 'library'}
        self.assertEqual('5.8.4', libraries['liblzma']['version'])
        self.assertIn('Old official-DEB', libraries['readline']['properties'][1]['value'])
        files = {row['name']: row for row in result['components'] if row['type'] == 'file'}
        self.assertTrue(any(row['hashes'][0]['content'] == sha256(self.stripped) for row in files.values()))
        metadata = {row['name']: row['value'] for row in result['metadata']['properties']}
        self.assertEqual(sha256(b'CA'), metadata['dosos73:arm64-v8a-selected-ca-bundle-sha256'])
        self.assertEqual(sha256(b'producer CA'), metadata['dosos73:arm64-v8a-producer-build-ca-bundle-sha256'])

    def test_rejects_changed_app_even_with_refreshed_payload_proof(self):
        self.final_entries['classes.dex'] = b'changed-app'
        self.write_zip(self.final, self.final_entries)
        self.save_proof()
        with self.assertRaisesRegex(ValueError, 'Non-Python'):
            self.generate()

    def test_rejects_missing_abi_evidence(self):
        del self.proof['abis'][ABIS[0]]
        self.save_proof()
        with self.assertRaisesRegex(ValueError, 'four ABIs'):
            self.generate()

    def test_rejects_changed_host_source_with_resealed_artifact_digest(self):
        self.replace_receipt(ABIS[0], lambda row: row['host_python_source'].update(sha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'source receipt mismatch'):
            self.generate()

    def test_rejects_changed_ndk_member_with_resealed_artifact_digest(self):
        self.replace_receipt(ABIS[0], lambda row: row['ndk']['libcxx'].update(sha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.generate()

    def test_rejects_unproven_native_transformation(self):
        self.proof['abis'][ABIS[0]]['stripped']['usr/lib/libc++_shared.so']['source_sha256'] = '0' * 64
        self.save_proof()
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.generate()

    def test_rejects_changed_retained_vendor_with_refreshed_proof(self):
        changed = dict(self.new)
        changed[self.vendor] = Entry(b'tampered vendor', changed[self.vendor].mode)
        self.final_entries = self.apk_runtime({abi: changed for abi in ABIS}, self.launcher)
        self.write_zip(self.final, self.final_entries)
        self.save_proof()
        with self.assertRaisesRegex(ValueError, 'Inherited runtime file changed'):
            self.generate()


if __name__ == '__main__':
    unittest.main()
