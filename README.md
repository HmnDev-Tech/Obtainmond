# ![Obtainmond Icon](./app/src/main/res/mipmap-hdpi/ic_launcher.png) Obtainmond

[![APK Build](https://github.com/HmnDev-Tech/Obtainmond/actions/workflows/apk-build.yml/badge.svg)](https://github.com/HmnDev-Tech/Obtainmond/actions/workflows/apk-build.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Shevery-API](https://img.shields.io/badge/Shevery--API-v14.6.4-8A2BE2.svg)](https://github.com/HmnDev-Tech/Shevery-API)
[![Rust Core](https://img.shields.io/badge/Rust-Core%20Engine-DEA584.svg?logo=rust)](native/obtainmond-core)
[![Compose M3 Expressive](https://img.shields.io/badge/UI-Material%203%20Expressive-6750A4.svg)](app/src/main/java/io/obtainmond/ui)

**Get Android app updates straight from the source — fully automated, zero-touch, and blazing fast.**

**Obtainmond** is a native **Kotlin (Jetpack Compose Material 3 Expressive)** and **Rust (`libobtainmond_core.so`)** evolution of Obtainium. Powered by native **Shevery-API (`v14.6.4`)** and **Shizuku**, Obtainmond bypasses Android battery optimizations to silently update your applications in the background on the fly — automatically selecting the exact APK variant that matches your device architecture without requiring user confirmation.

---

## 🚀 Why Obtainmond? (Key Improvements over Obtainium)

| Feature | Upstream Obtainium | Obtainmond (`io.obtainmond`) |
| :--- | :--- | :--- |
| **Core Architecture** | Flutter / Dart interpreter | **Native Android (Kotlin + Jetpack Compose) + Rust Core (`libobtainmond_core.so`)** |
| **Multi-APK Background Updates** | ❌ **Fails**. Silently aborts if a release has >1 APK asset | ✅ **Automated**. Rust Smart ABI Selector matches your installed app's ABI and flavor |
| **Zero-Touch Background Updates** | ⚠️ Often killed by Android Doze/battery limits | ✅ **Privileged Doze Bypass**. Whitelists itself via Shevery/Shizuku on the fly |
| **Privilege Backend** | Shizuku legacy binder only | ✅ **Dual Backend**: High-speed **Shevery-API v14.6.4** (`execFast()`) + **Shizuku** + **Root** |
| **User Interface** | Standard Flutter Material | ✅ **Material 3 Expressive** (`MotionScheme.expressive()`), floating pill bar, AGSL motion blur |
| **Time-Machine Rollback** | ❌ None | ✅ **Instant Rollback**. Snapshots `base.apk` before updates; 1-click downgrade (`pm install -r -d`) |
| **ART Optimization** | ❌ None | ✅ **Post-Install Dexopt**. Runs `cmd package compile -m speed-profile` for zero JIT lag |
| **Security & Integrity** | Basic SHA256 in Dart | ✅ **Rust ABI & Signature Guard**. Inspects APK ZIP Central Directory before install |
| **Debug & Release Builds** | Different keys (requires reinstall) | ✅ **Unified Signing**. Debug and release builds share the exact same key |

---

## 📦 Supported App Sources

Obtainmond tracks, parses, and downloads updates directly from a vast range of sources:

- **Open Source Repositories (Native Rust Parsers)**:
  - [GitHub](https://github.com/) (Full Release API + mirror acceleration + personal access token pool)
  - [GitLab](https://gitlab.com/) (Direct asset links + markdown release parser)
  - [Forgejo](https://forgejo.org/) / [Codeberg](https://codeberg.org/) (Native API v1)
  - [F-Droid](https://f-droid.org/) (Official Index v1 API)
  - Third-Party F-Droid Repositories
  - [IzzyOnDroid](https://android.izzysoft.de/)
  - [SourceHut](https://git.sr.ht/)
- **General App Stores & Mirrors**:
  - [APKPure](https://apkpure.net/)
  - [Aptoide](https://aptoide.com/)
  - [Uptodown](https://uptodown.com/)
  - [itch.io](https://itch.io/)
  - [Huawei AppGallery](https://appgallery.huawei.com/)
  - [Tencent App Store](https://sj.qq.com/)
  - [vivo App Store](https://h5.appstore.vivo.com.cn/)
  - [RuStore](https://rustore.ru/)
  - [Samsung Galaxy Store](https://galaxystore.samsung.com/)
  - [SourceForge](https://sourceforge.net/)
  - Jenkins CI Builds
  - [APKMirror](https://apkmirror.com/) *(Track-Only)*
- **Direct & Fallback Sources**:
  - **Direct APK URL**: Direct link to any `.apk` file endpoint.
  - **HTML Web Scraper (Rust Regex)**: Any HTML release page with `.apk` download links.

---

## 📥 Installation & Verification

Download the latest APK directly from the [GitHub Releases](https://github.com/HmnDev-Tech/Obtainmond/releases) page or CI Artifacts.

### Verification Information
- **Package Name**: `io.obtainmond`
- **Minimum Android Version**: Android 8.0 (API level 26)
- **Target Android Version**: Android 15 (API level 35)
- **Supported Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- **Signing Keystore**: Unified key across both Debug and Release APKs — you can freely install debug builds over release builds without uninstalling!

---

## 🔬 Obtainmond Labs

Obtainmond includes experimental power-user features:

1. **Instant Rollback Time-Machine**: Automatically preserves the previous `base.apk` in a secure rollback vault before updating. Allows 1-tap silent downgrade (`pm install -r -d`) if a new release has bugs.
2. **Post-Install ART Compiler**: Triggers Android's ART profile compiler (`cmd package compile -m speed-profile -f <pkg>`) immediately after silent updates, pre-compiling bytecode so apps open instantly.
3. **Installer Package Spoofer**: Emulates installer provenance (`-i com.android.vending`, `com.sec.android.app.samsungapps`, or `org.fdroid.fdroid`) to preserve store compatibility and bypass integrity checks.
4. **Privileged App Freezer**: Suspend or quarantine tracked applications on demand via privileged `pm suspend`.
5. **Rust ABI & Signature Guard**: Uses `libobtainmond_core.so` to parse APK ZIP headers and verify that native `.so` libraries match your device CPU architecture before privileged installation.

---

## 🛠️ Building From Source

### Prerequisites
- JDK 21 (Temurin or OpenJDK)
- Android SDK (API 35/36) & Android NDK (r26 or r27)
- Rust (stable toolchain) with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  cargo install cargo-ndk --locked
  ```

### Build Commands
```bash
# 1. Build Rust core libraries
cd native
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 --platform 26 -o ../app/src/main/jniLibs build --release -p obtainmond-core
cd ..

# 2. Build Debug and Release APKs
./gradlew :app:assembleDebug :app:assembleRelease
```

Output APKs will be located in `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

---

## 🤝 Contributing & Community

We welcome contributions! Please review our guidelines before submitting code:
- [Contributing Guide](CONTRIBUTING.md) — How to set up your environment, code style, and PR process.
- [Code of Conduct](CODE_OF_CONDUCT.md) — Our community standards.
- [Security Policy](SECURITY.md) — How to report vulnerabilities responsibly.
- [Issue Templates](.github/ISSUE_TEMPLATE/) — Bug reports and feature proposals.

---

## 📄 License

Obtainmond is licensed under the [GNU General Public License v3.0](LICENSE.txt).
Original Obtainium concept Copyright (c) ImranR98. Re-architected in Rust and Kotlin by HmnDev-Tech.
