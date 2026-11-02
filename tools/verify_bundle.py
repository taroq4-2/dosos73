"""Validate an AAB and emit machine-readable identity/delivery evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from fetch_bundletool import SHA256
from trim_universal_apk import ABIS


def validate_identity(manifest, version_code, version_name):
    root = ET.fromstring(manifest)
    ns = '{http://schemas.android.com/apk/res/android}'
    sdk = root.find('uses-sdk')
    if sdk is None:
        raise ValueError('Missing bundle SDK identity')
    identity = {'package': root.get('package'), 'versionCode': int(root.get(ns + 'versionCode')),
                'versionName': root.get(ns + 'versionName'), 'minSdk': int(sdk.get(ns + 'minSdkVersion')),
                'targetSdk': int(sdk.get(ns + 'targetSdkVersion'))}
    expected = {'package': 'com.os73.dosos73', 'versionCode': version_code,
                'versionName': version_name, 'minSdk': 29, 'targetSdk': 36}
    if identity != expected:
        raise ValueError(f'Bundle identity mismatch: observed={identity}, expected={expected}')
    return identity


def validate_runtime_payload(bundle, apk):
    native = {n[5:] for n in bundle.namelist() if n.startswith('base/lib/') and not n.endswith('/')}
    expected = {n for n in apk.namelist() if n.startswith('lib/') and not n.endswith('/')}
    # Checking only the files present in the AAB accepts a missing executable.
    # Compare both complete path sets before comparing any payload bytes.
    if native != expected:
        raise ValueError(f'Native delivery paths differ: missing={sorted(expected - native)}, extra={sorted(native - expected)}')
    for name in sorted(native):
        if bundle.read('base/' + name) != apk.read(name):
            raise ValueError(f'Native payload differs: {name}')
    for name in ('assets/dosos73/python-common.zip', 'assets/dosos73/python-common.json'):
        if bundle.read('base/' + name) != apk.read(name):
            raise ValueError(f'Shared runtime payload differs: {name}')


def validate_signature_output(output):
    # jarsigner without -strict exits zero and says "jar verified." even when
    # an unsigned entry was appended. Self-signed certificate-chain warnings
    # are expected for an upload key; unsigned payload entries are never valid.
    if 'jar verified.' not in output or re.search(r'\bunsigned\s+entries\b|treated as unsigned', output, re.IGNORECASE):
        raise ValueError('AAB does not have a fully verified JAR signature')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--bundletool', type=Path, required=True)
    p.add_argument('--java', default='java')
    p.add_argument('--report', type=Path, required=True)
    p.add_argument('--compact-apk', type=Path)
    p.add_argument('--require-signed', action='store_true')
    p.add_argument('--version-code', type=int, required=True)
    p.add_argument('--version-name', required=True)
    a = p.parse_args()
    if hashlib.sha256(a.bundletool.read_bytes()).hexdigest() != SHA256:
        raise ValueError('Untrusted bundletool')
    command = [a.java, '-Duser.language=en', '-jar', str(a.bundletool)]
    result = subprocess.run(command + ['validate', '--bundle=' + str(a.bundle)], capture_output=True, text=True, check=True)
    manifest = subprocess.run(command + ['dump', 'manifest', '--bundle=' + str(a.bundle)], capture_output=True, text=True, check=True).stdout
    identity = validate_identity(manifest, a.version_code, a.version_name)
    with zipfile.ZipFile(a.bundle) as bundle:
        if len(bundle.namelist()) != len(set(bundle.namelist())) or bundle.testzip():
            raise ValueError('Duplicate or corrupt bundle entries')
        native = [i for i in bundle.infolist() if i.filename.startswith('base/lib/') and not i.is_dir()]
        if {i.filename.split('/')[2] for i in native} != set(ABIS):
            raise ValueError('All four bundle ABIs are required')
        records = [{'path': i.filename, 'bytes': i.file_size, 'sha256': hashlib.sha256(bundle.read(i)).hexdigest()} for i in native]
        if a.compact_apk:
            with zipfile.ZipFile(a.compact_apk) as apk:
                validate_runtime_payload(bundle, apk)
        signature_present = any(i.upper().endswith(('.RSA', '.EC', '.DSA')) for i in bundle.namelist() if i.startswith('META-INF/'))
        mapping_present = 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map' in bundle.namelist()
        if not mapping_present:
            raise ValueError('Missing R8 mapping')
    if a.require_signed:
        if not signature_present:
            raise ValueError('Unsigned bundle')
        java_path = Path(a.java)
        jarsigner = str(java_path.with_name('jarsigner.exe' if java_path.suffix == '.exe' else 'jarsigner')) if java_path.parent != Path('.') else 'jarsigner'
        checked = subprocess.run([jarsigner, '-J-Duser.language=en', '-verify', str(a.bundle)], capture_output=True, text=True, check=True)
        validate_signature_output(checked.stdout + '\n' + checked.stderr)
    report = {'identity': identity, 'bytes': a.bundle.stat().st_size, 'sha256': hashlib.sha256(a.bundle.read_bytes()).hexdigest(),
              'bundletool_validation': result.stdout.strip(), 'signature_present': signature_present,
              'signature_verified': a.require_signed, 'mapping_present': mapping_present,
              'certificate_trust_validated': False,
              'native': records, 'device_delivery_tested': False, 'play_approval': False}
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('native', 'bundletool_validation')}, indent=2))


if __name__ == '__main__':
    main()
