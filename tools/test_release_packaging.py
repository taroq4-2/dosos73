"""Synthetic tamper tests for the APK/AAB payload boundary; no Android dependency."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from assemble_compact_bundle import assemble
from trim_universal_apk import ABIS
from verify_bundle import validate_identity, validate_runtime_payload, validate_signature_output


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
        self.files.update({f'lib/{abi}/libffmpeg.so': b'engine-' + abi.encode() for abi in ABIS})
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

    def test_verifier_accepts_exact_runtime_payload(self):
        assemble(self.bundle, self.original, self.compact, self.output)
        with zipfile.ZipFile(self.output) as bundle, zipfile.ZipFile(self.compact) as apk:
            validate_runtime_payload(bundle, apk)

    def test_verifier_rejects_missing_executable_with_all_abis_still_present(self):
        assemble(self.bundle, self.original, self.compact, self.output)
        with zipfile.ZipFile(self.output) as bundle:
            partial = {n: bundle.read(n) for n in bundle.namelist()
                       if n != 'base/lib/x86_64/libffmpeg.so'}
        self.write(self.output, partial)
        with zipfile.ZipFile(self.output) as bundle, zipfile.ZipFile(self.compact) as apk:
            self.assertEqual(set(ABIS), {n.split('/')[2] for n in bundle.namelist() if n.startswith('base/lib/')})
            with self.assertRaisesRegex(ValueError, 'Native delivery paths differ'):
                validate_runtime_payload(bundle, apk)

    def test_verifier_rejects_unexpected_native_payload(self):
        assemble(self.bundle, self.original, self.compact, self.output)
        with zipfile.ZipFile(self.output, 'a') as bundle:
            bundle.writestr('base/lib/x86_64/libunexpected.so', b'extra')
        with zipfile.ZipFile(self.output) as bundle, zipfile.ZipFile(self.compact) as apk:
            with self.assertRaisesRegex(ValueError, 'Native delivery paths differ'):
                validate_runtime_payload(bundle, apk)

    def test_verifier_rejects_altered_runtime_payload(self):
        assemble(self.bundle, self.original, self.compact, self.output)
        with zipfile.ZipFile(self.output) as bundle:
            changed = {n: bundle.read(n) for n in bundle.namelist()}
        changed['base/assets/dosos73/python-common.zip'] = b'altered'
        self.write(self.output, changed)
        with zipfile.ZipFile(self.output) as bundle, zipfile.ZipFile(self.compact) as apk:
            with self.assertRaisesRegex(ValueError, 'Shared runtime payload differs'):
                validate_runtime_payload(bundle, apk)


class BundleVerificationTest(unittest.TestCase):
    MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.os73.dosos73" android:versionCode="15" android:versionName="1.3.0-rc.2">
        <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/></manifest>'''

    def test_version_name_is_verified_with_other_identity_fields(self):
        self.assertEqual('1.3.0-rc.2', validate_identity(self.MANIFEST, 15, '1.3.0-rc.2')['versionName'])
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            validate_identity(self.MANIFEST, 15, '1.3.0-rc.3')
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            validate_identity(self.MANIFEST.replace('com.os73.dosos73', 'com.other.app'), 15, '1.3.0-rc.2')

    def test_signature_allows_self_signed_upload_certificate_without_claiming_ca_trust(self):
        validate_signature_output('jar verified.\nWarning:\nThis jar contains entries whose certificate chain is invalid.\n'
                                  'This jar contains entries whose signer certificate is self-signed.\n')

    def test_partially_signed_jar_is_rejected_even_when_jarsigner_says_verified(self):
        # Reproduced using a real JDK signed fixture with an appended entry:
        # jarsigner -verify exits zero and emits both lines below.
        with self.assertRaisesRegex(ValueError, 'fully verified'):
            validate_signature_output('jar verified.\nWarning:\nThis jar contains unsigned entries which have not been integrity-checked.\n')

    def test_unsigned_or_disabled_signature_is_rejected(self):
        for output in ('jar is unsigned.', 'jar verified.\nThe jar will be treated as unsigned, because it is signed with a weak algorithm.'):
            with self.assertRaisesRegex(ValueError, 'fully verified'):
                validate_signature_output(output)


if __name__ == '__main__':
    unittest.main()
