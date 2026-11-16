#!/usr/bin/env bash
# OpenSSL 3.5.9 with the five official 10 November 2026 security backports.
set -euo pipefail
abi=${1:?ABI required}
out=$(realpath -m "${2:?Output directory required}")
tools=$(cd "$(dirname "$0")" && pwd)
ndk=${ANDROID_NDK_HOME:?Pinned Android NDK required}
case "$abi" in
  arm64-v8a) target=android-arm64 ;;
  armeabi-v7a) target=android-arm ;;
  x86) target=android-x86 ;;
  x86_64) target=android-x86_64 ;;
  *) exit 2 ;;
esac
mkdir -p "$out"
python3 - "$tools" "$out" <<'PY'
import hashlib,json,pathlib,tarfile,urllib.request,sys
tools,out=map(pathlib.Path,sys.argv[1:])
manifest=json.loads((tools/'patches/openssl/manifest.json').read_text())
archive=out/'openssl-3.5.9.tar.gz'
if not archive.exists():
    with urllib.request.urlopen('https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz',timeout=90) as r:
        archive.write_bytes(r.read())
assert hashlib.sha256(archive.read_bytes()).hexdigest()==manifest['base_sha256']
for p in manifest['patches']:
    assert hashlib.sha256((tools/'patches/openssl'/f"{p['commit']}.patch").read_bytes()).hexdigest()==p['sha256']
with tarfile.open(archive) as f: f.extractall(out,filter='data')
PY
cd "$out/openssl-3.5.9"
for patch in "$tools"/patches/openssl/*.patch; do git apply --check "$patch"; git apply "$patch"; done
python3 - <<'PY'
from pathlib import Path
p=Path('Configurations/15-android.conf')
s=p.read_text()
old='shared_extension => ".so",'
assert s.count(old)==1
# Match the versioned SONAME required by the existing Python extensions.
p.write_text(s.replace(old, r'shared_extension => ".so.\$(SHLIB_VERSION_NUMBER)",'))
PY
export ANDROID_NDK_ROOT="$ndk"
export PATH="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
# Keep TLS/cipher support. Disable neither QUIC nor CMS to conceal advisories.
./Configure "$target" -D__ANDROID_API__=29 -DNO_SYSLOG -Oz -fPIC \
  -Wl,-z,max-page-size=16384 \
  --prefix=/data/data/com.os73.dosos73/files/runtime/usr \
  shared zlib-dynamic no-ssl no-hw no-srp no-tests enable-tls1_3
make -j3 build_libs
mkdir -p "$out/$abi"
for library in libcrypto.so.3 libssl.so.3; do
  llvm-strip --strip-unneeded -o "$out/$abi/$library" "$library"
  llvm-readelf -d "$out/$abi/$library" > "$out/$abi/$library.dynamic.txt"
  llvm-readelf --dyn-syms --wide "$out/$abi/$library" > "$out/$abi/$library.symbols.txt"
done
perl configdata.pm --dump > "$out/$abi/configuration.txt"
cp "$tools/patches/openssl/manifest.json" "$out/$abi/source-manifest.json"
cd "$out/$abi"
sha256sum libcrypto.so.3 libssl.so.3 > SHA256SUMS.txt
