# Contributing to Obtainmond

Thank you for your interest in contributing to **Obtainmond**! This document provides guidelines and workflows for contributing bug fixes, features, and documentation.

---

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). Please treat all contributors with respect and professionalism.

---

## Architectural Principles

Obtainmond is divided into two primary subsystems:

1. **Rust Core Engine (`native/obtainmond-core`)**:
   - Written in modern Rust (edition 2021).
   - Responsible for version parsing, semantic version comparison, multi-asset ABI scoring (`apk_selector.rs`), release API scraping (`sources.rs`), streaming SHA-256 calculation, and APK ZIP Central Directory inspection (`integrity.rs`).
   - Exposed to Kotlin via JNI (`ObtainmondNative`).
   - **Rule**: Pure business/parsing logic belongs in Rust. Do not add heavy parsing or regex engines in Kotlin when they can run safely and with zero allocations in Rust.

2. **Android UI & System Integration (`app/`)**:
   - Written in Kotlin with Jetpack Compose and **Material 3 Expressive** (`androidx.compose.material3:material3:1.5.0-alpha16+`).
   - Dual privileged backends: **Shevery-API (`v14.6.4`)** as the primary ultra-low-latency IPC channel, with **Shizuku** and **Root** as fallbacks.
   - Follows reactive unidirectional data flow (`StateFlow` + Coroutines).

---

## How to Contribute

### 1. Reporting Bugs
- Before submitting a new issue, check existing [open and closed issues](https://github.com/HmnDev-Tech/Obtainmond/issues) to ensure it hasn't already been reported.
- Use our [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.yml).
- Always include:
  - Device model, Android OS version, and CPU architecture (`arm64-v8a`, etc.).
  - Obtainmond version/commit hash.
  - Privileged backend in use (Shevery-API, Shizuku, Root, or None).
  - Exact URL of the app being added or updated.
  - Audit log snippet from the **Settings & Telemetry** page if applicable.

### 2. Suggesting Features
- Use the [Feature Request Template](.github/ISSUE_TEMPLATE/feature_request.yml).
- Explain the problem, proposed solution, and alternative approaches.
- For new sources or power-user features, consider whether it belongs in **Obtainmond Labs**.

### 3. Pull Request Guidelines

To ensure smooth review and merging, please follow these conventions:

#### Branching Strategy
- Fork the repository and create a descriptive branch:
  ```bash
  git checkout -b fix/github-asset-scoring
  # or
  git checkout -b feat/fdroid-mirror-rotation
  ```

#### Code Standards
- **Kotlin**:
  - Follow official Kotlin coding conventions.
  - Keep Compose composables modular and stateless where possible.
  - Respect the Material 3 Expressive design tokens (`MotionScheme.expressive()`, floating toolbar, spring physics).
- **Rust**:
  - Run `cargo fmt` and `cargo check` inside `native/` before committing.
  - Avoid `unsafe` unless strictly required for JNI boundary conversions.
  - Handle all errors with `Result` or `Option`; never `unwrap()` or `panic!()` in production JNI pathways.

#### Commit Messages
- Use concise, conventional commit messages:
  - `feat: add Codeberg forgejo v1 release parser in rust`
  - `fix: resolve armv7-a fallback scoring on 64-bit devices`
  - `docs: update build instructions and contributing guide`

#### Pull Request Submission
- Fill out all sections in the [Pull Request Template](.github/pull_request_template.md).
- Ensure your changes do not break the unified signing setup or CI workflow.
- Keep PRs focused on a single logical change. Do not bundle unrelated refactoring with bug fixes.

---

## Building & Verification

```bash
# Verify Rust core
cd native
cargo check
cargo test
cd ..

# Verify Android build (debug & release)
./gradlew :app:assembleDebug :app:assembleRelease
```

Thank you for helping make Obtainmond better!
