# Readiness QA matrix

Date: 2 November 2026. Package: `com.os73.dosos73`, candidate1.3.0-rc.2,
code15, min29, target36. Source/build identity and exact executed counts are
recorded with the candidate verification file. Previous rc.1 results are
historical evidence and do not replace this candidate's acceptance.

## Commands

```text
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease exportRuntimeInventory verifyNoKapt --console=plain
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/check_dependency_advisories.py --inventory app/build/reports/audit/runtime-components.json --output app/build/reports/audit/osv.json
python tools/check_build_advisories.py --output app/build/reports/audit/build-advisories.json
adb -s emulator-5560 shell am instrument -w com.os73.dosos73.test/androidx.test.runner.AndroidJUnitRunner
```

CI executes device tests separately on API29,30,31,32,33,34,35,36. It grants
notifications on33+ so the real Home/quick preset/duplicate flows actually run.
The five existing optional external-source fixture tests must be distinguished
from passing tests; they are not skipped to hide a failure.

| Area | Coverage and evidence | Candidate execution state |
|---|---|---|
| JVM | URL/IP/file names, HTTP200/206/416, Range/ETag, integrity/checkpoint state | Baseline116pass; final refreshed run pending |
| Host tools | Advisory failure/expiry/pagination, build coordinates/KAPT guard, AAB tamper boundaries, performance parsers | 21pass on integrated tools |
| Android versions | Full instrumentation on API29–36 | New CI run pending |
| New storage boundary | `PublicationUriBoundaryTest`, foreign/malformed rename results and recoverability | 2methods pending |
| Controlled recovery | `StorageRecoverySoakTest`,100seeded provider/journal reconstruction cases and SHA256 | 1method/100cases pending; not real OS process-kill |
| New adaptive UI | `ReviewLayoutTest`:48dp RTL200%, duplicate actions, real landscape long details | 3methods pending |
| Existing features | OfflineAudio, LocalReplay/TaskJournal, Presets, History, real MediaToolsFlow, QuickLaunch | Full regression pending |
| Network/security | PublicProxy/Lifecycle/Transport, LiteralNetworkBoundary, TlsVersion, NetworkRecovery, DirectDownloader | Full regression pending |
| Storage/queue/native | Publication/RenameRecovery, ServiceLease/NetworkDeferral, Journal/Failure retention, NativeProcess ownership/cancel | Full regression pending |
| Signed release | Signature, source/runtime correspondence, install/upgrade, retained media hashes, direct transfer and MP3 playback | Pending exact candidate |
| AAB delivery | bundletool validate, identity/ABI/mapping; build/extract native split and install as upgrade | Prototype validation passed; signed candidate pending |
| Package size/performance | Universal bytes; per-ABI device delivery sizes; matched startup/PSS/raw records | Pending signed candidate |

## Explicit NOT RUN / external gates

Physical ARM low-memory and multiple-OEM Android10/13/15/16 devices; real
Wi-Fi↔cellular handover, USB/cloud provider removal, full-disk ENOSPC, long Doze
and reboot transfer acceptance, multi-hour low-memory/battery/CPU/thermal soak,
human TalkBack and Switch Access, and Google Play testing-track/policy approval.
Automated provider faults, layout semantics and emulator API coverage do not
certify those conditions. Keep each item open rather than inferring a pass.
