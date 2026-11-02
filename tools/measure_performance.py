"""Measure process-cold startup, PSS and startup frames without APK dependencies.

Run only on an idle, dedicated test device. This force-stops the app before
each sample; it does not clear app data, caches, media, or device statistics.
Install the desired signed APK separately and use identical settings for both
labels. These are process-cold launches with the OS disk cache left intact.
"""
import argparse
import csv
import datetime
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import time


def summary(values):
    if not values:
        return {'count': 0, 'p50': None, 'p95': None}
    ordered = sorted(values)
    return {'count': len(values), 'p50': statistics.median(values),
            'p95': ordered[math.ceil(len(values) * .95) - 1],
            'min': ordered[0], 'max': ordered[-1]}


def startup_times(text):
    found = {name: int(value) for name, value in
             re.findall(r'^(ThisTime|TotalTime|WaitTime):\s*(\d+)\s*$', text, re.MULTILINE)}
    if 'TotalTime' not in found or not re.search(r'^Status:\s*ok\s*$', text, re.MULTILINE):
        raise ValueError('am start did not report a successful measured launch')
    return found


def pss_kib(text):
    found = re.search(r'\bTOTAL PSS:\s*([\d,]+)', text)
    if not found:
        found = re.search(r'^\s*TOTAL\s+([\d,]+)\s', text, re.MULTILINE)
    if not found:
        raise ValueError('No TOTAL PSS in meminfo output')
    return int(found[1].replace(',', ''))


def frame_times_ms(text):
    """Read completed, unflagged frames; invalid timestamps stay out of metrics."""
    header = None
    result = []
    for row in csv.reader(text.splitlines()):
        if row and row[0] == 'Flags' and 'FrameCompleted' in row and 'IntendedVsync' in row:
            header = {name: position for position, name in enumerate(row)}
            continue
        if header is None or not row or not row[0].isdigit():
            continue
        try:
            flags = int(row[header['Flags']])
            intended = int(row[header['IntendedVsync']])
            completed = int(row[header['FrameCompleted']])
        except (ValueError, IndexError):
            continue
        if flags == 0 and intended > 0 and intended < completed < 2 ** 63 - 1:
            result.append((completed - intended) / 1_000_000)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runs', type=int, default=10)
    parser.add_argument('--settle-seconds', type=float, default=3)
    parser.add_argument('--allow-force-stop', action='store_true',
                        help='Confirm this is an idle test device; active transfers would be interrupted')
    args = parser.parse_args()
    if not args.allow_force_stop:
        parser.error('--allow-force-stop is required on an idle, dedicated test device')
    if not 10 <= args.runs <= 100 or not 1 <= args.settle_seconds <= 30:
        parser.error('Use 10–100 runs and 1–30 seconds of settling')
    raw = args.output.with_suffix('.raw')
    if args.output.exists() or raw.exists():
        parser.error('Use new output and raw-evidence paths')
    package = 'com.os73.dosos73'
    component = package + '/.MainActivity'

    def adb(*parameters):
        call = subprocess.run([args.adb, '-s', args.serial, *parameters], capture_output=True,
                              text=True, encoding='utf-8', errors='replace', timeout=60, check=True)
        return call.stdout

    services = adb('shell', 'dumpsys', 'activity', 'services', package)
    if re.search(r'ServiceRecord\{[^\n]*DownloadService', services):
        raise RuntimeError('Download service is running; leave the device idle before measuring')
    metadata = {key: adb('shell', 'getprop', prop).strip() for key, prop in {
        'manufacturer': 'ro.product.manufacturer', 'model': 'ro.product.model',
        'fingerprint': 'ro.build.fingerprint', 'api': 'ro.build.version.sdk',
        'abis': 'ro.product.cpu.abilist'}.items()}
    metadata.update({'window_size': adb('shell', 'wm', 'size').strip(),
                     'window_density': adb('shell', 'wm', 'density').strip(),
                     'font_scale': adb('shell', 'settings', 'get', 'system', 'font_scale').strip(),
                     'package_version': re.findall(r'version(?:Code|Name)=[^\s]+',
                                                  adb('shell', 'dumpsys', 'package', package))})
    raw.mkdir(parents=True)
    samples = []
    all_frames = []
    for run in range(1, args.runs + 1):
        adb('shell', 'am', 'force-stop', package)
        launch = adb('shell', 'am', 'start', '-W', '-n', component)
        # Preserve unsuccessful attempts too; a timeout must never vanish from evidence.
        (raw / f'{run:02d}-launch.txt').write_text(launch, encoding='utf-8')
        times = startup_times(launch)
        time.sleep(args.settle_seconds)
        memory = adb('shell', 'dumpsys', 'meminfo', package)
        frames = adb('shell', 'dumpsys', 'gfxinfo', package, 'framestats')
        durations = frame_times_ms(frames)
        for kind, contents in (('launch', launch), ('meminfo', memory), ('gfxinfo', frames)):
            (raw / f'{run:02d}-{kind}.txt').write_text(contents, encoding='utf-8')
        sample = {'run': run, 'launch_ms': times, 'pss_kib': pss_kib(memory),
                  'startup_frame_ms': summary(durations)}
        samples.append(sample)
        all_frames.extend(durations)
        print(json.dumps(sample), flush=True)
    report = {'label': args.label, 'captured_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'device': metadata, 'runs': args.runs, 'settle_seconds': args.settle_seconds,
              'launch_scope': 'process-cold, OS disk cache retained; am start -W TotalTime',
              'frame_scope': 'only frames during launch and settling; no scrolling/transition benchmark',
              'pss_scope': 'dumpsys meminfo TOTAL PSS after identical settling',
              'startup_total_ms': summary([s['launch_ms']['TotalTime'] for s in samples]),
              'pss_kib': summary([s['pss_kib'] for s in samples]),
              'startup_frame_ms': summary(all_frames), 'samples': samples,
              'not_measured': ['physical ARM/OEM performance', 'battery drain', 'CPU energy', 'GC rate',
                               'long transfer throughput', 'scrolling jank', 'multi-hour soak', 'network handover'],
              'raw_evidence': raw.name, 'app_data_cleared': False}
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
