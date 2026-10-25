# Release 1.0.0 — verification record

The project owner reported YouTube downloads working correctly on their phone on 2026-10-26 and requested moving to release. This is user-reported acceptance, not an automated claim or a guarantee for every source/network.

## Included fixes

Authenticated local proxy sessions; transfer ownership retained through cleanup; native cancellation off the UI thread; completion notification; highest compatible audio bitrate; HEAD fallback; available-space checks with no fixed media-size ceiling; Arabic/English interface and contact/about pages.

## Evidence

- 29 JVM tests passed in the reliability build.
- GitHub Android verification run 40238340188 passed build and Android API 35/36 device jobs at c01bfe4.
- Local deterministic output/UI tests passed. The direct MP4 download and MediaPlayer playback passed before release packaging.
- Local native Python TLS requests remain intermittent, including a fixed public fixture without the proxy. These failures are retained in docs/evidence and are not erased by owner acceptance.
- Release package validation is recorded below after the signed artifact is built and retrieved from GitHub.

## Scope and limitations

No resume, DRM bypass, login/cookie import or guarantee of all sites. Disk capacity and provider limits apply. Process-death recovery, unusual SAF providers, full native dependency auditing and lifecycle stress coverage remain follow-up work. This release is not a security certification. Historical preview audit documents describe the state at their recorded date.

## Signing

VersionCode 4, versionName 1.0.0. Production RSA key is kept outside the repository. Its password is encrypted for the local Windows account. Back up the signing directory securely: future compatible updates require the same key. Neither key nor password is uploaded to GitHub.

GitHub native public-fixture diagnostic run 40238340229 also passed. This exercised the native downloader on an independent API35 emulator, not YouTube.

Signed release build: testDebugUnitTest (29 passed), lintRelease and assembleRelease succeeded. APK SHA256: 2a701da47ef1fd92940251ccaccf55e9dcbef9683a759fa5d2ac8c786ed6bb3b. Certificate SHA256: bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9. APK v2 signature verified; Android10+; arm64-v8a/x86_64.
