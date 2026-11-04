"""Regression tests for rebuilt runtime trust and compatibility boundaries."""
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from assemble_rebuilt_runtime import (
    ABIS, Entry, encode_zip, load_segment_identity, read_zip, runtime_identity,
    select_runtime, sha, split_runtime, verified_artifact,
    tool_path,
)


REGULAR = stat.S_IFREG | 0o644
SYMLINK = stat.S_IFLNK | 0o777


class ElfSegmentTest(unittest.TestCase):
    def test_llvm_symlink_alias_is_kept_for_command_mode(self):
        with tempfile.TemporaryDirectory() as folder:
            binary = Path(folder) / 'llvm-readobj'
            binary.write_bytes(b'fixture')
            alias = Path(folder) / 'llvm-readelf'
            try:
                alias.symlink_to(binary.name)
            except OSError as error:
                self.skipTest(f'Host symlink creation unavailable: {error}')
            self.assertEqual(tool_path(alias), alias.absolute())
            self.assertNotEqual(tool_path(alias), binary.absolute())

    def elf(self, bits, order='<'):
        wide = bits == 64
        header_size, segment_size = (64, 56) if wide else (52, 32)
        data = bytearray(header_size + segment_size)
        data[:7] = b'\x7fELF' + bytes((2 if wide else 1, 1 if order == '<' else 2, 1))
        struct.pack_into(order + 'HH', data, 16, 3, 62 if wide else 40)
        struct.pack_into(order + ('Q' if wide else 'I'), data, 32 if wide else 28, header_size)
        struct.pack_into(order + 'HH', data, 54 if wide else 42, segment_size, 1)
        fields = (1, 5, 0, 0, 0, len(data), len(data), 16384) if wide else (
            1, 0, 0, 0, len(data), len(data), 5, 16384)
        struct.pack_into(order + ('IIQQQQQQ' if wide else 'IIIIIIII'), data, header_size, *fields)
        return data

    def test_headers_are_read_without_tool_output_layout(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'library.so'
            for bits in (32, 64):
                for order in ('<', '>'):
                    with self.subTest(bits=bits, order=order):
                        data = self.elf(bits, order)
                        path.write_bytes(data)
                        with patch('assemble_rebuilt_runtime.subprocess.run',
                                   return_value=SimpleNamespace(stdout='No printed program-header table')):
                            identity = runtime_identity('llvm-readelf', path)
                        self.assertEqual(identity[2], load_segment_identity(data))

    def test_changed_load_flags_are_detected(self):
        first = self.elf(64)
        second = bytearray(first)
        struct.pack_into('<I', second, 68, 6)
        self.assertNotEqual(load_segment_identity(first), load_segment_identity(second))

    def test_missing_or_truncated_program_headers_are_rejected(self):
        data = self.elf(64)
        with self.assertRaises(ValueError):
            load_segment_identity(data[:-1])
        struct.pack_into('<I', data, 64, 0)
        with self.assertRaisesRegex(ValueError, 'no load segment'):
            load_segment_identity(data)


class RuntimeSelectionTest(unittest.TestCase):
    def test_versioned_backing_file_changes_preserve_the_public_alias(self):
        alias = 'usr/lib/libsqlite3.so'
        old_name = 'usr/lib/libsqlite3.so.3.50.4'
        new_name = 'usr/lib/libsqlite3.so.3.54.1'
        site = 'usr/lib/python3.12/site-packages/vendor.py'
        old = {alias: Entry(b'libsqlite3.so.3.50.4', SYMLINK),
               old_name: Entry(b'\x7fELFold', REGULAR), site: Entry(b'inherited', REGULAR)}
        new = {alias: Entry(b'libsqlite3.so.3.54.1', SYMLINK),
               new_name: Entry(b'\x7fELFnew', REGULAR),
               'usr/lib/libsqlite3.54.1.so': Entry(b'\x7fELFunused-tcl', REGULAR)}
        records = {alias: {}, new_name: {'elf': {'needed': [], 'soname': None}}}
        retained, selected, superseded = select_runtime(old, new, records, {})
        self.assertEqual(retained, {site: old[site]})
        self.assertEqual(set(selected), {alias, new_name})
        self.assertEqual(superseded, [old_name])

    def test_unknown_missing_baseline_file_stops_selection(self):
        old = {'usr/lib/python3.12/required.py': Entry(b'required', REGULAR)}
        with self.assertRaisesRegex(ValueError, 'Unaccounted baseline'):
            select_runtime(old, {}, {}, {})

    def test_escaping_and_cyclic_rebuilt_links_stop_selection(self):
        old = {'usr/lib/current.so': Entry(b'\x7fELFold', REGULAR)}
        for link in (b'/outside', b'../../outside', b'current.so'):
            with self.subTest(link=link), self.assertRaises(ValueError):
                select_runtime(old, {'usr/lib/current.so': Entry(link, SYMLINK)},
                               {'usr/lib/current.so': {}}, {})

    def test_shared_split_reconstructs_each_abi_without_changing_modes(self):
        shared = Entry(b'python', REGULAR)
        runtime = {abi: {'usr/lib/python3.12/code.py': shared,
                         'usr/lib/native.so': Entry(abi.encode(), stat.S_IFREG | 0o755)}
                   for abi in ABIS}
        common, metadata, specific = split_runtime(runtime)
        manifest = json.loads(metadata)
        self.assertEqual(manifest['sha256'], sha(common))
        self.assertEqual(len(manifest['files']), 1)
        for abi in ABIS:
            self.assertFalse(read_zip(common).keys() & read_zip(specific[abi]).keys())
            self.assertEqual(read_zip(common) | read_zip(specific[abi]), runtime[abi])

    def test_unsafe_archive_members_are_rejected(self):
        for name in ('../outside', '/usr/file', 'usr/a/../file', 'usr\\file'):
            with self.subTest(name=name):
                data = io.BytesIO()
                with ZipFile(data, 'w') as archive:
                    info = ZipInfo(name.replace('\\', '/'))
                    info.create_system = 3
                    info.external_attr = REGULAR << 16
                    archive.writestr(info, b'text')
                raw = data.getvalue()
                if '\\' in name:
                    raw = raw.replace(name.replace('\\', '/').encode(), name.encode())
                with self.assertRaises(ValueError):
                    read_zip(raw)


class ArtifactReceiptTest(unittest.TestCase):
    def make_artifact(self, directory, mutation=None):
        abi = 'x86_64'
        producer = 'a' * 40
        runtime = encode_zip({'usr/lib/example.so': Entry(b'\x7fELFfile', REGULAR)})
        launcher = b'\x7fELFlauncher'
        proof = {'abi': abi, 'unresolved_needed': [], 'archive_sha256': sha(runtime),
                 'launcher': {'sha256': sha(launcher), 'bytes': len(launcher)},
                 'lock': {'sources': {'python': {'sha256': 'b' * 64}}, 'ndk_archive_sha256': 'c' * 64},
                 'payload': [{'path': 'usr/lib/example.so', 'sha256': sha(b'\x7fELFfile'), 'bytes': 8}]}
        proof_data = json.dumps(proof).encode()
        config, header = b'cross config', b'android header'
        descriptor = lambda value: {'bytes': len(value), 'sha256': sha(value)}
        receipt = {'abi': abi, 'core_archive_sha256': sha(runtime), 'core_proof': descriptor(proof_data),
                   'host_python_source': {'sha256': 'b' * 64},
                   'ndk': {'archive_sha256': 'c' * 64},
                   'termux_files': {'python/build/config.log': descriptor(config),
                                    'python/build/pyconfig.h': descriptor(header)}}
        if mutation:
            mutation(receipt)
        path = directory / 'x86_64.zip'
        with ZipFile(path, 'w', ZIP_DEFLATED) as archive:
            archive.writestr('native-runtime-out/x86_64/libpython-core.zip.so', runtime)
            archive.writestr('native-runtime-out/x86_64/libpython.so', launcher)
            archive.writestr('native-runtime-out/x86_64/native-build-proof.json', proof_data)
            archive.writestr('source-receipt.json', json.dumps(receipt))
            archive.writestr('ndk-archive-sha256.txt', 'c' * 64 + '  ndk.zip\n')
            archive.writestr('target-python-config.log', config)
            archive.writestr('target-python-pyconfig.h', header)
        expected = {'digest': 'sha256:' + sha(path.read_bytes()),
                    'workflow_run': {'id': 42, 'head_sha': producer}}
        return path, expected, producer

    def test_receipt_matches_exact_artifact_and_producer(self):
        with tempfile.TemporaryDirectory() as folder:
            path, expected, producer = self.make_artifact(Path(folder))
            entries, _, _, _ = verified_artifact(path, 'x86_64', expected, 42, producer)
            self.assertEqual(set(entries), {'usr/lib/example.so'})
            with self.assertRaisesRegex(ValueError, 'producer identity'):
                verified_artifact(path, 'x86_64', expected, 43, producer)

    def test_valid_outer_hash_cannot_hide_a_mismatched_source_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            path, expected, producer = self.make_artifact(
                Path(folder), lambda receipt: receipt.update(core_archive_sha256='d' * 64))
            with self.assertRaisesRegex(ValueError, 'source receipt mismatch'):
                verified_artifact(path, 'x86_64', expected, 42, producer)

    def test_config_header_is_checked_against_the_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            path, expected, producer = self.make_artifact(
                Path(folder), lambda receipt: receipt['termux_files']['python/build/pyconfig.h'].update(bytes=1))
            with self.assertRaisesRegex(ValueError, 'configuration receipt mismatch'):
                verified_artifact(path, 'x86_64', expected, 42, producer)


if __name__ == '__main__':
    unittest.main()
