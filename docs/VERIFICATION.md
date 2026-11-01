# dosos731.2.0 — release verification

Prepared1November2026. One signed universal APK for Android10+; this record binds measured checks to the exact delivery rather than to a previous candidate.

## Identity

| Field | Value |
|---|---|
| Application | `com.os73.dosos73` ·1.2.0 / versionCode12 |
| Application source | `72783e39d2704f18722a91040443a51f88adb245` |
| Package | `dosos73-1.2.0-universal.apk` |
| Length | **62,059,289bytes** ·62.06MB /59.18MiB |
| APK SHA-256 | `278c1cdeb6c65ad18180ddeef1fa39f9b024bede0109c6ee1ed78132dcf5402a` |
| Certificate SHA-256 | `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9` |
| Android / CPU | API29+; ARM32, ARM64, x86 and x86_64 in one APK |

The certificate is retained for update compatibility. The APK is not debuggable, backup is disabled, and Debug-only providers/fault hooks are absent. No additional runtime download is required on paste.

## Source and device checks

- **106 JVM tests**, zero failures/errors/skips; Debug/Release lint and minified Release assembly passed.
- [Final source CI](https://github.com/taroq4-2/dosos73/actions/runs/41263479501) passed build and device jobs for **every API29–36**. Each runner reports145 cases; optional external-network and SDK-conditioned skips are included in runner totals. This CI uses x86_64 Debug with shared Python and upstream FFmpeg; final compact media-engine acceptance is separate below.
- Local compact-Debug full run before the last layout-only source change reported145 cases: **140 passed,5 optional cases skipped,0 failures**. The final layout source subsequently passed the full CI matrix. The39 focused durable-rename/journal/deferred-service regressions passed.
- Android10 stress retains105 observer subscriptions and verifies that system-owned request IDs return to a stable baseline. An earlier unpaced burst exhausted the asynchronous system quota; its failure was preserved and the corrected drain-observing test passed across the matrix.
- The60 distinct AI review reports and scoped dispositions are preserved in [review dispositions](launch/REVIEW_DISPOSITIONS.md). They are engineering reviews, not60 human certifications or a promise of zero defects.

[Device matrix](audit/device-matrix.json) · [30-item audit](audit/REVIEW.md) · [remaining requirements](REMAINING_REQUIREMENTS.md)

## Actual signed-APK acceptance

The exact APK above was installed on a **clean API35 x86_64 emulator**, without prior application data. Through its real UI it analyzed/selected/downloaded YouTube720p MP4, converted YouTube audio to MP3V0, and saved a W3C direct MP4. MediaStore publication, file lengths and SHA-256 were read from the resulting files.

| Saved output | Bytes | SHA-256 |
|---|---:|---|
| YouTube720p MP4 |30,812,514|`9b42c9c2785deffc14f4b75edab52fe62a84d4777830525f7fa997051e143af2`|
| YouTube MP3 |7,809,318|`bba662231ad690482a525892f95916b988a7d218e16ec724b8d221bb31543ddb`|
| W3C direct MP4 |4,372,373|`b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`|

Actual video playback was observed in the local preview with different frames and an active/unpaused Android renderer. On a separate update profile, **all6 previously saved media files retained their exact byte counts and hashes** after installing this APK. See [signed acceptance](audit/signed-acceptance.json). The first notification prompt was allowed during clean acceptance; permission-denied task preservation also has scoped service-test coverage.

Large-font navigation/source spacing was inspected at200% on the signed APK and corrected before this delivery. Current screenshots are [documented here](../screenshots/README.md). This visual check is not full TalkBack certification.

## Package and source integrity

The final SBOM validates against CycloneDX1.6 schema:699components, including608ELF records and85Maven build inputs. Assembly checks every CPU target, exactly8Readline/History8.3.6 replacements and642 shared Python files. Repeated unsigned payload assembly from the same inputs is byte-identical. SBOM generation rejects the untransformed APK against the final runtime proof. Debug fault-hook names are absent from Release DEX.

All64-bit ELF PT_LOAD segments meet the checked16KB alignment/congruence gate.32-bit non16KB findings are informational, not hidden; actual physical16KB runtime coverage remains separate. [Artifact checks](audit/artifact-static.json) · [ABI contributions](audit/abi-size.json) · [signature](audit/apk-signature.txt)

The release offers application sources, FFmpeg/LAME sources/configuration and Python native sources/notices separately beside the APK. Exact Readline/GDBM/Mutagen correspondence is checked; inherited CPython producer build-lock provenance remains explicitly limited. Maven archive hashes describe build inputs, not independent attestation of deployed DEX. See [native provenance](launch/NATIVE_SOURCES.md). The Maven OSV coordinate lookup reported no matches at the recorded check time; it does not cover every native/Python advisory or prove absence of vulnerabilities.

## Publication

The release page supplies one universal APK, checksums, source archives, SBOM, runtime/ELF evidence and this record. A **PUBLIC-DOWNLOAD-VERIFICATION.json** receipt is added after downloading the public replacement and checking its exact hash. Older packages are removed only after that check; Git source history is retained.

## Coverage limits

Physical S23Ultra/other ARM/OEM power behavior is left to owner testing, as agreed. Every cloud/removable provider, real ENOSPC, long battery/CPU/heap benchmarks, every codec/source and full assistive-technology coverage are not certified. OS eligibility reminders are inexact and open the app for resume; they do not guarantee silent timed downloads. Multiple simultaneous active files and subtitle selection remain future features. Ambiguous SAF rename responses preserve evidence instead of guessing.
