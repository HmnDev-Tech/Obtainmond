# Agent Guide for Obtainmond

Obtainmond (`io.obtainmond`) is a native Android app written in **Kotlin (Jetpack Compose + Material 3 Expressive)** with a **Rust core engine (`native/obtainmond-core`)** bridged via JNI (`ObtainmondNative`) and bundled privileged IPC backends (**Shevery-API**, **Shizuku**, **Dhizuku**, **Root**).

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before making architectural changes.

## Hard Rules

1. **Don't plagiarize.** Do not copy code wholesale from Obtainium forks or third-party projects without clear compatibility/licensing and explicit discussion with the user. Official Android, Rust crate, Tink, and `apksig` documentation/APIs are fine.
2. **Never embed signing keys, passwords, proxy URLs, or PATs in the repository.**
   - `signing.gradle` must only read external `signing.properties` or environment variables and must fail release builds cleanly when credentials are missing.
   - Store runtime user secrets (GitHub/GitLab PATs, API keys) exclusively via `SecureTokenStore` (Android Keystore + Google Tink).
3. **Preserve ABI boundaries and fail closed on security checks.**
   - APK inspection (`integrity.rs` / `ObtainmondNative`), cryptographic verification (`ApkSignatureVerifier` / `apksig`), and signing-certificate pinning must never silently pass when native libs are absent or mirror downloads are used.
   - Pure parsing, version normalization/comparison, source resolution, APK selection, and ZIP Central Directory checks belong in `native/obtainmond-core/`.
4. **Stage explicitly.** Use `git add <path> ...`; never `git add -A` or `git add .`. Never commit `signing.properties`, keystores (`*.jks`, `*.keystore`), or generated `app/src/main/jniLibs/*.so` binaries if gitignored.
5. **User-facing strings** should live in `app/src/main/res/values/strings.xml` and be accessed via `stringResource(...)` / `context.getString(...)` for i18n readiness.
6. **Follow code style:**
   - Rust: run `cargo fmt --check`, `cargo check`, and `cargo test` under `native/`; avoid `unwrap()` / `panic!()` in JNI paths.
   - Kotlin: follow Compose unidirectional data flow (`StateFlow` + `Dispatchers.IO`), atomic file persistence, and Material 3 Expressive conventions (`TASTE.md`).
7. **Commits and verification:** Report only build, test, and device checks you actually ran after your final edits. Never push, open PRs, or comment on issues unless explicitly asked.

## Build, Test, and Verify

```bash
# Rust core tests & formatting
cargo fmt --manifest-path native/Cargo.toml --check
cargo check --manifest-path native/Cargo.toml
cargo test --manifest-path native/Cargo.toml

# Cross-compile libobtainmond_core.so for all Android ABIs
cd native && cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 --platform 26 -o ../app/src/main/jniLibs build --release -p obtainmond-core && cd ..

# Android unit tests & APK build
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
