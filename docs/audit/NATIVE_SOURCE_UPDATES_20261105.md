# Native source verification — 5 November 2026

The signed validation candidate is **60,851,254 bytes** at SHA-256
`ef2e05196b271fb551cbe88338ecbb3d87ad1de26551b1ab480b188befd1f7e4`.
It retains the existing signing certificate and package identity and is
1,200,128 bytes smaller than the previous signed candidate. The application
code/resources remain those of `96337091f968ee36714e1adcb9b215d43191cd77`.
The two overlays change only the four QuickJS executables, the selected
Cryptodome files, and the compact runtime containers and manifest that hold
them. The APK-bound CycloneDX inventory verifies the complete ELF file list.

| Component | Verified result |
|---|---|
| CPython core | An independent [run 42029898208](https://github.com/taroq4-2/dosos73/actions/runs/42029898208) from the exact commit of [run 41730382903](https://github.com/taroq4-2/dosos73/actions/runs/41730382903) produced the same raw launcher and core ZIP for every ABI. Build logs and outer GitHub ZIPs differ. |
| QuickJS | The inherited April 2025 version falls in the published affected range of [CVE-2025-62491](https://github.com/CVEProject/cvelistV5/blob/main/cves/2025/62xxx/CVE-2025-62491.json) and [CVE-2025-62494](https://github.com/CVEProject/cvelistV5/blob/main/cves/2025/62xxx/CVE-2025-62494.json). The candidate uses the [June 2026 source](https://bellard.org/quickjs/) and an app-specific patch. All four outputs were built with NDK r28c. [Linux producer and Android 10 smoke](https://github.com/taroq4-2/dosos73/actions/runs/42033939945) passed. |
| PyCryptodomex | The candidate updates 3.23.0 to [3.24.1](https://pypi.org/project/pycryptodomex/3.24.1/). All 109 shipped Python paths per ABI and all 164 native extension paths were preserved, including x86 AES-NI/CLMUL extensions. [Linux producer](https://github.com/taroq4-2/dosos73/actions/runs/42033504180) produced identical artifacts across two build directories. The same-host Windows build also reproduced. ELF metadata differs by compiler host. |

The QuickJS executable is an internal application tool. It still accepts the
script and expression invocations used by the pinned yt-dlp and offline probes.
It does not register host modules or the module loader, exposes no
`__loadScript`, and no longer embeds the interactive bytecode REPL. This also
removes the private CLI's trusted REPL bytecode entry; it is not an assertion
that the upstream engine has no bytecode parser defects. See the [upstream
parser report](https://github.com/bellard/quickjs/issues/549).
The paired Android 15 test used the exact bundled EJS code and a synthetic
player fixture. Both signature and throttle transformations succeeded. The
inherited executable read a scratch file and ran a local command; the new
executable blocked host access, static and dynamic std/os imports, local
script imports and native module imports. This local proof is separate from
live platform availability.

The Android crypto probe exercised the selected CPython, bundled yt-dlp and
rebuilt PyCryptodomex together: AES-CBC/GCM, CMAC, Blowfish and RSA passed,
as did ECDSA/EdDSA round trips. The x86-64 emulator exercised software
fallbacks; it did not expose AES-NI/CLMUL for a hardware execution comparison.
The signing timing and encrypted PKCS#8 findings concern the inherited library;
no normal app call path to those specific operations was demonstrated.

The prior Python-only matrix remains historical. The updated
[Android 10–16 matrix](https://github.com/taroq4-2/dosos73/actions/runs/42035105249)
passed packaging and all eight device jobs. It uses separately pinned Linux
outputs and explicitly checks the loaded
Cryptodome version, an AES vector, and restricted QuickJS module access.
The controlled signed APK was installed and opened on Android 15 with
4 KiB pages and with strict 16 KiB pages. The latter disabled both linker
and package-manager compatibility. Six previously saved media fixtures
retained their exact hashes after the 4 KiB upgrade.
Its size and local smoke results do not establish a fresh
on-device performance benchmark.

The first local strict 16 KiB suite ran all 185 tests and recorded a Python
probe timeout and a Downloads empty-state mismatch. Both passed in a clean
focused run, together with the new source-version and module checks (four
tests in total). The clean full-suite retry stopped in Android's
`ReferenceQueueDaemon` during Quick Launch; the stack contained no app frames.
The focused five-test Quick Launch class passed on both the prior and current
candidate under the same strict settings. The cause of the full-suite
interruption has not been established. A final attempt after reboot stopped
before running its first test. Crash logs from the same image also showed ART
crashes in two Google system packages. All full attempts are retained as
failed or interrupted evidence.

The latest signed APK's live YouTube fixture did not complete analysis. The
prior candidate's detailed diagnostic recorded a provider authentication
challenge; the new release UI does not expose that diagnostic cause. There
is no successful live YouTube download result for this candidate.
Public release approval remains open in [remaining issues](REMAINING_ISSUES.md).
