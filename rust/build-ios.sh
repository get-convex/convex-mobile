#!/usr/bin/env zsh

# This file is a modified copy of the original found at https://github.com/ianthetechie/uniffi-starter.

set -e
set -u

# NOTE: You MUST run this every time you make changes to the core. Unfortunately, calling this from Xcode directly
# does not work so well.

# In release mode, we create a ZIP archive of the xcframework and update Package.swift with the computed checksum.
# This is only needed when cutting a new release, not for local development.
release=false

for arg in "$@"
do
    case $arg in
        --release)
            release=true
            shift # Remove --release from processing
            ;;
        *)
            shift # Ignore other argument from processing
            ;;
    esac
done


simulator_lib_dir="target/ios-simulator/release"
catalyst_lib_dir="target/ios-catalyst/release"

generate_ffi() {
  echo "Generating framework module mapping and FFI bindings"
  cargo run --features=uniffi/cli --bin uniffi-bindgen generate --library target/aarch64-apple-ios/release/lib$1.dylib --language swift --out-dir target/uniffi-xcframework-staging
  mkdir -p ../ios/Sources/UniFFI/
  mv target/uniffi-xcframework-staging/*.swift ../ios/Sources/UniFFI/
  mv target/uniffi-xcframework-staging/$1FFI.modulemap target/uniffi-xcframework-staging/module.modulemap  # Convention requires this have a specific name
}

create_simulator_lib() {
  echo "Creating a library for aarch64 simulator"
  mkdir -p $simulator_lib_dir
  lipo -create target/aarch64-apple-ios-sim/release/lib$1.a -output $simulator_lib_dir/lib$1.a
}

create_catalyst_lib() {
  echo "Creating a library for Mac Catalyst"
  mkdir -p $catalyst_lib_dir
  cp target/aarch64-apple-ios-macabi/release/lib$1.a $catalyst_lib_dir/lib$1.a
}

build_xcframework() {
  # Builds an XCFramework
  echo "Generating XCFramework"
  rm -rf target/ios  # Delete the output folder so we can regenerate it
  xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/ios-simulator/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/aarch64-apple-darwin/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library $catalyst_lib_dir/lib$1.a -headers target/uniffi-xcframework-staging \
    -output target/ios/lib$1-rs.xcframework
  cp -R target/ios/lib$1-rs.xcframework ../ios

  if $release; then
    echo "Building xcframework archive"
    zip -r target/ios/lib$1-rs.xcframework.zip target/ios/lib$1-rs.xcframework
    checksum=$(swift package compute-checksum target/ios/lib$1-rs.xcframework.zip)
    version=$(cargo metadata --format-version 1 | jq -r '.packages[] | select(.name=="foobar") .version')
    sed -i "" -E "s/(let releaseTag = \")[^\"]+(\")/\1$version\2/g" ../ios/Package.swift
    sed -i "" -E "s/(let releaseChecksum = \")[^\"]+(\")/\1$checksum\2/g" ../ios/Package.swift
  fi
}

# IPHONEOS_DEPLOYMENT_TARGET=16.0 is required to avoid a linker error when
# aws-lc-sys Kyber PQC objects (compiled against iOS 26.x SDK) reference
# ___chkstk_darwin, a stack guard symbol not present before iOS 13.
IPHONEOS_DEPLOYMENT_TARGET=16.0 cargo build --lib --release --target aarch64-apple-ios-sim
IPHONEOS_DEPLOYMENT_TARGET=16.0 cargo build --lib --release --target aarch64-apple-ios
cargo build --lib --release --target aarch64-apple-darwin

# Mac Catalyst requires two workarounds:
#
# 1. catalyst-toolchain.cmake forces CMake (used by aws-lc-sys) to use the macOS
#    SDK rather than the iPhoneOS SDK, which is required for Catalyst targets.
#
# 2. Rust 1.94+ sets both CARGO_CFG_TARGET_ENV and CARGO_CFG_TARGET_ABI to
#    "macabi" for aarch64-apple-ios-macabi. cc-rs concatenates both, producing
#    the invalid Clang target --target=arm64-apple-ios{ver}-macabimacabi.
#    clang-catalyst-wrapper.sh rewrites "-macabimacabi" → "-macabi" before
#    forwarding the args to the real clang.
#
# IPHONEOS_DEPLOYMENT_TARGET must NOT be set here; Catalyst uses the macOS SDK
# and the deployment target is embedded in the --target flag by cc-rs.
CMAKE_TOOLCHAIN_FILE="$(pwd)/catalyst-toolchain.cmake" \
  CC_aarch64_apple_ios_macabi="$(pwd)/clang-catalyst-wrapper.sh" \
  CFLAGS="-Wno-unused-command-line-argument" \
  cargo build --lib --release --target aarch64-apple-ios-macabi

basename=convexmobile
generate_ffi $basename
create_simulator_lib $basename
create_catalyst_lib $basename
build_xcframework $basename