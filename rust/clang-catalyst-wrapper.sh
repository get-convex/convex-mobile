#!/bin/bash
# clang-catalyst-wrapper.sh
#
# Workaround for a Rust 1.94.0 regression: rustc now sets both target_env and
# target_abi to "macabi" for the aarch64-apple-ios-macabi target. This causes
# cc-rs to generate an invalid --target flag:
#
#   --target=arm64-apple-ios{ver}-macabimacabi  (invalid - doubled suffix)
#
# This wrapper replaces "-macabimacabi" with "-macabi" before passing all flags
# to the real clang. It is used as CMAKE_C_COMPILER in catalyst-toolchain.cmake
# so that aws-lc-sys's CMake build also sees the corrected triple.

args=()
for arg in "$@"; do
    fixed="${arg//-macabimacabi/-macabi}"
    args+=("$fixed")
done
exec clang "${args[@]}"
