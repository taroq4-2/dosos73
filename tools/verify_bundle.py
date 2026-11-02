"""Validate an AAB and emit machine-readable identity/delivery evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from fetch_bundletool import SHA256
from trim_universal_apk import ABIS


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--bundletool', type=Path, required=True)
    p.add_argument('--java', default='java')
    p.add_argument('--report', type=Path, required=True)
    p.add_argument('--compact-apk', type=Path)
    p.add_argument('--require-signed', action='store_true')
    p.add_argument('--version-code', type=int, required=True)
    a = p.parse_args()
    if hashlib.sha256(a.bundletool.read_bytes()).hexdigest() != SHA256:
        raise ValueError('Untrusted bundletool')
    command = [a.java, '-Duser.language=en', '-jar', str(a.bundletool)]
    result = subprocess.run(command + ['validate', '--bundle=' + str(a.bundle)], capture_output=True, text=True, check=True)
    manifest = subprocess.run(command + ['dump', 'manifest', '--bundle=' + str(a.bundle)], capture_output=True, text=True, check=True).stdout
    root = ET.fromstring(manifest)
    ns = '{http://schemas.android.com/apk/res/android}'
    sdk = root.find('uses-sdk')
    identity = {'package': root.get('package'), 'versionCode': int(root.get(ns + 'versionCode')),
                'versionName': root.get(ns + 'versionName'), 'minSdk': int(sdk.get(ns + 'minSdkVersion')),
                'targetSdk': int(sdk.get(ns + 'targetSdkVersion'))}
    assert identity['package'] == 'com.os73.dosos73' and identity['versionCode'] == a.version_code
    assert identity['minSdk'] == 29 and identity['targetSdk'] == 36
    with zipfile.ZipFile(a.bundle) as bundle:
        assert len(bundle.namelist()) == len(set(bundle.namelist())) and not bundle.testzip()
        native = [i for i in bundle.infolist() if i.filename.startswith('base/lib/') and not i.is_dir()]
        assert {i.filename.split('/')[2] for i in native} == set(ABIS)
        records = [{'path': i.filename, 'bytes': i.file_size, 'sha256': hashlib.sha256(bundle.read(i)).hexdigest()} for i in native]
        if a.compact_apk:
            with zipfile.ZipFile(a.compact_apk) as apk:
                for i in native:
                    assert bundle.read(i) == apk.read(i.filename[5:]), i.filename
                for name in ('assets/dosos73/python-common.zip', 'assets/dosos73/python-common.json'):
                    assert bundle.read('base/' + name) == apk.read(name)
        signature_present = any(i.upper().endswith(('.RSA', '.EC', '.DSA')) for i in bundle.namelist() if i.startswith('META-INF/'))
        mapping_present = 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map' in bundle.namelist()
        assert mapping_present, 'Missing R8 mapping'
    if a.require_signed:
        assert signature_present, 'Unsigned bundle'
        java_path = Path(a.java)
        jarsigner = str(java_path.with_name('jarsigner.exe' if java_path.suffix == '.exe' else 'jarsigner')) if java_path.parent != Path('.') else 'jarsigner'
        checked = subprocess.run([jarsigner, '-J-Duser.language=en', '-verify', str(a.bundle)], capture_output=True, text=True, check=True)
        assert 'jar verified.' in checked.stdout, 'AAB signature verification failed'
    report = {'identity': identity, 'bytes': a.bundle.stat().st_size, 'sha256': hashlib.sha256(a.bundle.read_bytes()).hexdigest(),
              'bundletool_validation': result.stdout.strip(), 'signature_present': signature_present,
              'signature_verified': a.require_signed, 'mapping_present': mapping_present,
              'native': records, 'device_delivery_tested': False, 'play_approval': False}
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('native', 'bundletool_validation')}, indent=2))


if __name__ == '__main__':
    main()
