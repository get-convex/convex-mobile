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

build_xcframework() {
  # Builds an XCFramework
  echo "Generating XCFramework"
  rm -rf target/ios  # Delete the output folder so we can regenerate it
  xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/ios-simulator/release/lib$1.a -headers target/uniffi-xcframework-staging \
    -library target/aarch64-apple-darwin/release/lib$1.a -headers target/uniffi-xcframework-staging \
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

cargo build --lib --release --target aarch64-apple-ios-sim
cargo build --lib --release --target aarch64-apple-ios
cargo build --lib --release --target aarch64-apple-darwin

basename=convexmobile
generate_ffi $basename
create_simulator_lib $basename
build_xcframework $basename