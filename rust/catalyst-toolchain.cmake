# catalyst-toolchain.cmake
# Toolchain file for Mac Catalyst (aarch64-apple-ios-macabi).
#
# aws-lc-sys uses CMake internally and incorrectly selects the iPhoneOS SDK
# for Catalyst targets. This toolchain forces it to use the macOS SDK, which
# is what Catalyst builds require.
#
# Additionally, Rust 1.94+ sets both target_env and target_abi to "macabi",
# causing cc-rs to generate --target=arm64-apple-ios{ver}-macabimacabi (invalid).
# We use a compiler wrapper to fix this bad --target flag before passing it to
# clang.

set(CMAKE_SYSTEM_NAME Darwin)
set(CMAKE_SYSTEM_PROCESSOR arm64)
set(CMAKE_OSX_ARCHITECTURES arm64)

# Use the macOS SDK explicitly
execute_process(
    COMMAND xcrun --sdk macosx --show-sdk-path
    OUTPUT_VARIABLE MACOS_SDK_PATH
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
set(CMAKE_OSX_SYSROOT "${MACOS_SDK_PATH}")

# Prevent CMake from injecting its own arch/sysroot flags
set(CMAKE_OSX_DEPLOYMENT_TARGET "" CACHE STRING "")
set(CMAKE_C_COMPILER_TARGET "")
set(CMAKE_CXX_COMPILER_TARGET "")

# Use a wrapper compiler to fix the doubled -macabi in --target flags.
# The wrapper is placed next to this toolchain file by build-ios.sh.
get_filename_component(TOOLCHAIN_DIR "${CMAKE_CURRENT_LIST_FILE}" DIRECTORY)
set(CMAKE_C_COMPILER "${TOOLCHAIN_DIR}/clang-catalyst-wrapper.sh")
set(CMAKE_CXX_COMPILER "${TOOLCHAIN_DIR}/clang-catalyst-wrapper.sh")
