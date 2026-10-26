# Neon redesign implementation

Baseline: e79fe4f, Compose/Kotlin, min29/target35, current yt-dlp/FFmpeg unchanged. The earlier 1.0.1 reliability fixes are retained. Native source requests remain behind the authenticated public-address proxy. No extra runtime dependency or permission is planned.

Reference mapping: deep navy canvas, D mark/cyan-blue-purple wordmark, rounded URL pill, source shortcuts, four tool tiles, fixed four-item navigation; compact headers in downloads/files; real filtered task and file lists. No mock phone frame or fabricated transfer list.

Implementation: shared theme/icons/components; home; task queue and direct-stream pause/resume while app process lives; persistent completed-file index and scoped-storage discovery; library actions; settings/about/contact; screenshot/device verification. Native extractor tasks retain cancel/retry, because safe resumable pausing is not supplied by that engine. UI must not advertise unsupported native resume.

Acceptance: baseline and final JVM/lint/build; queue/file-action device tests; Arabic/English navigation and screenshots; actual direct transfer; signed APK from GitHub installed as an update; README/screenshots/release updated after verification. Process-death interrupted transfers require explicit restart; no automatic background network on launch.

## Implemented design and behavior

The supplied PNG is rendered directly for the interior D mark and wordmark; the launcher uses a small adaptive vector version of the same motif. The PNG itself is unmodified. Shared navy/cyan/blue/purple tokens, spacing, rounded components, a consistent vector symbol family and bundled Tajawal fonts apply throughout the app. The font is unmodified OFL-licensed and its notice is retained inside the APK.

Home, downloads and files are real Compose screens with persistent bottom navigation. Settings, About, contact, details, confirmation dialogs, errors, empty states and search use the same theme. Arabic is the default for new installations, with an English switch and Android app-language support. Filters scroll horizontally at increased font sizes.

DownloadQueue persists up to 50 waiting tasks in an atomic private JSON file. A single foreground-service worker processes tasks in order. FailureJournal retains up to 100 failed/cancelled entries independently of the current transfer. Failure stops the queue until an explicit retry/continue. Cancellation leaves the waiting queue intact. There is no automatic network job at launch.

LibraryRepository persists published document URIs and discovers app-owned MediaStore Downloads. Rename/delete operate on the content resolver or document provider off the UI thread. Delete requires UI confirmation; removing history only hides a history entry. Thumbnail work is lazy with an 8 MiB cache. No all-files or media-reading permission was added. Unowned files left by an uninstalled preview are not silently imported.

## Test execution and corrections

Baseline JVM/lint/debug build passed. Final JVM suite has 35 passing tests, including exact-byte consumption after a cooperative pause. Added device tests for queue codec/order, output rename/history-removal/delete, supported app languages, launcher density, navy system splash, navigation and failure preservation.

Initial redesign CI 40309315235 passed build/API35/API36. A later share regression test initially opened an implicit Android chooser, then interfered with ActivityScenario teardown when relaunching its activity. The test now uses Instrumentation.callActivityOnNewIntent on the managed activity to exercise the callback without creating an unmanaged activity. External ACTION_SEND routing was also exercised manually on the emulator.

Manual public Sintel download was verified from the release UI, with actual byte count 4,372,373 and thumbnail/library entry. Opening Android sharing was tested without sending to any recipient. Under an intentionally very slow emulator link, cooperative pause and resume changed real transfer behavior; the long-lived connection did not complete successfully in that stress run, while the subsequent fresh queued transfer did. This exposed the need for independent failure history and stopping the queue, which was implemented and covered by a service test using DNS-blocked loopback URLs without external network access. This stress run is not reported as a successful resumed completion.

Local emulator system_server/nexuslauncher crashed with EGL_BAD_CONFIG using host graphics. Restarted the same AVD with SwiftShader and without loading a graphics snapshot; no user data was wiped. That system-renderer failure is not counted as an application exception.

## Remaining limits

One active transfer at a time. Native-extractor pause/resume and process-death resumption are unavailable. Waiting tasks and failure history persist; an in-flight task killed with its process needs a new explicit start. Full Doze/background stress, physical ARM UI verification and every SAF provider are not covered. No guarantee of pixel identity across Android/font-scale/device variants or of every source succeeding is made. Historical native/license/security follow-up items remain documented.

## Final artifact acceptance

Final evidence is recorded below.

Final code CI: [40318085024](https://github.com/taroq4-2/dosos73/actions/runs/40318085024) at 51faee0 passed build and API35/API36. Each device report lists 27 tests, zero failures and five opt-in network/diagnostic tests skipped (22 passed). The JVM suite passed 35 tests. Debug/release lint and signed assembleRelease succeeded locally; verifyTranslations ran before build. Commands: `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` and `gradlew.bat lintRelease assembleRelease`; CI runs `./gradlew connectedDebugAndroidTest`.

The ActivityScenario issue was traced to its intent-based lifecycle matching in the [official AndroidX source](https://github.com/android/android-test/blob/main/core/java/androidx/test/core/app/ActivityScenario.java). The regression test now restores the original launch intent before teardown after verifying the new-intent callback. Production onNewIntent behavior is preserved. A post-test screenshot export step also failed because the runner had already removed the target app directory; that unsupported export was removed while retaining all device tests and reports. Screenshots were captured manually from running signed 1.1.0 acceptance builds, without modifying their content.

On the signed GitHub artifact, a later short-pause run completed successfully: active transfer, Pause state, Resume, completed publication and 4,372,373-byte `trailer (3).mp4`. Four real public test files were present afterward. Open launched the Android media viewer; the captured viewer frame shows 0:05 / 0:52. Language switching was tested using Settings itself. English/Arabic layouts and 130% font scale were inspected, then system font scale and emulator network limits were restored.

One local SwiftShader/API35 observation: 1833 ms cold launch; file-library PSS 51,834 KiB. These are single observations, not comparable benchmarks or claims of a speedup. APK size is 111,978,356 bytes (universal arm64-v8a/x86_64). The embedded Tajawal notice was confirmed inside the optimized APK (`res/Y6.txt`, raw/tajawal_license resource).

Version 1.1.0 / versionCode 6. APK SHA256: `242d68d720a773397afef53f04e9f530f9e0d287d1ba107af85a844e40cea3a4`. Release certificate SHA256: `bf0bcb1ba4d282b2e38f75be6220ad4b06902f96b47bbe68d67a51bfeb6e0ca9`. Key material remains outside the repository. Existing official-signature installations can update in place; legacy debug previews use another signing key.

The final checksum-matching artifact was reinstalled after the search-close fix and its Arabic Home screen was captured again. No application-process crash was observed. The emulator's external Photos viewer later showed an ANR after the captured playback frame, and a shell UiAutomation process timed out while connecting. The external viewer was closed; these are recorded as emulator/third-party limitations rather than successful full-duration playback. The dosos73 activity resumed normally and all four output files remained intact.

Screenshots include public Sintel trailer frames/thumbnails, credited to Blender Foundation. Test source: https://media.w3.org/2010/05/sintel/trailer.mp4 ; project: https://durian.blender.org/ . No media is bundled with the application.
