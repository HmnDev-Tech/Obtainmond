# Obtainmond (`io.obtainmond`)

**Obtainmond** is a complete Native Android (**Kotlin + Jetpack Compose Material 3 Expressive**) and **Rust (`libobtainmond_core.so`)** re-architecture of Obtainium with first-class **Shevery-API (`v14.6.4`)** and **Shizuku** privileged zero-touch background auto-updates.

---

## Key Highlights

1. **Rust Native Engine (`native/obtainmond-core`)**:
   - **Smart Primary ABI APK Selector (`apk_selector.rs`)**: Unlike Obtainium (which fails silent updates whenever a release has more than one `.apk` asset), Obtainmond's Rust engine scores all release assets against the currently installed package's ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), device `Build.SUPPORTED_ABIS`, and flavor tokens (`foss`, `gms`, `fdroid`, `universal`) so background auto-updates work automatically with zero user confirmation.
   - **Semantic & Pre-Release Version Engine (`version.rs`)**: High-speed Rust version normalizer, cosmetic tag stripper, and custom regex extractor.
   - **Multi-Source Release Parser (`sources.rs`)**: Native Rust parsers for GitHub Releases, GitLab, Codeberg/Forgejo, F-Droid, IzzyOnDroid, Direct APK endpoints, and HTML scraping.
   - **Streaming SHA-256 & ZIP/ABI Guard (`integrity.rs`)**: Verifies APK ZIP headers, `AndroidManifest.xml`, v2/v3 signing blocks, and `lib/<abi>/*.so` CPU compatibility before privileged installation.

2. **Zero-Touch Background Auto-Updater (`Shevery-API v14.6.4` + `Shizuku`)**:
   - Powered by [`Shevery-API v14.6.4`](https://github.com/HmnDev-Tech/Shevery-API) (`SheveryPrivilege.connectFast()` + flat-Parcel `SheveryPrivilege.execFast()`) while retaining full `rikka.shizuku.Shizuku` compatibility.
   - Automatically whitelists `io.obtainmond` from Android Doze / Battery Optimization (`cmd deviceidle whitelist +io.obtainmond`, `cmd appops set io.obtainmond RUN_ANY_IN_BACKGROUND allow`, `am set-standby-bucket io.obtainmond active`) and silently updates tracked apps in the background without opening the app.

3. **Material 3 Expressive UI (`Jetpack Compose`)**:
   - Built with `MaterialExpressiveTheme` (`MotionScheme.expressive()`), floating pill toolbar (`ExpressiveFloatingNavigationBar`), spring physics cards, Dynamic Color, Pure Black OLED mode, and 10-sample AGSL directional scroll motion blur (`MotionBlur.kt`).

4. **Obtainmond Labs**:
   - **Instant Rollback Time-Machine**: Snapshots the installed `base.apk` before every silent update for 1-click privileged downgrade (`pm install -r -d`).
   - **Post-Install ART Speed-Profile Compiler**: Runs `cmd package compile -m speed-profile -f <pkg>` immediately after silent updates.
   - **Installer Source Spoofer**: Spoofs installer package (`com.android.vending`, `com.sec.android.app.samsungapps`, `org.fdroid.fdroid`, or `io.obtainmond`).
   - **Privileged App Freezer**: Suspend/unsuspend any tracked app (`pm suspend` / `pm unsuspend`).
   - **GitHub Mirror Acceleration & Token Pool**: High-speed release asset mirror + PAT support.

5. **Unified Signing for Debug & Release (`io.obtainmond`)**:
   - Both `debug` and `release` variants use `applicationId "io.obtainmond"` and share the exact same signing configuration (`signing.gradle`), allowing seamless installation of debug over release (and vice-versa) without uninstalling.
