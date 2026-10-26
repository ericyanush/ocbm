#!/bin/sh
set -eu

if [ "$#" -ne 6 ]; then
    echo "usage: $0 CARGO_TARGET_DIR OUTPUT_JNILIBS_DIR NDK_BIN CARGO FDK_X86_PREFIX FDK_ARM64_PREFIX" >&2
    exit 2
fi

cargo_target_dir=$1
output_dir=$2
ndk_bin=$3
cargo=$4
fdk_x86_prefix=$5
fdk_arm64_prefix=$6
native_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../gm_ccpa/native/carplay-jni" && pwd)
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)

if [ ! -f "$fdk_x86_prefix/lib/libfdk-aac.a" ]; then
    echo "missing x86_64 Android libfdk-aac: $fdk_x86_prefix/lib/libfdk-aac.a" >&2
    exit 1
fi
if [ ! -f "$fdk_arm64_prefix/lib/libfdk-aac.a" ]; then
    echo "building arm64 Android libfdk-aac (first build only)" >&2
    ANDROID_NDK_HOME="$(CDPATH= cd -- "$ndk_bin/../../../../.." && pwd)" \
        ANDROID_API=32 "$repo_root/pi/tools/build_fdk_aac_arm64.sh"
fi
if [ ! -f "$fdk_arm64_prefix/lib/libfdk-aac.a" ]; then
    echo "missing arm64 Android libfdk-aac: $fdk_arm64_prefix/lib/libfdk-aac.a" >&2
    exit 1
fi

build_abi() {
    abi=$1
    target=$2
    fdk_prefix=$3
    mkdir -p "$output_dir/$abi"
    (
        cd "$native_dir"
        CARGO_TARGET_DIR="$cargo_target_dir" \
        FDK_AAC_PREFIX="$fdk_prefix" \
        CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android32-clang" \
        CC_aarch64_linux_android="$ndk_bin/aarch64-linux-android32-clang" \
        AR_x86_64_linux_android="$ndk_bin/llvm-ar" \
        AR_aarch64_linux_android="$ndk_bin/llvm-ar" \
        CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android32-clang" \
        CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android32-clang" \
        PATH="$(dirname -- "$cargo"):$PATH" \
            "$cargo" build --release --target "$target"
    )
    built_so="$cargo_target_dir/$target/release/libcarplayjni.so"
    if [ ! -f "$built_so" ]; then
        echo "Cargo succeeded but did not produce $built_so" >&2
        exit 1
    fi
    cp "$built_so" "$output_dir/$abi/libcarplayjni.so"
}

build_abi x86_64 x86_64-linux-android "$fdk_x86_prefix"
build_abi arm64-v8a aarch64-linux-android "$fdk_arm64_prefix"
