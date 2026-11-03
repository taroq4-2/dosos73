# Readiness QA matrix

Date: 2–3 November 2026. Package: `com.os73.dosos73`, candidate1.3.0-rc.2,
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
| JVM | URL/IP/file names, HTTP200/206/416, Range/ETag, integrity/checkpoint state | 116 passed on the release source; zero failures |
| Host tools | Advisory failure/expiry/pagination, build coordinates/KAPT guard, AAB tamper boundaries, performance parsers | 29 passed on integrated tools |
| Android versions | Full instrumentation on API29–36 | All eight device jobs passed on [`40fa614`](https://github.com/taroq4-2/dosos73/actions/runs/41637321085) |
| New storage boundary | `PublicationUriBoundaryTest`, foreign/malformed rename results and recoverability | 2 methods passed on API35 |
| Controlled recovery | `StorageRecoverySoakTest`,100seeded provider/journal reconstruction cases and SHA256 | [100/100, zero lost/corrupt/duplicate](docs/readiness/storage-soak-api35.json); not real OS process-kill |
| New adaptive UI | `ReviewLayoutTest` and `LandscapeReviewLayoutTest`:48dp RTL200%, duplicate actions, real landscape long details | Previous 2 layout checks passed; the corrected landscape window fixture passed a targeted API35 run |
| Existing features | OfflineAudio, LocalReplay/TaskJournal, Presets, History, real MediaToolsFlow, QuickLaunch | Automated regression passed; all three OfflineAudio tests passed on local API35 |
| Network/security | PublicProxy/Lifecycle/Transport, LiteralNetworkBoundary, TlsVersion, NetworkRecovery, DirectDownloader | Automated regression passed; optional external-source fixtures not run locally |
| Storage/queue/native | Publication/RenameRecovery, ServiceLease/NetworkDeferral, Journal/Failure retention, NativeProcess ownership/cancel | CI passed; local runtime inventory Python launch timed out once |
| Signed release | Signature, source/runtime correspondence, install/upgrade and retained media hashes | Exact APK installed and opened; 6 retained files preserved. New live network download/playback on this signed APK not rerun |
| AAB delivery | bundletool validate, identity/ABI/mapping; build/extract native split and install as upgrade | Signed AAB validated; all native/shared files match APK; locally signed x86-64 derived splits installed and opened; all 6 media hashes matched |
| Package size/performance | Universal bytes; per-ABI device delivery sizes; matched startup/PSS/raw records | 62,116,918-byte APK; AAB estimates in performance record |

The signed APK and AAB embed `e599d58b058006a7c44fec8bd84307b0f68dcf04`.
The follow-up test-only change keeps the component Activity mounted while its
actual window rotates. The targeted run required landscape resources and a
window wider than tall before testing Close. The earlier prelaunch configuration
broadcast timed out; that failed run is preserved. No production source or
release build inputs changed in the follow-up test/CI commits.

The complete local repeat (`readiness-fixture-full-instrument.log`) reported
181 cases: 175 passed, five optional fixture cases skipped and one failed.
`RuntimeInventoryTest.bundledComponentsExecute` exceeded its 15-second Python
startup limit, although it passed in the eight CI device jobs. The same debug
APK and isolated test passed on the same local API35 emulator (JUnit elapsed
14.279 s); the original timeout is retained, with no timeout increase. The three layout
cases and 100 storage recovery scenarios all passed locally. Signed delivery
checks exercised launch and upgrade on the same emulator; they are not a fresh
live source download or a physical ARM device certification.

## Explicit NOT RUN / external gates

Physical ARM low-memory and multiple-OEM Android10/13/15/16 devices; real
Wi-Fi↔cellular handover, USB/cloud provider removal, full-disk ENOSPC, long Doze
and reboot transfer acceptance, multi-hour low-memory/battery/CPU/thermal soak,
human TalkBack and Switch Access, and Google Play testing-track/policy approval.
Automated provider faults, layout semantics and emulator API coverage do not
certify those conditions. Keep each item open rather than inferring a pass.
