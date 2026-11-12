## Summary
<!-- Briefly explain the motivation and context of your changes. What problem does this solve? -->

## Type of Change
- [ ] Bug fix (non-breaking change fixing an issue)
- [ ] New feature (non-breaking change adding functionality)
- [ ] Rust Core improvement (version parsing, ABI selector, integrity guard)
- [ ] Material 3 Expressive UI / UX enhancement
- [ ] Shevery-API / Shizuku privilege improvement
- [ ] Documentation update
- [ ] Build / CI pipeline update

## Subsystems Affected
- [ ] `native/obtainmond-core` (Rust JNI)
- [ ] `app/src/main/java/io/obtainmond/privilege/` (Shevery/Shizuku)
- [ ] `app/src/main/java/io/obtainmond/worker/` (Background Auto-Updater)
- [ ] `app/src/main/java/io/obtainmond/ui/` (Jetpack Compose)
- [ ] `app/src/main/java/io/obtainmond/data/` (Repository & Models)

## Testing Done
- [ ] Built and verified locally (`cargo check` in `native/`)
- [ ] Verified on physical device or emulator
- [ ] Verified silent background update flow
- [ ] Verified unified signing (both debug and release APKs install cleanly)

## Checklist
- [ ] My code adheres to the project's coding standards.
- [ ] I have commented my code, particularly in hard-to-understand areas.
- [ ] I have updated corresponding documentation if applicable.
- [ ] My branch is up to date with `main`.
