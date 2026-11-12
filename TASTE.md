# Obtainmond — Design & Architecture Taste (`TASTE.md`)

## 1. Visual Identity & Material 3 Expressive Direction
- **Engine**: Jetpack Compose + `androidx.compose.material3:material3:1.5.0-alpha16` (`MaterialExpressiveTheme` with `MotionScheme.expressive()`).
- **Navigation**: Floating Pill Toolbar (`ExpressiveFloatingNavigationBar` built on `HorizontalFloatingToolbar`) suspended above edge-to-edge content with dynamic AGSL directional scroll motion blur (`MotionBlur.kt`).
- **Surface Hierarchy**:
  - Grouped expressive cards (`SettingsGroup` / `ExpressiveCard`) with `28.dp` outer corners and `6.dp` inner stacked corners.
  - Spring-driven interactive scaling (`0.97f` press scale, `Spring.DampingRatioMediumBouncy`) on primary action pills and app cards.
  - Optional Pure Black (`#000000`) OLED mode and Material You Dynamic Color (`Build.VERSION_CODES.S+`).

## 2. Core Engineering Principles
- **Rust-First Core (`libobtainmond_core.so`)**:
  - All version normalization, semantic/pre-release comparisons, multi-APK ABI scoring, release API JSON/HTML parsing, and streaming SHA-256 + ZIP Central Directory ABI verification run natively in Rust via JNI.
- **Dual Privileged Execution (`Shevery-API` v14.6.4 + `Shizuku`)**:
  - Primary fast-path: `SheveryPrivilege.connectFast(context)` + `SheveryPrivilege.execFast(...)` from `com.hamondev.shevery.api:api:14.6.4`.
  - Automatic fallback: `rikka.shizuku.Shizuku` binder shell, `su -c` Root shell, or standard Android `PackageInstaller` session.
- **Zero-Touch Background Auto-Update**:
  - Self-whitelists `io.obtainmond` from Doze/Battery Optimization via privileged `cmd deviceidle whitelist +io.obtainmond` and `cmd appops set io.obtainmond RUN_ANY_IN_BACKGROUND allow`.
  - Automatically matches the installed app's CPU ABI (`primaryCpuAbi` / `Build.SUPPORTED_ABIS`) and flavor tokens in Rust so multi-APK releases update silently in the background without user intervention.
