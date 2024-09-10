# Convex Mobile Libraries

This repo contains code for building mobile libraries for Convex.

The layout is as follows:

1. `rust/` - contains the wrapper around `convex-rs` that exposes types for use via FFI
2. [`android/`](android/) - contains the code for the `android-convexmobile` library
3. `app_for_test/` - a Convex application used for integration tests