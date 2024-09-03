# Convex for Android

## Building

This will get you setup to build (and hack on) the convex-mobile library.

1. Ensure you have build tools available on your OS (XCode on Mac, `build-essential` on Linux)
2. Clone https://github.com/dowski/convex-mobile
3. Install Android Studio
4. Use the SDK Manager to install NDK version 27.0.11902837
    1. On Mac it will be installed somewhere like `/Users/$you/Library/Android/sdk/ndk/`
5. Add the following to `~/.cargo/config.toml` (**use your NDK path**)

    ```toml
    [target.aarch64-linux-android]
    linker = "${your NDK x.y.z path}/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android35-clang"
    
    [target.armv7-linux-androideabi]
    linker = "${your NDK x.y.z path}/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi35-clang"
    ```

6. Run `rustup target add armv7-linux-androideabi aarch64-linux-android`
7. Open `convex-mobile/android` in Android Studio (wait for it to sync)
8. Double press Ctrl and type `./gradlew build`
9. You can generate a local Maven installable package by running `./gradlew publishToMavenLocal`