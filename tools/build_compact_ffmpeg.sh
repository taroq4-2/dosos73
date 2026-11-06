#!/usr/bin/env bash
# Experimental build only. Do not replace production until the feature matrix passes.
set -euo pipefail
abi="$1"
extra_options=()
# Android x86 PIE cannot link the absolute relocations in this upstream assembly.
if [ "$abi" = x86 ]; then extra_options+=(--disable-x86asm --disable-inline-asm); fi
case "$abi" in
  arm64-v8a) arch=aarch64; triple=aarch64-linux-android; cpu=armv8-a ;;
  armeabi-v7a) arch=arm; triple=armv7a-linux-androideabi; cpu=armv7-a ;;
  x86) arch=x86; triple=i686-linux-android; cpu=i686 ;;
  x86_64) arch=x86_64; triple=x86_64-linux-android; cpu=x86-64 ;;
  *) exit 2 ;;
esac
: "${ANDROID_NDK_HOME:?NDK required}"
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
root="$PWD"
patch_dir="$root/tools/patches/ffmpeg"
mkdir -p native-build
cd native-build
if [ ! -d ffmpeg/.git ]; then git clone --filter=blob:none --no-checkout https://github.com/FFmpeg/FFmpeg.git ffmpeg; fi
# LAME 3.100, official release archive; fail closed if mirror returns HTML or changes.
lame_prefix="$root/native-build/lame-install-$abi"
if [ ! -f lame-3.100.tar.gz ]; then
  curl --fail --location --retry 3 https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz -o lame-3.100.tar.gz
fi
echo 'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e  lame-3.100.tar.gz' | sha256sum --check
mkdir -p "lame-$abi"
tar -xzf lame-3.100.tar.gz -C "lame-$abi" --strip-components=1
cd "lame-$abi"
CC="$compiler/${triple}29-clang" AR="$compiler/llvm-ar" RANLIB="$compiler/llvm-ranlib" \
 CFLAGS='-Oz -fPIC -ffunction-sections -fdata-sections' \
 ./configure --host="$triple" --prefix="$lame_prefix" --disable-shared --enable-static \
 --disable-frontend --disable-decoder --disable-gtktest --with-pic
make -j2
make install
cd "$root/native-build"
git -C ffmpeg worktree add --detach "$root/native-build/ffmpeg-$abi" 946fcce07b6dcd0331c8cc609192aeff5e1924f8
cd "ffmpeg-$abi"
(cd "$patch_dir" && sha256sum --check SHA256SUMS.txt)
git apply "$patch_dir/969c7eb091989a664cb7bf66a1f02051cc46cb52.patch"
# The companion API was added on master. Keep the pinned branch's version header.
git apply --exclude=doc/APIchanges --exclude=libavutil/version.h \
  "$patch_dir/d1434518fbd03789e3e5dc0657a7a9f1123ce074.patch"
git apply "$patch_dir/71403a21b02a92b925e26e609e9bd3e48df451cf.patch"
git apply "$patch_dir/2c2f6e96e31795ae95a8f8323a493ffbc493111f.patch"
./configure "${extra_options[@]}" --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
  --cc="$compiler/${triple}29-clang" --cxx="$compiler/${triple}29-clang++" \
  --ar="$compiler/llvm-ar" --ranlib="$compiler/llvm-ranlib" --strip="$compiler/llvm-strip" \
  --enable-pic --enable-small --disable-debug --disable-doc --disable-autodetect \
  --enable-shared --disable-static --disable-avdevice --disable-network \
  --disable-everything --enable-ffmpeg --enable-ffprobe \
  --enable-protocol=file,pipe --enable-demuxers --enable-muxers --enable-parsers --enable-bsfs \
  --enable-decoder=h264,hevc,vp8,vp9,aac,opus,vorbis,mp3,flac,pcm_s16le,pcm_s24le,pcm_s32le,subrip,webvtt \
  --enable-libmp3lame --enable-encoder=aac,flac,pcm_s16le,subrip,webvtt,libmp3lame \
  --enable-filter=aresample,aformat,anull,null,format,scale \
  --extra-ldflags="-L$lame_prefix/lib -Wl,-z,max-page-size=16384 -Wl,--gc-sections" \
  --extra-cflags="-I$lame_prefix/include -Oz -ffunction-sections -fdata-sections"
make -j2 ffmpeg ffprobe
mkdir -p "$root/native-out/$abi"
cp ffmpeg ffprobe config.h ffbuild/config.mak COPYING.LGPLv2.1 LICENSE.md "$root/native-out/$abi/"
cp "$patch_dir/SHA256SUMS.txt" "$root/native-out/$abi/FFMPEG_SECURITY_PATCHES.txt"
cp "$root/native-build/lame-$abi/COPYING" "$root/native-out/$abi/COPYING.LAME"
mkdir -p "$root/native-out/$abi/usr/lib"
while IFS= read -r lib; do
  name="$(basename "$lib")"
  cp "$lib" "$root/native-out/$abi/usr/lib/${name%%.so*}.so"
done < <(find . -type f -name 'lib*.so*')
"$compiler/llvm-strip" "$root/native-out/$abi/ffmpeg" "$root/native-out/$abi/ffprobe" "$root/native-out/$abi/usr/lib/"*.so*
"$compiler/llvm-readelf" -l -d "$root/native-out/$abi/ffmpeg" > "$root/native-out/$abi/elf-audit.txt"
cd "$root/native-out/$abi"
sha256sum ffmpeg ffprobe > SHA256SUMS.txt

