# RC5 verification / التحقق من RC5

Date: **2026-10-30**. Application code: **fe09629**; subsequent commits change documentation and screenshots only.

| Property | Verified value |
|---|---|
| Artifact | `dosos73-1.2.0-rc.5-universal.apk` |
| Bytes | **61,944,601** (61.94 MB; 59.07 MiB) |
| SHA-256 | `8d5e740f09d8a60becc74c202738699c27029458184d86680b0016cfb0cc31eb` |
| Certificate SHA-256 | `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9` |
| Package/version | `com.os73.dosos73`, **1.2.0-rc.5 / 11** |
| Support | Android10+, min API29, target35; four CPU architectures in one APK |
| Release properties | R8 minified/resource shrunk; DEBUGGABLE absent; backup disabled; debug fault provider absent; APK v3 signature verified |

## Source and runtime checks

- 72 JVM cases passed, zero failures/errors/skips; debug/release lint and builds passed.
- Local Android15 compact instrumentation: `OK (64 tests)` = **59 executed passes and 5 opt-in network cases skipped**.
- [CI41006565578](https://github.com/taroq4-2/dosos73/actions/runs/41006565578): build and APIs29,30,31,32,33,34,35,36 passed. APIs29/30:58 passes +6 skips each; APIs31–36:59 passes +5 skips each. The additional old-API skip concerns an Android12 resource.
- CI uses actual shared Python packaging with upstream FFmpeg. The compact signed Release is accepted separately on API35 x86_64. These are emulator results, not all-device or all-ABI runtime certification.
- 642 shared regular Python files: all restored bytes/modes verified. Reconstruction of each ABI reproduces its original runtime file set, contents and attributes. Per-ABI symlinks stay in their original ZIPs. Partial-install recovery and idempotence passed.
- All608 native ELF payloads equal RC4 by SHA-256. Zero64-bit PT_LOAD16KB alignment/congruence failures;286 smaller-aligned32-bit entries remain informational. No physical16KB-page device was tested.
- CycloneDX1.6 SBOM validates against the official schema, with unique component references and the exact APK hash. Maven coordinate lookup returned zero OSV advisories at query time; native/Python coverage is explicitly outside that lookup.

## Signed Release acceptance

An independent emulator and test profiles were used; existing profiles/data were not cleared.

- A fresh-profile candidate initialized its bundled runtime, analyzed the user's YouTube link, downloaded and played a720p video and genuine MP3. Direct W3C trailer bytes matched their independent reference.
- The final APK additionally downloaded and saved a **3840×2160 YouTube video,353,323,072bytes**, and an MP3 of7,809,318bytes. This confirms a real large-file path; it is not a universal website guarantee.
- Final4K SHA-256: `974b52f153dbd510dfabbef2d39febe2a316ccddee0615ace0dc675bb46f3517`.
- Final MP3 SHA-256: `bba662231ad690482a525892f95916b988a7d218e16ec724b8d221bb31543ddb`.
- Prior720p SHA-256: `8be0a7bf298a7ac0ee2dea26713a4fca8d8465513779655132ae9d9f974eb4b6` (30,812,514bytes).
- Direct trailer SHA-256: `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254` (4,372,373bytes).
- Screenshot review covered Arabic/English, portrait, landscape and200% text. The short-screen header keeps link controls visible. Font/orientation settings were restored afterward.
- Same-certificate updates retained the existing profiles, language/library data and saved media. The public redownload receipt is supplied as `PUBLIC-DOWNLOAD-VERIFICATION.json` in the release assets after publication.

## Evidence and limits / الأدلة والحدود

[30-item Arabic audit](audit/REVIEW_2026-10-30.md) · [Mind map](REPOSITORY_MAP.md) · [Machine-readable evidence](audit/artifact-static.json) · [Raw device outputs](audit/device-matrix.json).

The APK is smaller without removing ABI/native payloads or adding a first-use engine download. Small startup/memory samples do not establish better battery or memory use; PSS/RSS results are mixed and retained in the audit. Physical Samsung/power-saving/network handover/real ENOSPC, exhaustive SAF-provider coverage and full transitive native-source/license correspondence remain open. TalkBack was not certified on a physical device. OS scheduling, multiple active files and subtitle selection remain in [the requirements ledger](REMAINING_REQUIREMENTS.md).

The unavailable `BaW_jenozKc` fixture timed out and was not counted as success. Earlier CI setup/result-gate failures and one transient draft-navigation test failure were corrected and followed by a successful full matrix; details are retained in the audit.

[Historical RC4 evidence](https://github.com/taroq4-2/dosos73/blob/42c911ec47369113b872c896e1656e5579eba113/docs/VERIFICATION.md) remains available in Git history.
