# Media response recovery in 1.3.0-rc.3

The previous signed candidate downloaded a verified YouTube MP4 with audio.
The live MP3 attempts encountered a format discovery failure and a provider
verification prompt. A diagnostic run on the matching debug runtime recorded
incomplete YouTube player and API responses, followed by an image-only format
list and the terminal `Requested format is not available` error.

Application commit `c2f774756ec6167aabf1bfd5cf51a4ce70e395bd` recognizes the
combined failure pattern as an interrupted source response. Format discovery
gets one additional attempt after a short cancellable delay. A transfer can
use that attempt while its private staging directory is empty. The retry
uses a fresh process with the same request and format selector. Authentication
and HTTP 403/429 signals suppress this retry. A genuine missing format keeps
its existing result.

The tests cover recognition of the observed native output, the two-attempt
limit, cancellation during the delay, staged files, and authentication.
The Android fixture runs two separate Python processes after injecting a
controlled failure and checks that both processes received the same format
identifier.

The signed universal APK contains **60,810,294 bytes** and has SHA-256
`3aadf773ef0f9f84e89a0a377ae37d96fb1be042f46173b6754d0334b21bd80f`.
It carries version code 16 and the existing signing certificate. Comparison
against RC.2 found identical bytes in all 608 ELF entries. The four repacked
FFmpeg ZIP files had different compression sizes and identical contained
file bytes and permissions.

The local JVM suite passed 125 tests and release lint passed. On the Android
16 x86-64 emulator with strict 16 KiB pages, the matching instrumentation
suite reported `OK (186 tests)`. A separate opt-in public media test passed
through the bundled extractor. The exact signed APK installed and opened
with the expected SHA-256.

On that signed installation, one live YouTube MP3 test saved 1,483,292 bytes
with SHA-256 `d43b891529229ff5233340a7b2f32c78c13a23fa5b0689aed47c108c1cf777e2`.
The file contained 2,178 MPEG Layer III frames and ran for approximately
52.27 seconds. The bundled FFmpeg decoded the complete file. A separate
live MP4 test saved 12,051,617 bytes with 1920 × 818 video and audio tracks.
The controlled retry fixture establishes the recovery behavior. The live
tests establish successful downloads for this fixture on the test connection.

The [source runtime matrix](https://github.com/taroq4-2/dosos73/actions/runs/42102988681)
passed Android 10–16. The first regular build reached a stale bundle check
expecting RC.2. Commit `15842f693554c8b416c83ad6edbd7273dbba83e3` updated its
expected version to RC.3, and the [corrected run](https://github.com/taroq4-2/dosos73/actions/runs/42104437149)
passed packaging and all eight device jobs. Physical Android device
acceptance remains open.
