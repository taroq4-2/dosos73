"""Single-ABI packaging keeps the full-bundle verification; no Android dependency."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from assemble_universal import ABIS, assemble

LIBS = ('libpython.zip.so', 'libffmpeg.so')
ASSETS = {'assets/dosos73/python-common.zip': b'common', 'assets/dosos73/python-common.json': b'{}'}


class SingleAbiAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.members = {f'lib/{abi}/{lib}': f'runtime-{abi}-{lib}'.encode() for abi in ABIS for lib in LIBS}
        self.members.update(ASSETS)
        self.bundle = self.root / 'runtime.zip'
        self.write(self.bundle, self.members)
        self.lock = self.root / 'lock.json'
        self.write_lock(self.members, self.bundle)

    def write(self, path, files):
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in files.items():
                z.writestr(name, data)

    def write_lock(self, members, bundle):
        self.lock.write_text(json.dumps({
            'runtime_sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(),
            'members': {n: hashlib.sha256(d).hexdigest() for n, d in members.items()},
        }))

    def app(self, abis):
        path = self.root / ('app-' + '-'.join(abis) + '.apk')
        files = {'classes.dex': b'dex', 'AndroidManifest.xml': b'manifest', 'META-INF/CERT.SF': b'old-signature'}
        files.update({f'lib/{abi}/{lib}': b'gradle-' + abi.encode() for abi in abis for lib in LIBS})
        self.write(path, files)
        return path

    def test_arm64_package_contains_only_arm64_runtime_and_shared_assets(self):
        out = self.root / 'arm64.apk'
        assemble(self.app(['arm64-v8a']), self.bundle, self.lock, out, 'arm64-v8a')
        with zipfile.ZipFile(out) as z:
            names = set(z.namelist())
            self.assertEqual({n for n in names if n.startswith('lib/')}, {f'lib/arm64-v8a/{lib}' for lib in LIBS})
            self.assertTrue(set(ASSETS) <= names)
            # Runtime bytes come from the verified bundle, never from Gradle; old signatures dropped.
            self.assertEqual(z.read('lib/arm64-v8a/libffmpeg.so'), b'runtime-arm64-v8a-libffmpeg.so')
            self.assertNotIn('META-INF/CERT.SF', names)
        self.assertEqual('arm64-v8a', json.loads(out.with_suffix('.json').read_text())['abi'])

    def test_universal_package_is_unchanged_by_the_new_option(self):
        out = self.root / 'universal.apk'
        assemble(self.app(list(ABIS)), self.bundle, self.lock, out)
        with zipfile.ZipFile(out) as z:
            self.assertEqual(len([n for n in z.namelist() if n.startswith('lib/')]), len(ABIS) * len(LIBS))

    def test_tampered_member_of_another_abi_still_fails(self):
        tampered = dict(self.members)
        tampered['lib/x86/libffmpeg.so'] = b'evil'
        self.write(self.bundle, tampered)
        # Bundle hash no longer matches the lock: refused even though x86 is not packaged.
        with self.assertRaises(AssertionError):
            assemble(self.app(['arm64-v8a']), self.bundle, self.lock, self.root / 'x.apk', 'arm64-v8a')

    def test_app_built_for_all_abis_cannot_be_packaged_as_single_abi(self):
        with self.assertRaises(AssertionError):
            assemble(self.app(list(ABIS)), self.bundle, self.lock, self.root / 'y.apk', 'arm64-v8a')

    def test_three_abi_package_leaves_out_only_x86(self):
        kept = ['armeabi-v7a', 'arm64-v8a', 'x86_64']
        out = self.root / 'three.apk'
        assemble(self.app(kept), self.bundle, self.lock, out, kept)
        with zipfile.ZipFile(out) as z:
            libs = {n for n in z.namelist() if n.startswith('lib/')}
            self.assertEqual(libs, {f'lib/{abi}/{lib}' for abi in kept for lib in LIBS})
            self.assertTrue(set(ASSETS) <= set(z.namelist()))
        self.assertEqual('armeabi-v7a+arm64-v8a+x86_64', json.loads(out.with_suffix('.json').read_text())['abi'])

    def test_app_and_package_abis_must_match(self):
        # The app still carries x86 libraries: refused rather than shipping a mixed layout.
        with self.assertRaises(AssertionError):
            assemble(self.app(list(ABIS)), self.bundle, self.lock, self.root / 'm.apk',
                     ['armeabi-v7a', 'arm64-v8a', 'x86_64'])

    def test_duplicate_abi_is_rejected(self):
        with self.assertRaises(AssertionError):
            assemble(self.app(['arm64-v8a']), self.bundle, self.lock, self.root / 'd.apk', ['arm64-v8a', 'arm64-v8a'])

    def test_unknown_abi_is_rejected(self):
        with self.assertRaises(AssertionError):
            assemble(self.app(['arm64-v8a']), self.bundle, self.lock, self.root / 'z.apk', 'mips')


if __name__ == '__main__':
    unittest.main()
