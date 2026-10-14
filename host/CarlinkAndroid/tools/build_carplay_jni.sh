#!/bin/sh
set -eu

if [ "$#" -ne 5 ]; then
    echo "usage: $0 CARGO_TARGET_DIR OUTPUT_SO NDK_BIN CARGO FDK_AAC_PREFIX" >&2
    exit 2
fi

cargo_target_dir=$1
output_so=$2
ndk_bin=$3
cargo=$4
fdk_prefix=$5
native_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../gm_ccpa/native/carplay-jni" && pwd)

mkdir -p "$(dirname -- "$output_so")"
(
    cd "$native_dir"
    CARGO_TARGET_DIR="$cargo_target_dir" \
    FDK_AAC_PREFIX="$fdk_prefix" \
    CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android32-clang" \
    AR_x86_64_linux_android="$ndk_bin/llvm-ar" \
    CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android32-clang" \
    PATH="$(dirname -- "$cargo"):$PATH" \
        "$cargo" build --release --target x86_64-linux-android
)

built_so="$cargo_target_dir/x86_64-linux-android/release/libcarplayjni.so"
if [ ! -f "$built_so" ]; then
    echo "Cargo succeeded but did not produce $built_so" >&2
    exit 1
fi
cp "$built_so" "$output_so"
