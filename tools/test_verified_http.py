import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('verified_http', Path(__file__).parents[1] / 'app/src/main/assets/verified_http.py')
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)


class Response(io.BytesIO):
    def __init__(self, body, status=200, headers=None, fail=False, url='https://cdn.example/video'):
        super().__init__(body)
        self.status, self.url, self.fail = status, url, fail
        self.headers = {'Content-Type': 'video/mp4', 'Content-Length': str(len(body)), 'ETag': '"v1"'}
        self.headers.update(headers or {})

    def read(self, count=-1):
        if self.fail and self.tell() >= 65536:
            raise OSError('Network disconnected')
        return super().read(count)


class VerifiedHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'media.mp4'
        self.info = {'url': 'https://cdn.example/video', 'protocol': 'https'}
        self.requests, self.events = [], []
        self.body = bytes(range(256)) * 1024
        self.space = patch.object(http.os, 'statvfs', return_value=types.SimpleNamespace(f_bavail=2**30, f_frsize=1), create=True)
        self.space.start()
        self.addCleanup(self.space.stop)

    def run_transfer(self, response):
        def request(url, headers):
            self.requests.append((url, headers))
            return url
        return http.transfer(self.file, self.info, lambda _: response, lambda *args: self.events.append(args), request)

    def interrupted(self, etag='"v1"'):
        with self.assertRaises(OSError):
            self.run_transfer(Response(self.body, fail=True, headers={'ETag': etag}))
        self.assertFalse(self.file.exists())

    def test_interruption_resume_uses_etag_range_and_exact_bytes(self):
        self.interrupted()
        self.run_transfer(Response(self.body[65536:], 206, {'Content-Range': f'bytes 65536-{len(self.body)-1}/{len(self.body)}'}))
        self.assertEqual(self.body, self.file.read_bytes())
        self.assertEqual('bytes=65536-', self.requests[-1][1]['Range'])
        self.assertEqual('"v1"', self.requests[-1][1]['If-Range'])
        self.assertFalse(Path(str(self.file)+'.part.resume.json').exists())

    def test_ignored_range_restarts_without_concatenation(self):
        self.interrupted()
        new = b'new' * 1000
        self.run_transfer(Response(new, headers={'ETag': '"v2"'}))
        self.assertEqual(new, self.file.read_bytes())

    def test_changed_or_invalid_resume_headers_never_publish(self):
        changes = [
            {'ETag': '"v2"'}, {'Content-Range': 'bytes 0-1/2'},
            {'Content-Range': f'bytes 65536-{len(self.body)-2}/{len(self.body)}'},
            {'Content-Length': '1'}, {'Content-Encoding': 'gzip'},
        ]
        for changed in changes:
            with self.subTest(changed=changed):
                self.interrupted()
                headers = {'Content-Range': f'bytes 65536-{len(self.body)-1}/{len(self.body)}', **changed}
                with self.assertRaises(ValueError):
                    self.run_transfer(Response(self.body[65536:], 206, headers))
                self.assertFalse(self.file.exists())

    def test_changed_redirect_identity_is_rejected(self):
        self.interrupted()
        with self.assertRaises(ValueError):
            self.run_transfer(Response(self.body[65536:], 206,
                {'Content-Range': f'bytes 65536-{len(self.body)-1}/{len(self.body)}'}, url='https://other.example/video'))

    def test_weak_validator_cannot_resume(self):
        self.interrupted('W/"v1"')
        self.run_transfer(Response(self.body))
        self.assertNotIn('Range', self.requests[-1][1])
        self.assertEqual(self.body, self.file.read_bytes())

    def test_corrupted_partial_is_not_appended(self):
        self.interrupted()
        part = Path(str(self.file)+'.part')
        part.write_bytes(b'x' * part.stat().st_size)
        self.run_transfer(Response(self.body))
        self.assertNotIn('Range', self.requests[-1][1])

    def test_uncheckpointed_tail_is_discarded(self):
        self.interrupted()
        with Path(str(self.file)+'.part').open('ab') as out: out.write(b'uncommitted')
        self.run_transfer(Response(self.body[65536:], 206, {'Content-Range': f'bytes 65536-{len(self.body)-1}/{len(self.body)}'}))
        self.assertEqual(self.body, self.file.read_bytes())

    def test_changed_source_url_restarts(self):
        self.interrupted()
        self.info['url'] += '?version=2'
        self.run_transfer(Response(self.body))
        self.assertNotIn('Range', self.requests[-1][1])

    def test_unsolicited_range_and_oversize_and_short_body(self):
        cases = [Response(b'abc', 206, {'Content-Range':'bytes 0-2/3'}),
                 Response(b'abc', headers={'Content-Length':'2'}),
                 Response(b'abc', headers={'Content-Length':'4'}), Response(b'')]
        for response in cases:
            with self.subTest(status=response.status, headers=response.headers):
                with self.assertRaises((OSError, ValueError)): self.run_transfer(response)
                self.assertFalse(self.file.exists())

    def test_storage_reserve_is_enforced_before_writing(self):
        with patch.object(http.os, 'statvfs', return_value=types.SimpleNamespace(f_bavail=1, f_frsize=1)):
            with self.assertRaises(OSError): self.run_transfer(Response(self.body))
        self.assertFalse(self.file.exists())

    def test_signed_url_is_not_persisted(self):
        self.info['url'] += '?secret=private-token'
        self.interrupted()
        journal = Path(str(self.file)+'.part.resume.json').read_text()
        self.assertNotIn('private-token', journal)
        self.assertEqual(65536, json.loads(journal)['offset'])


if __name__ == '__main__': unittest.main()
