# Native sources and Readline replacement evidence

Prepared 2026-10-31. This is an artifact-bound engineering evidence record, not a legal certification. No APK/AAR, application code, signing key, emulator or publication was changed by this review.

## Status and scope

RC5 APK SHA-256: `8d5e740f09d8a60becc74c202738699c27029458184d86680b0016cfb0cc31eb`. The existing RC5 Readline/History patch level is **not established**. The proposed replacement is official Termux Readline **8.3.6**, retaining functionality, with all four ABI payloads collected separately. The candidate requires integration, runtime acceptance and source/notice delivery before its evidence can apply to a new APK. RC5 has not been retrospectively cleared.

The evidence directory is `work/launch-review/license-correspondence` at the task workspace root. `readline-deb-inputs.json` is the integration contract: official DEB URL/hash and the two exact member paths, destination relative paths and ELF hashes for each ABI. `readline-verified/manifest.json` and its per-ABI manifests contain static compatibility results. A source-only ZIP and its SHA-256 manifest are prepared in that evidence directory; nothing was uploaded.

## Proven binary provenance

- All **564 Python-runtime ELF files** in the actual RC5 APK (140/140/142/142 by ABI) match the cached Maven `io.github.junkfood02.youtubedl-android:library:0.18.1` AAR byte for byte. The split/common reconstruction was read without executing a runtime. Evidence: `apk-to-maven-python-correspondence.json`.
- All **12 upstream Python ZIP/launcher/QuickJS blobs** in that cached AAR match Git blob hashes at wrapper tag `0.18.1`, commit `d725d5c9a18c3a99a13ee0308bf78275dc310760`. Evidence: `maven-to-wrapper-tag-correspondence.json`. This proves binary origin, not the source of every binary.
- Source-only Maven Central `library`, `common`, and `ffmpeg` `0.18.1-sources.jar` files and cached POMs are collected. Their official Maven SHA-1 checks match; local SHA-256 values are recorded. Their entries contain no vendor ZIP, native binary, AAR, APK or class file. Evidence: `maven-sources-manifest.json`.

Official wrapper source: [pinned tag](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760). [BUILD_PYTHON.md](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_PYTHON.md) is historical guidance using Python 3.7.2 and an unpinned Termux checkout; it is **not** an exact build lock for shipped Python 3.12.11.

## Copyleft components actually identified in Python

| Component | Evidence beyond filename | Source/notice material and remaining limit |
|---|---|---|
| GNU Readline and History, GPL-3.0-or-later | GNU-specific exports, `8.3` embedded version, Python extension DT_NEEDED on libreadline.so.8. Source headers explicitly permit GPLv3 or later. | RC5 patch level remains unknown. Candidate replaces both ELF libraries in each ABI with official DEB version **8.3.6**. GNU base source + patches 001–006 + Termux patch/configuration and complete pinned build repository are collected. |
| GNU GDBM and compatibility library, GPL-3.0-or-later | `GDBM version 1.26 ... built Aug 24 2025` string, API exports, _gdbm/_dbm linkage. | All **8 shipped library ELF files** match official Termux `gdbm_1.26-1` DEBs byte for byte. GNU GDBM 1.26 source hash matches the pinned Termux recipe. No GDBM-local patches occur in the historical package directory. Complete Termux snapshot is supplied, but the original producer did not provide an explicit global build-commit attestation. |
| Mutagen 1.47.0, GPL-2.0-or-later | `version=(1,47,0)` and GPL headers in shipped Python files. | All **57 shipped .py files** match the official PyPI source distribution byte for byte. Source tarball SHA-256 matches official PyPI metadata. COPYING/AUTHORS and sources are collected. |

The GPL wrapper is separately declared in its Maven POMs and pinned LICENSE. First-party code is GPL-3.0-or-later. The CPython readline/_gdbm/_dbm adapters have PSF source headers; their linkage to the above libraries does not make the libraries' source obligations disappear.

**No additional LGPL library was established from this Python payload.** In particular, XZ 5.8.1's own `COPYING` says **liblzma is 0BSD**. The Termux package's composite GPL/LGPL labels cover other XZ tools/build files and must not be assigned to liblzma just from package metadata. `liblzma.so` contains version 5.8.1; the upstream source and license are supplied. This is component-level evidence, not a claim that arbitrary static contents were exhaustively reverse engineered.

## Readline 8.3.6 candidate

