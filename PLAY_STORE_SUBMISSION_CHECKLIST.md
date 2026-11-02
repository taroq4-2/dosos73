# Google Play submission checklist

Prepared 2 November 2026 for `com.os73.dosos73`. **State: External approval pending / production NO-GO.** This is a submission preparation record, not a Play Console upload, legal approval or guarantee of acceptance. The release report supplies the final artifact hashes and test counts.

## Technical package

- [ ] Final source SHA, versionName/versionCode, APK/AAB hashes, sizes and build commands recorded in `RELEASE_READINESS_REPORT.md`.
- [ ] Release AAB validates with pinned bundletool, contains the intended four ABI delivery sets, mapping/symbol evidence and API target; generated device APKs install and execute the native engines.
- [ ] API 36 compile/target migration and 29–36 regression matrix pass on the final source. The code has been upgraded; completion depends on the root QA evidence, not this checkbox.
- [ ] Signed APK certificate matches the existing application; AAB upload signature verified separately. Production/upload keystores remain outside source and logs.
- [ ] Play App Signing enrollment/upload-key ownership and recovery process confirmed in the owner's account. A locally signed AAB does not establish Play enrollment or the eventual Play app-signing certificate.
- [ ] Physical ARM/OEM/low-memory test matrix completed or explicitly left unverified; emulator results alone do not close it.
- [ ] Native security findings and producer-lock/source correspondence in `SECURITY_REVIEW.md` resolved or formally approved with evidence.
- [ ] Required third-party licenses, complete corresponding sources/build recipes and notices are available for the exact submitted binaries. Legal review pending.

Ordinary new applications and updates must now target API 36; upgrading the target preserves the existing minimum SDK rather than excluding older supported Android devices. [Official target-API policy](https://support.google.com/googleplay/android-developer/answer/11926878).

## Permissions and foreground work

| Manifest item | Current purpose | Submission evidence / owner task |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | User-requested transfers and network policy | Explain remote source requests and network conditions |
| POST_NOTIFICATIONS | Optional transfer/completion/reminder notifications | Demonstrate both grant and denial paths |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | Visible user-started network transfer and local processing | Complete FGS declaration, user-impact explanation and demonstration video; review whether local conversion should be declared under media processing for the final Play use case |
| RECEIVE_BOOT_COMPLETED | Durable JobScheduler reminders | Explain reminders and saved queue; do not claim exact scheduled auto-download |
| Selected folder URI grants | User-selected SAF source/destination, no broad file permission | Show picker consent, revocation and graceful recovery |

No accessibility service, background clipboard reader, all-files access, sensor gesture monitor, login/cookie importer or full-screen-intent permission is requested by the reviewed manifest. The OEM back-tap route launches a normal application shortcut; the app does not secretly change the phone's gesture settings.

Google requires the foreground-service declaration and a demonstration of the user-triggered functionality. Actual Console selections must match the final build; they have not been submitted here. [FGS declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821).

## Privacy / Data safety preparation

- [ ] Publish an accessible, owner-approved privacy-policy URL, linked in the store and inside the app. `PRIVACY.md` is a draft source artifact; verify the public URL separately.
- [ ] Identify the responsible developer/contact and applicable retention/deletion terms; do not invent an email or legal entity.
- [ ] Review network traffic for the signed final package and dependencies. The reviewed source has no analytics, advertisements or account service, but requested URLs, headers and IP information necessarily reach content hosts/CDNs.
- [ ] Explain local encrypted queue/history/presets, filenames, output grants, one-shot foreground clipboard access and optional external sharing.
- [ ] Complete Data safety for the actual app, including third-party libraries and user-initiated transfers. “No analytics” is not by itself proof that every form answer is “no data collected.”
- [ ] Verify deletion/clear-history controls and local-only processing claims against the shipped binary. Do not claim end-to-end confidentiality for optional unencrypted HTTP.

The owner is responsible for accurate form answers and the privacy-policy link, including SDK behavior. User-initiated sharing and on-device-only processing have specific policy definitions; apply them to the actual flows. [Official Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

## Content, rights and store listing

- [ ] Owner/legal review confirms intended uses, third-party service terms, copyright/codec redistribution and territorial obligations. Public accessibility is not permission to download or redistribute.
- [ ] Do not advertise DRM bypass, restricted/login-only access, rights circumvention or support for every source/video.
- [ ] Keep examples/screenshots to original, licensed or permissioned media. Obtain rights for third-party brand artwork and avoid implied endorsement.
- [ ] Explain supported workflows truthfully: direct public files, supported permissioned media, MP3 conversion, local video-to-MP3, queue/batch/presets and library. Distinguish a single active file from optional multi-connection transfer.
- [ ] Provide Arabic/English listing, latest screenshots, support and privacy links, content rating, target audience, advertisements declaration and reviewer instructions.
- [ ] Review the existing broad downloader positioning before submission; local engineering success cannot decide service-term or Play policy acceptance.

YouTube's terms restrict downloading and automated access except under the permissions specified by that service. A working extractor is not evidence of permission or Play approval. Owner/legal disposition remains required. [YouTube terms](https://www.youtube.com/static?template=terms).

## Account-controlled gates

- [ ] Confirm Play Console account type, creation date, identity verification, region and access permissions.
- [ ] If the personal account was created after 13 November 2023, confirm the applicable closed-test requirement, currently at least 12 opted-in testers for 14 continuous days, and apply for production access after satisfying it.
- [ ] Record actual test-track participation and feedback; do not substitute virtual reviewers, emulator runs or APK downloads for opted-in testers.
- [ ] Obtain explicit owner authorization for any upload, track rollout or publication. This work does not perform one.
- [ ] Complete Google review and retain its actual decision. Only Google can approve the submission.

The account condition and test-track rules were checked in the [official testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465). No Console account details, real testers, 14-day participation or production access were verified in this environment.

## Draft reviewer notes

The app has no sign-in. Paste a URL for content that the reviewer owns or is authorized to download, review the available type/quality and explicitly start the transfer. Notifications are optional. The folder picker grants access only to a chosen location. Local video-to-MP3 requires a user-selected video and preserves its original. Quick copied-link launch is opt-in and reads the clipboard only when the app is foreground, focused and unlocked. Deferred tasks show a reminder to open the application; execution time is controlled by Android. Supply an owner-approved stable test asset before submission; no private or copyrighted test URL is included here.
