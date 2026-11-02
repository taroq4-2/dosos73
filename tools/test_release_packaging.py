"""Synthetic tamper tests for the APK/AAB payload boundary; no Android dependency."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from assemble_compact_bundle import assemble
from trim_universal_apk import ABIS


class BundlePayloadTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.original = self.root / 'original.apk'
        self.compact = self.root / 'compact.apk'
        self.bundle = self.root / 'original.aab'
        self.output = self.root / 'output.aab'
        self.files = {'classes.dex': b'dex-source', 'assets/helper.py': b'helper-source',
                      'assets/dexopt/baseline.prof': b'baseline',
                      'AndroidManifest.xml': b'binary-manifest', 'resources.arsc': b'binary-resources'}
        self.files.update({f'lib/{abi}/libpython.zip.so': b'original-' + abi.encode() for abi in ABIS})
        self.new = dict(self.files)
        self.new.update({f'lib/{abi}/libpython.zip.so': b'compact-' + abi.encode() for abi in ABIS})
        self.new.update({'assets/dosos73/python-common.zip': b'common', 'assets/dosos73/python-common.json': b'{}'})
        self.base = {'base/dex/classes.dex': b'dex-source', 'base/assets/helper.py': b'helper-source',
                     'base/manifest/AndroidManifest.xml': b'proto-manifest', 'base/resources.pb': b'proto-resources',
                     'BundleConfig.pb': b'configuration', 'BUNDLE-METADATA/test': b'mapping',
                     'BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof': b'baseline'}
        self.base.update({'base/' + k: v for k, v in self.files.items() if k.startswith('lib/')})
        self.write_inputs()

    def write(self, path, files):
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in files.items():
                z.writestr(name, data)

    def write_inputs(self):
        self.write(self.original, self.files)
        self.write(self.compact, self.new)
        self.write(self.bundle, self.base)

    def test_only_runtime_changes_proto_and_mapping_are_preserved(self):
        assemble(self.bundle, self.original, self.compact, self.output)
        with zipfile.ZipFile(self.output) as z:
            for name, data in self.base.items():
                self.assertEqual(self.new[name[5:]] if name.startswith('base/lib/') else data, z.read(name))
            self.assertEqual(b'common', z.read('base/assets/dosos73/python-common.zip'))
        self.assertFalse(json.loads(self.output.with_suffix('.bundle-proof.json').read_text())['signed'])
        with self.assertRaises(ValueError):
            assemble(self.bundle, self.original, self.compact, self.output)

    def test_rejects_compact_code_tampering(self):
        self.new['classes.dex'] = b'tampered'
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, 'Non-runtime'):
            assemble(self.bundle, self.original, self.compact, self.output)

    def test_rejects_mismatched_application_build(self):
        self.base['base/dex/classes.dex'] = b'different-build'
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, 'source mismatch'):
            assemble(self.bundle, self.original, self.compact, self.output)

    def test_rejects_missing_abi_or_added_native_path(self):
        del self.new[f'lib/{ABIS[0]}/libpython.zip.so']
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, 'Native delivery'):
            assemble(self.bundle, self.original, self.compact, self.output)


if __name__ == '__main__':
    unittest.main()
