# Contributing

Discuss substantial changes in an issue first. Keep patches focused, preserve Arabic/English resource parity and never commit signing keys, tokens, private URLs or user files.

Use JDK 17/21, Android SDK 35 and the included Gradle wrapper. Run `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`, then device tests with `./gradlew connectedDebugAndroidTest`. Network acceptance tests are opt-in; see [validation](docs/RELEASE_VALIDATION.md). A passing debug build does not substitute for a minified release smoke test.

Describe the problem, changed behavior, test environment and limitations in pull requests. Keep public download links tied to a real published release. Report vulnerabilities as described in [SECURITY.md](SECURITY.md). Contributions must be compatible with GPL-3.0-or-later and preserve applicable third-party notices.
