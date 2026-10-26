# Release validation — 1.1.2

Validation is in progress on maintenance/verified-cleanup. This page will be completed with exact artifact identity and results before publication. Historical repair findings remain in RELIABILITY_1.1.1.md; they are not substituted for this release's tests.

Source of truth: clean local 0f6b14d matched origin/main and origin/feat/neon-manager. Local 0.1.0-dev and 0.2.0-preview archives were inspected and rejected as older implementations. Dependabot branches are unmerged upgrade proposals, not newer intended application features. The authorized workspace search found no other complete dosos73 checkout; unrelated inaccessible directories were not treated as sources.

Cleanup: 54 confirmed obsolete tracked files removed; individual entries are in CLEANUP_MANIFEST.csv. Historical reports remain in Git at tag v1.1.1. All current Kotlin implementation, regression/diagnostic tests, Gradle infrastructure and license notices retained. Required minSdk29 launcher uses the adaptive vector; obsolete pre26 raster fallback and unused old branding were removed.

Commands: Gradle testDebugUnitTest, lintDebug, lintRelease, assembleDebug, assembleRelease, connectedDebugAndroidTest. Device matrix: API29,33,35,36. Exact signed x86_64 release receives runtime acceptance locally; ARM64 cannot be executed on the available x86_64 emulator. No S23 Ultra or other physical device is attached. No blanket all-device claim.
