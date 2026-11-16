"""Durable HTTPS continuation for yt-dlp's plain HTTP media downloader.

Only identical source/representation identities may append. Manifests and data live
in private no-backup staging, contain URL digests (not signed URLs), and are fsynced
in data-before-journal order. Segmented/live protocols keep upstream behavior.
"""
import hashlib
import json
import os
import re
import time
from pathlib import Path

BLOCK = 65536
CHECKPOINT = 1048576
RESERVE = 32 * 1024 * 1024


def strong_etag(value):
    return bool(value and re.fullmatch(r'"[\x21\x23-\x7e\x80-\xff]*"', value))


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def checkpoint(part, journal, source):
    try:
        if journal.stat().st_size > 4096:
            return None
        data = json.loads(journal.read_text())
        if (data['source'] != source or not strong_etag(data['etag'])
                or type(data['offset']) is not int or type(data['total']) is not int
                or not 0 < data['offset'] < data['total'] or part.stat().st_size < data['offset']):
            return None
        hashed = hashlib.sha256()
        with part.open('rb') as stream:
            remaining = data['offset']
            while remaining:
                block = stream.read(min(BLOCK, remaining))
                if not block:
                    return None
                hashed.update(block)
                remaining -= len(block)
        return (data, hashed) if hashed.hexdigest() == data['digest'] else None
    except (OSError, ValueError, KeyError, TypeError):
        return None


def write_checkpoint(stream, journal, record, hashed, offset):
    stream.flush()
    os.fsync(stream.fileno())
    if record is None:
        journal.unlink(missing_ok=True)
        return
    temp = journal.with_suffix('.new')
    with temp.open('w') as out:
        json.dump(dict(record, offset=offset, digest=hashed.hexdigest()), out)
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, journal)


def transfer(filename, info, open_response, progress, request_factory):
    """Testable streaming core; all network access stays in yt-dlp's checked proxy."""
    part = Path(str(filename) + '.part')
    journal = Path(str(part) + '.resume.json')
    source = digest(info['url'])
    saved = checkpoint(part, journal, source)
    record, hashed = saved or (None, hashlib.sha256())
    offset = record['offset'] if record else 0
    headers = dict(info.get('http_headers') or {})
    # Extractor-supplied casing must not defeat representation or range controls.
    headers = {k: v for k, v in headers.items() if k.lower() not in ('accept-encoding', 'range', 'if-range')}
    headers['Accept-Encoding'] = 'identity'
    if record:
        headers.update({'Range': f'bytes={offset}-', 'If-Range': record['etag']})
    response = open_response(request_factory(info['url'], headers=headers))
    try:
        status = response.status
        mime = response.headers.get('Content-Type', '').split(';')[0].lower()
        if response.headers.get('Content-Encoding', 'identity').lower() not in ('', 'identity') or mime.startswith('text/') or 'json' in mime:
            raise ValueError('Unexpected media representation')
        etag = response.headers.get('ETag')
        length = response.headers.get('Content-Length')
        length = int(length) if length and re.fullmatch(r'[0-9]+', length) else None
        final = digest(response.url)
        if status == 206:
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
            if not record or not match:
                raise ValueError('Unsolicited or invalid Content-Range')
            first, last, total = map(int, match.groups())
            if (first != offset or last != total - 1 or total != record['total']
                    or etag != record['etag'] or final != record['final']
                    or (length is not None and length != total - offset)):
                journal.unlink(missing_ok=True)
                raise ValueError('Changed resume representation')
        elif status == 200:
            offset = 0
            hashed = hashlib.sha256()
            total = length
        else:
            raise ValueError(f'HTTP {status}')
        record = dict(source=source, final=final, etag=etag, total=total) if strong_etag(etag) and total else None
        journal.unlink(missing_ok=True)
        with part.open('r+b' if part.exists() else 'w+b') as out:
            out.truncate(offset)
            out.seek(offset)
            durable = offset
            started = last_progress = time.monotonic()
            start_offset = offset
            try:
                while True:
                    block = response.read(BLOCK)
                    if not block:
                        break
                    if total is not None and len(block) > total - offset:
                        raise ValueError('Media body exceeds declared length')
                    if offset == durable:
                        space = os.statvfs(part.parent)
                        if space.f_bavail * space.f_frsize < RESERVE + CHECKPOINT:
                            raise OSError(28, 'Insufficient storage reserve')
                    out.write(block)
                    hashed.update(block)
                    offset += len(block)
                    now = time.monotonic()
                    if offset - durable >= CHECKPOINT:
                        write_checkpoint(out, journal, record, hashed, offset)
                        durable = offset
                    if now - last_progress >= 0.25:
                        progress(offset, total, (offset - start_offset) / max(0.001, now - started), False)
                        last_progress = now
                if total is not None and offset != total:
                    raise OSError('Incomplete media body')
                if offset == 0:
                    raise ValueError('Empty media body')
                out.flush()
                os.fsync(out.fileno())
            except BaseException:
                write_checkpoint(out, journal, record, hashed, offset)
                raise
        os.replace(part, filename)
        journal.unlink(missing_ok=True)
        progress(offset, offset, 0, True)
        return True
    finally:
        response.close()


def install():
    from yt_dlp.downloader.http import HttpFD
    from yt_dlp.networking import Request
    from yt_dlp.networking.exceptions import HTTPError
    original = HttpFD.real_download

    def download(self, filename, info):
        if (info.get('protocol') != 'https' or info.get('request_data') is not None
                or any(k.lower() == 'range' for k in info.get('http_headers', {}))
                or info.get('impersonate')):
            # No unsafe cross-attempt continuation for fragments or special requests.
            self.params['continuedl'] = False
            return original(self, filename, info)

        def progress(done, total, speed, finished):
            self._hook_progress({'filename': filename, 'status': 'finished' if finished else 'downloading',
                                 'downloaded_bytes': done, 'total_bytes': total, 'speed': speed,
                                 'eta': (total - done) / speed if total and speed else None}, info)
        try:
            self.report_destination(filename)
            return transfer(filename, info, self.ydl.urlopen, progress, Request)
        except HTTPError as error:
            if error.status == 416:
                # Never accept yt-dlp's +/-100 byte 416 heuristic as completion.
                Path(str(filename) + '.part.resume.json').unlink(missing_ok=True)
                return transfer(filename, info, self.ydl.urlopen, progress, Request)
            raise
    HttpFD.real_download = download
