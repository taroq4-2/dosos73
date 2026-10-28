#!/usr/bin/env bash
# Experimental build only. Do not replace production until the feature matrix passes.
set -euo pipefail
abi="$1"
extra_options=()
# Android x86 PIE cannot link the absolute relocations in this upstream assembly.
if [ "$abi" = x86 ]; then extra_options+=(--disable-x86asm); fi
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
mkdir -p native-build
cd native-build
if [ ! -d ffmpeg/.git ]; then git clone --filter=blob:none --no-checkout https://github.com/FFmpeg/FFmpeg.git ffmpeg; fi
cd ffmpeg
git checkout --detach 946fcce07b6dcd0331c8cc609192aeff5e1924f8
./configure "${extra_options[@]}" --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
  --cc="$compiler/${triple}29-clang" --cxx="$compiler/${triple}29-clang++" \
  --ar="$compiler/llvm-ar" --ranlib="$compiler/llvm-ranlib" --strip="$compiler/llvm-strip" \
  --enable-pic --enable-small --disable-debug --disable-doc --disable-autodetect \
  --enable-shared --disable-static --disable-avdevice --disable-network \
  --disable-everything --enable-ffmpeg --enable-ffprobe \
  --enable-protocol=file,pipe --enable-demuxers --enable-muxers --enable-parsers --enable-bsfs \
  --enable-decoder=h264,hevc,vp8,vp9,aac,opus,vorbis,mp3,flac,pcm_s16le,pcm_s24le,pcm_s32le,subrip,webvtt \
  --enable-encoder=aac,flac,pcm_s16le,subrip,webvtt \
  --enable-filter=aresample,aformat,anull,null,format,scale \
  --extra-ldflags='-Wl,-z,max-page-size=16384 -Wl,--gc-sections' \
  --extra-cflags='-Oz -ffunction-sections -fdata-sections'
make -j2 ffmpeg ffprobe
mkdir -p "$root/native-out/$abi"
cp ffmpeg ffprobe config.h ffbuild/config.mak COPYING.LGPLv2.1 LICENSE.md "$root/native-out/$abi/"
mkdir -p "$root/native-out/$abi/usr/lib"
find . -type f -name 'lib*.so' -exec cp '{}' "$root/native-out/$abi/usr/lib/" ";"
"$compiler/llvm-strip" "$root/native-out/$abi/ffmpeg" "$root/native-out/$abi/ffprobe" "$root/native-out/$abi/usr/lib/"*.so
"$compiler/llvm-readelf" -l -d "$root/native-out/$abi/ffmpeg" > "$root/native-out/$abi/elf-audit.txt"
cd "$root/native-out/$abi"
sha256sum ffmpeg ffprobe > SHA256SUMS.txt

