#!/usr/bin/env bash
# Build Media3 FFmpeg native static libs (ac3/eac3) into the mobile decoder module.
# Output: client-mobile/media3-decoder-ffmpeg/src/main/jni/ffmpeg/android-libs/
#
# Requires: Android NDK, CMake, Ninja, git.
# Usage: ./scripts/build-media3-ffmpeg.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MEDIA="${MEDIA_DIR:-$ROOT/third_party/androidx-media}"
OUT_JNI="$ROOT/client-mobile/media3-decoder-ffmpeg/src/main/jni"
NDK_PATH="${NDK_PATH:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_PATH" ]]; then
  for d in "$HOME/Android/Sdk/ndk"/*; do
    [[ -d "$d" ]] || continue
    NDK_PATH="$d"
  done
fi
[[ -n "$NDK_PATH" && -d "$NDK_PATH" ]] || {
  echo "Set NDK_PATH to an Android NDK (tested ~r26+)" >&2
  exit 1
}

HOST_PLATFORM="${HOST_PLATFORM:-linux-x86_64}"
ANDROID_ABI="${ANDROID_ABI:-21}"
ENABLED_DECODERS=(ac3 eac3)

FFMPEG_MODULE_PATH="$MEDIA/libraries/decoder_ffmpeg/src/main"
JNI="$FFMPEG_MODULE_PATH/jni"

if [[ ! -d "$MEDIA/libraries/decoder_ffmpeg" ]]; then
  echo "Unpack Media3 1.11.0 under $MEDIA first (decoder_ffmpeg sources)" >&2
  exit 1
fi

if [[ ! -f "$JNI/ffmpeg/configure" ]]; then
  echo "Cloning FFmpeg 6.0 into $JNI/ffmpeg …"
  git clone --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$JNI/ffmpeg"
fi

echo "Building FFmpeg ($ENABLED_DECODERS) with NDK=$NDK_PATH"
cd "$JNI"
./build_ffmpeg.sh \
  "$FFMPEG_MODULE_PATH" \
  "$NDK_PATH" \
  "$HOST_PLATFORM" \
  "$ANDROID_ABI" \
  "${ENABLED_DECODERS[@]}"

echo "Syncing libs + headers into $OUT_JNI"
mkdir -p "$OUT_JNI"
cp -a "$JNI/CMakeLists.txt" "$JNI/ffmpeg_jni.cc" "$OUT_JNI/"
mkdir -p "$OUT_JNI/ffmpeg"
rm -rf "$OUT_JNI/ffmpeg/android-libs"
cp -a "$JNI/ffmpeg/android-libs" "$OUT_JNI/ffmpeg/"
cp -f "$JNI/ffmpeg/config.h" "$OUT_JNI/ffmpeg/config.h"
for d in libavcodec libavutil libswresample; do
  rm -rf "$OUT_JNI/ffmpeg/$d"
  mkdir -p "$OUT_JNI/ffmpeg/$d"
  (cd "$JNI/ffmpeg" && find "$d" -name '*.h' -print0 | tar --null -T - -cf -) \
    | (cd "$OUT_JNI/ffmpeg" && tar -xf -)
done

echo "OK — cd client-mobile && ./gradlew :app:assembleDebug"
