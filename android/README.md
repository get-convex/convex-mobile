# Convex for Android

The official Android client for [Convex](https://www.convex.dev/).

Convex is the backend application platform with everything you need to build your product.

This library lets you create Convex applications that run on Android. It builds on the
[Convex Rust client](https://github.com/get-convex/convex-rs) and offers a convenient Android API
for executing queries, actions and mutations.

## Getting Started

If you haven't started a Convex application yet, head over to the
[Convex Android quickstart](https://docs.convex.dev/quickstart/android) to get the basics down. It
will get you up and running with a Convex dev deployment and a basic Android application that
communicates with it using this library.

Also [join us on Discord](https://www.convex.dev/community) to get your questions answered or share
what you're doing with Convex.

## Installation

Add the following to your app's `build.gradle.kts` file:

```kotlin
plugins {
    // ... existing plugins
    kotlin("plugin.serialization") version "1.9.0"
}

dependencies {
    // ... existing dependencies
    implementation("dev.convex:android-convexmobile:0.4.0@aar") {
        isTransitive = true
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

Also ensure that your `AndroidManifest.xml` file has the `INTERNET` permission declared.

```xml
<uses-permission android:name="android.permission.INTERNET"/>
```

## Basic Usage

```kotlin
@Serializable
data class YourData(val foo: String, val bar: @ConvexNum Int)

val client = ConvexClient("your convex deployment URL")

// Use the ConvexClient methods in a CoroutineScope.

// results will contain a Flow that will automatically update when the query data changes
val results: Flow<Result<YourData>> = client.subscribe("your:query", mapOf("someArg" to "someVal"))

client.mutation("your:mutation", mapOf("anotherArg" to "anotherVal", "aNumber" to 42))
```

## Building

Follow along here if you're interested in hacking on the Android client.

### Prerequisites

You'll need a working [Rust installation](https://www.rust-lang.org/tools/install) to build this
library.

### Step by Step

1. Ensure you have build tools available on your OS (XCode on Mac, `build-essential` on Linux)
2. Clone https://github.com/get-convex/convex-mobile
3. Install Android Studio
4. Use the SDK Manager to install NDK version 27.3.13750724
    1. Open android studio. Press shift-shift and search sdk manager
    2. In the SDK tools tab, go to "NDK".
    3. Check the "show package details" box to get more options
    4. Select NDK version 27.3.13750724
    5. On Mac it will be installed somewhere like `/Users/$USER/Library/Android/sdk/ndk/`
6. Add the following to `~/.cargo/config.toml` (**use your NDK path**)

    ```toml
    [target.aarch64-linux-android]
    linker = "${your NDK x.y.z path}/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android35-clang"
    
    [target.armv7-linux-androideabi]
    linker = "${your NDK x.y.z path}/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi35-clang"
    ```

7. Run `rustup target add armv7-linux-androideabi aarch64-linux-android`
8. Open `convex-mobile/android` in Android Studio (wait for it to sync)
9. Double press Ctrl and type `./gradlew build`
10. Set up GPG key/passphrase https://central.sonatype.org/publish/requirements/gpg/
11. Make sure to follow through the instructions, including adding to ~/.gradle/gradle.properties and `gpg --keyserver pgp.mit.edu --send-keys {key}`
12. Set credentials in gradle.properties https://central.sonatype.org/publish/publish-gradle/#credentials
13. You can generate a local Maven installable package by running `./gradlew publishToMavenLocal`
