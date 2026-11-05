# DOSOS73 Android QuickJS

The app invokes a private Android PIE executable as `libqjs.so --script FILE`. The
builder uses Bellard QuickJS 2026-06-04, the checked `restricted-cli.patch`, Android
NDK r28c and Android API 29. It builds arm64-v8a, armeabi-v7a, x86 and x86_64
without a host bytecode compiler.

The private CLI accepts source files and expressions and exposes the upstream
version in `--help`. It does not register `std` or `os`, install an external
module loader, expose `__loadScript`, or compile the interactive bytecode REPL.
It retains the language features and console output that the bundled YouTube
challenge solver uses. The bytecode restrictions address the app path of the
issues described in [upstream issue 549](https://github.com/bellard/quickjs/issues/549).

On a Linux or Windows x86-64 host with Python 3.12+, curl and GNU patch, run

```sh
python tools/quickjs/build_android.py --abi arm64-v8a \
    --ndk /path/to/android-ndk-r28c --output quickjs-out
```

Use `--source /path/to/quickjs-2026-06-04.tar.xz` to reuse the pinned archive. The
output contains the executable, a source and binary receipt, the static ELF
inspection, the original archive, the exact patch and the upstream MIT license.
The GitHub workflow also records the NDK archive hash and producer commit.

Run the Android smoke with the target ABI executable, the
[pinned EJS 0.8.0 wheel](https://pypi.org/project/yt-dlp-ejs/0.8.0/) and a device
connected to ADB.

```sh
python tools/quickjs/smoke_android.py quickjs-out/libqjs.so \
    yt_dlp_ejs-0.8.0-py3-none-any.whl --serial emulator-5554
```

The smoke runs an offline player fixture through the shipped EJS solver and
checks both signature transforms. The same fixture attempts dynamic imports of
`std`, `os`, a local JavaScript module and a native file. It also checks the
global file helper and a separate static import. The report binds these results
to the exact executable hash and Android API.
