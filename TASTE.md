# Obtainmond — Design & Architecture Taste (`TASTE.md`)

## Direction
Obtainium-faithful Android app-updater UX elevated with true **Material 3 Expressive** (`androidx.compose.material3:material3:1.5.0-alpha16`, `MotionScheme.expressive()`, `MaterialShapes`, `Morph`, wavy progress, split buttons, floating toolbar + FAB). 100% Jetpack Compose — **zero XML UI layouts, drawables, or animations**.

## Type
- **Display / Headers**: `headlineLarge` / `displaySmall`, `FontWeight.ExtraBold`, tight `-0.5.sp` letter spacing for editorial contrast.
- **App Rows / Actions**: `titleMedium` (`FontWeight.Bold` / `SemiBold`) paired with `labelMedium` tonal status pills.
- **Technical Telemetry**: `FontFamily.Monospace` strictly for package IDs, semantic versions, CPU ABIs (`arm64-v8a`), regex filters, and SHA-256 digests.

## Color
- **Dynamic System Palette**: `dynamicDarkColorScheme` / `dynamicLightColorScheme` on Android 12+ (`S+`), with expressive tonal fallback and optional `#000000` Pure Black OLED surface mode.
- **Tonal Hierarchy**: Distinct `surfaceContainerLowest` → `surfaceContainerHigh` separation instead of flat monochrome cards; `primaryContainer` / `tertiaryContainer` reserved for active update states and primary actions.
- **Obtainium Category Accents**: Deterministic hue-mapped vertical color bars (`4.dp` rounded pill) along the start edge of tracked app items.

## Shape & Material
- **Expressive `MaterialShapes` + `Morph` Transitions** (powered by `androidx.graphics.shapes.Morph` + `.toShape()`):
  - App Avatar Container: real `PackageManager.getApplicationIcon(pkg)` rendered inside a state-morphing polygon (`Square` / `Clover4Leaf` idle → `Cookie7Sided` update available → `Cookie9Sided` multi-selected → `Diamond` frozen).
  - Busy Indicators: `ContainedLoadingIndicator` (morphing `MaterialShapes`), `LinearWavyProgressIndicator`, and `CircularWavyProgressIndicator`.
  - Tactile Controls: `ButtonDefaults.shapes()`, `ToggleButtonDefaults.shapes()`, `SplitButtonLayout` on App Detail, and `ButtonGroup` for segmented selectors.
  - Floating Toolbar: `HorizontalFloatingToolbar` with `FloatingToolbarDefaults.VibrantFloatingActionButton` (morphs into contextual multi-select batch toolbar when apps are long-pressed).
- **Surface Variety**: Stacked `SettingsGroup` (`26.dp` outer / `6.dp` inner corners), asymmetric version-compare capsules, and compact Obtainium list rows — never uniform `28.dp` boxes everywhere.

## Spacing & Density
- **Apps List**: Scannable Obtainium density (`16.dp` horizontal margin, `10.dp` item gap, `4.dp` category accent bar) so tracked apps are immediately visible without scrolling past a giant hero banner.
- **Detail & Forms**: Progressive disclosure (collapsible advanced fields on Add App; dedicated full-screen App Detail route and categorized Settings sub-pages).

## Motion
- `MotionScheme.expressive()` globally + bouncy springs (`Spring.DampingRatioMediumBouncy` / `DampingRatioLowBouncy`) for shape morphs, shared scale+fade screen transitions, multi-select reveals, and velocity-driven AGSL directional scroll motion blur (`MotionBlur.kt`).

## Explicitly Avoid
- Any XML UI (`res/layout/`, XML animations, XML UI themes beyond minimal `android:theme` manifest shell).
- Giant "AI dashboard" telemetry cards pushing tracked apps off the home screen.
- Uniform `RoundedCornerShape(28.dp)` cards repeated for every section.
- Text monogram fallbacks when the real installed Android app icon exists.
- Cramped inline accordions instead of a proper full-screen App Detail view.
