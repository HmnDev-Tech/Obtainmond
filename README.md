# ![Obtainmond Icon](./app/src/main/res/mipmap-hdpi/ic_launcher.png) Obtainmond

[![APK Build](https://github.com/HmnDev-Tech/Obtainmond/actions/workflows/apk-build.yml/badge.svg)](https://github.com/HmnDev-Tech/Obtainmond/actions/workflows/apk-build.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**Get Android app updates straight from the source — fully automated, zero-touch, and verified before installation.**

**Obtainmond** is a native **Kotlin (Jetpack Compose Material 3 Expressive)** and **Rust (`libobtainmond_core.so`)** evolution of Obtainium. Powered by native **Shevery-API (`v14.6.4`)**, **Shizuku**, **Dhizuku**, **Root**, and Android `PackageInstaller`, Obtainmond bypasses Android battery optimizations to update your applications cleanly — automatically selecting the exact APK or split-APK bundle matching your device architecture and verifying cryptographic signatures and certificates before installing.

---

## 🚀 Key Improvements & Architecture

| Feature | Upstream Obtainium | Obtainmond (`io.obtainmond`) |
| :--- | :--- | :--- |
| **Core Architecture** | Flutter / Dart | **Native Android (Kotlin + Jetpack Compose) + Rust Core (`libobtainmond_core.so`)** |
| **Multi-APK & Split Bundles** | Prompted on splits | **Automated**. Rust Smart ABI Selector matches ABIs and unpacks `.apks` / `.xapk` / `.apkm` / `.apk.zip` |
| **Zero-Touch Background Updates** | Standard Android scheduling | **Privileged Doze Bypass**. Whitelists itself via Shevery/Shizuku on the fly |
| **Privilege & Installer Backends** | Shizuku + PackageInstaller | **Shevery-API v14.6.4** (`execFast()`), **Shizuku**, **Dhizuku**, **Root**, **PackageInstaller**, & **External Installers** |
| **User Interface** | Flutter Material | **Material 3 Expressive** (`MotionScheme.expressive()`), adaptive tablet rail + 2-pane layout, AGSL motion blur |
| **Time-Machine Rollback** | None | **Instant Rollback**. Snapshots `base.apk` and all split APKs before updates; diagnoses Android 7+ downgrade limits |
| **ART Optimization** | None | **Post-Install Dexopt**. Runs `cmd package compile -m speed-profile` after updates |
| **Security & Integrity** | Basic SHA-256 check | **Rust ZIP Central Directory & APK Signing Block Guard + `apksig` + Cert SHA-256 Pinning + Tink/Keystore Encrypted PATs** |
| **Supply-Chain Verification** | None | **F-Droid / IzzyOnDroid Reproducible Builds**, **GitHub Release Attestations**, & optional **VirusTotal** hash lookup |

---

## 📦 Supported App Sources

Obtainmond implements dedicated structured Rust parsers in `native/obtainmond-core/src/sources.rs` for:

- **Open Source Forges**:
  - [GitHub](https://github.com/) (Full Releases API + encrypted PAT pool rotation + optional custom HTTPS mirror with strict signature enforcement)
  - [GitLab](https://gitlab.com/) & self-hosted `gitlab.*` instances (v4 Project Releases API + Markdown link extraction)
  - [Forgejo](https://forgejo.org/) / [Codeberg](https://codeberg.org/) / [Gitea](https://gitea.com/) (Native v1 Releases API)
  - [F-Droid Official](https://f-droid.org/) (v1 API + Reproducible Builds verification log check)
  - **Third-Party F-Droid Repositories** (`index-v1.json` & `index-v2.json` structured parsers)
  - [IzzyOnDroid](https://apt.izzysoft.de/) (Native index/API parser + Codeberg `rbtlog` Reproducible Builds verification)
- **App Stores & CI Feeds**:
  - [Aptoide](https://aptoide.com/) (`ws75.aptoide.com` API v7 metadata & search parser)
  - [RuStore](https://rustore.ru/) (`backapi.rustore.ru` app info & v2 download-link resolution)
  - [SourceForge](https://sourceforge.net/) (Project RSS feed XML parser)
  - **Jenkins CI** (`lastSuccessfulBuild/api/json` build & artifact parser)
- **Direct & Generic Sources**:
  - **Direct Package URL**: Direct links to `.apk`, `.apks`, `.xapk`, `.apkm`, or `.apk.zip` files
  - **HTML Web Scraper**: Fallback Rust regex extractor for release pages linking Android packages

---

## 📥 Installation & Signing Security

- **Package Name**: `io.obtainmond`
- **Minimum Android Version**: Android 8.0 (API level 26)
- **Target / Compile SDK**: API level 37
- **Supported Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- **Signing**: Debug builds use standard debug signing unless configured locally via external `signing.properties`. Release builds require external `signing.properties` or environment variables (`KEYSTORE_FILE` / `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and never fall back to any repository-embedded key.

---

## 🔬 Obtainmond Labs & Security Guards

1. **APK ZIP Central Directory & Signing Block Guard**: `libobtainmond_core.so` parses the real ZIP Central Directory (`AndroidManifest.xml`, `lib/<abi>/*.so`) and EOCD + APK Signing Block v2/v3, paired with Google `apksig` full cryptographic verification and SHA-256 signing certificate pinning on every update.
2. **Instant Rollback Time-Machine**: Preserves `base.apk` and all installed split APKs in `rollback_vault/` before updating, with clear diagnostics when Android 7+ blocks downgrading non-debuggable release apps without root bypass modules.
3. **Post-Install ART Compiler**: Triggers Android's ART profile compiler (`cmd package compile -m speed-profile -f <pkg>`) immediately after silent updates.
4. **Supply-Chain Verification**: Optional `Off` / `Audit` / `Enforce` verification against F-Droid & IzzyOnDroid Reproducible Builds, GitHub Release Attestations, and VirusTotal v3 file hash checks.

---

## 🛠️ Building From Source

### Prerequisites
- JDK 21 (Temurin or OpenJDK)
- Android SDK (API 37) & Android NDK
- Rust (stable toolchain) with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  cargo install cargo-ndk --locked
  ```

### Build & Test Commands
```bash
# 1. Verify & test Rust core
cargo test --manifest-path native/Cargo.toml

# 2. Cross-compile libobtainmond_core.so for all Android ABIs
cd native
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 --platform 26 -o ../app/src/main/jniLibs build --release -p obtainmond-core
cd ..

# 3. Run unit tests and build Debug APK
./gradlew :app:testDebugUnitTest :app:assembleDebug

# 4. Build Release APK (requires external signing.properties or env vars)
./gradlew :app:assembleRelease
```

---

## 🤝 Contributing & Community

- [Contributing Guide](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issue Templates](.github/ISSUE_TEMPLATE/)

---

## 📄 License

Obtainmond is licensed under the [GNU General Public License v3.0](LICENSE.txt).
Original Obtainium concept Copyright (c) ImranR98. Re-architected in Rust and Kotlin by HmnDev-Tech.
