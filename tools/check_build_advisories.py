"""Check actual Gradle/AGP/Kotlin build-tool coordinates against OSV.

Build tooling is not part of releaseRuntimeClasspath. Parse its checked-in locks
separately rather than pretending a runtime-only lookup covers the build host.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import sys

from check_dependency_advisories import audit, read_json

KAPT_DECLARATION = re.compile(
    r'''\b(?:id|plugin)\(\s*["'](?:kotlin-kapt|org\.jetbrains\.kotlin\.kapt)["']\s*\)|'''
    r'''\bapply\(\s*plugin\s*=\s*["'](?:kotlin-kapt|org\.jetbrains\.kotlin\.kapt)["']\s*\)|'''
    r'''\bkotlin\(\s*["']kapt["']\s*\)|(?m:^\s*kapt\s*[({])''', re.IGNORECASE)


def verify_kapt_source(text):
    if KAPT_DECLARATION.search(text):
        raise ValueError('KAPT declaration invalidates CVE-2026-53914 disposition')


def exact(pattern, text, label):
    matches = re.findall(pattern, text)
    if len(matches) != 1:
        raise ValueError(f'Expected one pinned {label} version')
    return matches[0]


def queries_for_build(wrapper, plugins):
    gradle = exact(r'(?m)^distributionUrl=.*?/gradle-([0-9]+(?:\.[0-9]+)+)-bin\.zip\s*$', wrapper, 'Gradle')
    coordinates = [('org.gradle:gradle-core', gradle)]
    for plugin_id, coordinate in (
        ('com.android.application', 'com.android.tools.build:gradle'),
        ('org.jetbrains.kotlin.android', 'org.jetbrains.kotlin:kotlin-gradle-plugin'),
        ('org.jetbrains.kotlin.plugin.compose', 'org.jetbrains.kotlin:compose-compiler-gradle-plugin'),
    ):
        version = exact(r'id\("' + re.escape(plugin_id) + r'"\)\s+version\s+"([0-9]+(?:\.[0-9]+)+)"', plugins, plugin_id)
        coordinates.append((coordinate, version))
    return [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version} for name, version in coordinates]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = {'checked_at_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
              'scope': 'Exact Gradle wrapper and AGP/Kotlin plugin Maven coordinates; not all transitive build dependencies'}
    try:
        wrapper = args.root / 'gradle/wrapper/gradle-wrapper.properties'
        plugins = args.root / 'build.gradle.kts'
        report['inputs'] = {str(p.relative_to(args.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (wrapper, plugins)}
        for source in args.root.rglob('*'):
            if source.suffix not in ('.gradle', '.kts', '.toml') or any(part in ('build', '.gradle', '.git', 'work') for part in source.relative_to(args.root).parts):
                continue
            text = source.read_text(encoding='utf-8-sig')
            verify_kapt_source(text)
            if source.suffix == '.toml' and re.search(r'(?i)kapt', text):
                raise ValueError('KAPT catalog entry needs manual advisory review')
            report['inputs'][str(source.relative_to(args.root))] = hashlib.sha256(source.read_bytes()).hexdigest()
        report['kapt_source_guard'] = 'No KAPT plugin/configuration declarations; run Gradle verifyNoKapt for resolved task graph'
        queries = queries_for_build(wrapper.read_text(encoding='utf-8-sig'), plugins.read_text(encoding='utf-8-sig'))
        disposition_file = args.root / 'docs/audit/advisory-dispositions.json'
        report['dispositions_sha256'] = hashlib.sha256(disposition_file.read_bytes()).hexdigest()
        report.update(audit(queries, read_json(disposition_file)['dispositions'], dt.date.today()))
        code = 0 if report['result'] == 'PASS_SCOPED_LOOKUP' else 1
    except Exception as error:
        report.update(result='ERROR_INCOMPLETE_SCAN', error_type=type(error).__name__)
        code = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f"Build advisory lookup: {report['result']}; queries={report.get('query_count', 0)}; findings={len(report.get('findings', []))}")
    return code


if __name__ == '__main__':
    sys.exit(main())
