#!/usr/bin/env bash
# Build fortis-bindings for Android and regenerate the Kotlin bindings
# (docs/development §5). Needs: cargo-ndk, Android NDK, rust targets
# aarch64-linux-android + x86_64-linux-android, and fortis-backend cloned
# next to fortis-mobile.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="${FORTIS_BACKEND:-$HERE/../fortis-backend}"
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$HERE/android/local.properties" 2>/dev/null)}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | head -1)}"
APP="$HERE/android/app/src/main"
cd "$BACKEND"
cargo ndk --platform 28 -t arm64-v8a -t x86_64 -o "$APP/jniLibs" build --release -p fortis-bindings
cargo run -q -p fortis-bindings --bin uniffi-bindgen -- generate \
  --library target/aarch64-linux-android/release/libfortis_bindings.so \
  --language kotlin --no-format --out-dir "$APP/java"
ls -la "$APP"/jniLibs/*/libfortis_bindings.so
