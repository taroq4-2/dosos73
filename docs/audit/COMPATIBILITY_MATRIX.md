# Android compatibility and device evidence

On 4 November, the new source-built candidate was tested on an API 35 x86-64
16 KiB emulator with compatibility mode disabled. The 185-test runner result
included 180 passes and five optional fixture skips. Five focused engine tests
also passed on a standard 4 KiB emulator. Both installed and opened the signed
62,051,382-byte universal APK. Loaded versions and actual kernel properties
are recorded in [TEST_EVIDENCE.md](TEST_EVIDENCE.md).
The updated Python runtime passed all API 29–36 x86-64 jobs in
[run 41932324505](https://github.com/taroq4-2/dosos73/actions/runs/41932324505)
on source 24bfa90, after the first Linux packaging attempt failed. Its debug
test pair retains the upstream test FFmpeg input; the compact signed release
FFmpeg was checked in the local 4/16 KiB trials.
The older tables below remain evidence of their named source revisions.

Mission baseline `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. The source declares min SDK 29, compile/target SDK 36 and the four default native ABIs `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` in `app/build.gradle.kts`. This is a supported build configuration; it is not a claim that every OEM device, kernel, file provider or media source passed.

## API and ABI matrix

The [previous readiness CI run 41637321085](https://github.com/taroq4-2/dosos73/actions/runs/41637321085) passed all eight x86-64 debug device jobs on `40fa61478e3f47a1a7413d7c266357e0b7299b61`. These historical results predate this mission's changes. The older [v1.2.0 matrix](device-matrix.json) uses a different source revision and test count. Consult [TEST_EVIDENCE.md](TEST_EVIDENCE.md) for new runs.

[Remediation run 41712135377](https://github.com/taroq4-2/dosos73/actions/runs/41712135377) also passed every API 29–36 x86-64 emulator job on `96337091f968ee36714e1adcb9b215d43191cd77`, and the build job passed. The table below records device-specific limits that persist after both matrices.

The exact signed universal APK from the remediation source was installed as an update and resumed on a local API 35 x86-64 emulator with 4096-byte pages. Six retained files matched their pre-update hashes. This acceptance is independent of the debug instrumentation matrix.

| Android | API | Prior x86-64 emulator CI | Specific source/coverage boundary |
|---|---:|---|---|
| 10 | 29 | Passed | Minimum SDK; MediaStore/SAF and scoped-storage transitions matter. No physical API29 result. |
| 11 | 30 | Passed | SAF and MediaStore instrumentation; provider quirks outside the fixture remain open. |
| 12 | 31 | Passed | Foreground lifecycle and app-visible notifications; not full OEM battery behavior. |
| 12L | 32 | Passed | Device job is not a tablet, hinge or desktop-resize certification. |
| 13 | 33 | Passed | CI grants notification permission for the normal path; permission-denied device journeys are separate. |
| 14 | 34 | Passed | Manifest declares a `dataSync` service and permission. Background restrictions are separate from normal foreground tests. |
| 15 | 35 | Passed | Prior exact signed universal APK and locally signed x86-64 split installs/opening; six retained media hashes matched. No physical acceptance. |
| 16 | 36 | Passed | Target SDK is 36. Real-window landscape component test is not whole-Activity state restoration; physical API36 acceptance remains open. |

| ABI / capability | Artifact evidence | Executed device evidence |
|---|---|---|
| ARM32 | Universal candidate and bundle include the ABI | **NOT VERIFIED** on a physical ARM32 device in this workstream |
| ARM64 | Universal candidate and bundle include the ABI; ELF static checks are available | **NOT VERIFIED** on a physical ARM64 device in this workstream |
| x86 | Universal candidate and bundle include the ABI | **NOT VERIFIED** with a standalone 32-bit x86 runtime |
| x86-64 | Universal candidate, AAB and x86-64 derived split payload were compared | Prior API29–36 CI plus API35 installed acceptance, scoped to the candidate documented in [readiness QA](../../QA_TEST_MATRIX.md) |
| 16 KB runtime | Current candidate ELF audit checked 608 ELFs with zero 64-bit alignment failures | PASS on an API35 x86-64 16 KiB emulator with compatibility disabled. Physical 16 KiB ARM acceptance remains NOT VERIFIED. |

The previous signed candidate source is `e599d58b058006a7c44fec8bd84307b0f68dcf04`, separate from the CI-only follow-up commits. Its local full instrumented run recorded 175 passed, five optional fixture skips and a Python startup timeout; the same focused test passed in isolation. Retain both observations. Prior universal and split install success applies to x86-64 API35, not an ARM or Play installation.

## Commands and remaining device work

Record the build identity and avoid assuming a device's bitness from the host. Run on a disposable emulator or an authorized test device with the correct signing identity:

```sh
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" shell getconf PAGE_SIZE
zipalign -c -P 16 -v 4 "$SIGNED_APK"
python tools/audit_elf_alignment.py "$SIGNED_APK" elf-audit.json
adb -s "$SERIAL" install -r "$SIGNED_APK"
adb -s "$SERIAL" shell am start -W -n com.os73.dosos73/.MainActivity
adb -s "$SERIAL" shell am instrument -w -r com.os73.dosos73.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation requires the matching debug app and instrumentation APK signed by the same key; run it in that installation separately from the signed-release acceptance. Check the Android Developers [16 KB testing guide](https://developer.android.com/guide/practices/page-sizes#test) for a proper emulator image or physical 16 KB mode; `getconf PAGE_SIZE` must report `16384` before a 16 KB runtime test is counted. Test extraction, a local conversion, cancellation and relaunch on that runtime in addition to UI start.

**Open:** physical low-memory ARM and OEM acceptance; clean/upgrade notifications allowed and denied; actual Wi-Fi/mobile handover and long Doze; real file-permission revocation, removable and cloud storage providers; actual Android 16 cutout and large-window behavior; manual TalkBack and Switch Access. Do not merge the rebuilt native runtime into a signed release without validating the exact new artifact on supported ABIs.
