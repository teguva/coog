#!/usr/bin/env bash
# Build libcoog_buttplug.so into app/src/main/jniLibs (arm64-v8a).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
export ANDROID_NDK_HOME="$NDK"
OUT="$ROOT/app/src/main/jniLibs"
cd "$(dirname "$0")"
cargo ndk -t arm64-v8a -o "$OUT" build --release
# cargo-ndk also copies dependency cdylibs we don't need / shouldn't load.
rm -f "$OUT"/arm64-v8a/libbuttplug_server*.so
ls -la "$OUT"/arm64-v8a/