Official package directory: [Termux Readline DEBs](https://packages.termux.dev/apt/termux-main/pool/main/r/readline/). Recipe commit: [`d005fcc8961ca61cd3715555526c324e3d0e68e4`](https://github.com/termux/termux-packages/commit/d005fcc8961ca61cd3715555526c324e3d0e68e4), the Readline 8.3.6 update preceding the official package directory timestamp. The complete recipe repository, `packages/readline/build.sh`, `rlconf.h.patch`, inputrc, GNU Readline 8.3 source and all six GNU patches are retained. Every source/patch digest matches the pinned recipe. Full GNU COPYING and copyright headers are included in the source archive.

| ABI | Official DEB SHA-256 |
|---|---|
| arm64-v8a / aarch64 | `616cc73009bc0223f61caed8dd09455714455aa5ea5e180126e2f69c4c775136` |
| armeabi-v7a / arm | `4b3bdacdbee97503a9062a81e5e460ff5c4f3b35fed4b1cb3a612c5b8c79e64f` |
| x86 / i686 | `137aa813623a11717f19b32c24fce0f2d016b12a440bc52ab8b5f3f7c55c6363` |
| x86_64 | `e9684798d13ede05f785315f68d3ff739563db2abef0e73db4dc9b8e17ded368` |

Candidate files: `readline-verified/<ABI>/usr/lib/libreadline.so.8.3` and `libhistory.so.8.3`. Preserve the existing libreadline.so/.so.8 and libhistory.so/.so.8 symlink entries. No symlink was created or changed on the host. Each new ELF is extracted from the hashed official package without running its maintainer scripts.

Static results on every ABI: both SONAMEs unchanged; all previous exports preserved; all **49** Readline symbols required by the current `readline.cpython-312.so` are exported; no new non-weak undefined symbols; unchanged DT_NEEDED. Both libraries' Android notes specify **API24**. ARM64 and x86_64 PT_LOAD segments pass the 16KB alignment/congruence check. These properties support integration; they do not prove runtime correctness.

Required acceptance after integration: verify the new APK contains exactly these 8 ELF hashes and preserves symlinks; test import readline, its GNU identity/version and history add/get/clear operations on APIs29–36; run the declared extraction, video/audio, MP3 and application regression matrix. Do not publish an untransformed developer APK as the accepted artifact.

## DT_NEEDED closure and optional-module assessment

`python-needed-closure.json` covers **572 ELF files**, including Python launchers and QuickJS: 142/142/144/144 by ABI. Every NEEDED library resolves to a shipped ELF/symlink except Android libc.so, libm.so and libdl.so. The report is a static DT_NEEDED graph; dynamic Python imports and dlopen require separate runtime validation.

For each ABI the only incoming GDBM/Readline edges are `_dbm.cpython-312.so -> libgdbm_compat.so -> libgdbm.so`, `_gdbm.cpython-312.so -> libgdbm.so`, and `readline.cpython-312.so -> libreadline.so.8`. libhistory has no DT_NEEDED incoming edge. GDBM's package-level dependency on readline includes command-line tooling that is absent from the runtime; the shipped GDBM library itself has no DT_NEEDED edge to Readline.

The pinned yt-dlp ZIP SHA-256 is `b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14`. Static source scanning found no import of readline, gdbm, dbm or shelve in it or in the shipped site-packages. Occurrences of `.readline()` are stream methods. yt-dlp does import Mutagen for optional thumbnail postprocessing. The application uses Python `-u -c` with runpy and exposes no interactive Python shell. This supports an optional-module investigation only; it does **not** establish lossless removal. No module was removed, and the proposed replacement retains all functions.

## Permissive notices and source limits

The source bundle includes upstream notices for CPython/PSF, QuickJS 2025-04-26/MIT, Expat 2.7.3/MIT, OpenSSL 3.5.2/Apache-2.0, zlib 1.3.1, bzip2 1.0.8, ncurses 6.5.20240831, libffi, Termux libandroid-support/wcwidth, libandroid-posix-semaphore, libcrypt, SQLite 3.50.4, XZ/liblzma 5.8.1/0BSD, and LLVM libc++/libunwind notices. The corresponding official URLs, retrieval hashes and source recipes are retained. Version strings independently identify Python, QuickJS, Expat, OpenSSL, zlib, bzip2, ncurses, SQLite, liblzma, GDBM and Readline's base version. For libraries without a reliable embedded release string, the historical Termux recipe is a **candidate provenance source**, not proof of exact version. LLVM notices were retrieved from the compiler's embedded Android LLVM commit; they are not a byte-match proof for libc++.

The full Python 3.12.11 source archive and a historical Termux build repository snapshot (`7c74f85f3b36991d07d4cfb0d1be458c1b4f0896`) contain the candidate Python patches/build controls. Build flags in the actual `_sysconfigdata__linux_.py` confirm Termux prefix, Android target, NDK r28c and readline/gdbm linkage. An exact producer lock/patch attestation for the original Python build is still absent; the historical snapshot is not mislabeled as that lock.

Source/notice distribution must be bound to the accepted APK and offered with clear directions beside its download. Local preparation does not prove public availability. The source-only bundle excludes all cached AARs, official DEBs, replacement ELF files and the wrapper's old native vendor ZIPs. FFmpeg/LAME sources and configurations are already provided in their separate full-source archive and remain independently applicable.
