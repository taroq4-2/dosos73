"""Trusted local-only FFmpeg adapter, executed inside NativeProcessSession's group."""
import os
import subprocess
import sys


def main():
    if len(sys.argv) != 5:
        raise ValueError('Invalid converter arguments')
    ffmpeg, source, target, duration_ms = sys.argv[1:]
    duration = max(0, int(duration_ms))
    # An argument array and fixed options keep names/data out of executable syntax.
    # No URL protocol, playlist demuxer, concat input or external subtitle is admitted.
    command = [ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
               '-protocol_whitelist', 'file,pipe',
               '-format_whitelist', 'mov,matroska,webm,avi,mpeg,mpegts,flv,ogg,asf',
               '-i', source, '-map', '0:a:0', '-vn', '-sn', '-dn',
               '-map_metadata', '-1', '-c:a', 'libmp3lame', '-q:a', '0',
               '-progress', 'pipe:1', '-nostats', '-f', 'mp3', target]
    print('[ExtractAudio] Converting selected video to MP3 V0', flush=True)
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, shell=False)
    tail = bytearray()
    try:
        # readline's limit also bounds malformed native output without allocating an entire line.
        while True:
            line = process.stdout.readline(4096)
            if not line:
                break
            tail.extend(line)
            del tail[:-16384]
            if duration and line.startswith(b'out_time_us='):
                try:
                    percent = min(99.9, max(0.0, int(line.split(b'=', 1)[1]) / (duration * 10.0)))
                    print('[download] {:.1f}%'.format(percent), flush=True)
                except ValueError:
                    pass
        if process.wait() != 0:
            print(tail.decode('utf-8', errors='replace'), file=sys.stderr, flush=True)
            return 1
        if not os.path.isfile(target) or os.path.getsize(target) == 0:
            raise ValueError('Empty MP3 output')
        print('[download] 100%', flush=True)
        return 0
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait()


if __name__ == '__main__':
    sys.exit(main())
