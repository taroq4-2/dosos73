# V4 review record — 2026-10-25

These are twenty review perspectives applied by the implementing assistant, not twenty independent experts. Evidence is linked in the repository; unexecuted cases remain open. User instruction now authorizes remaining UI work despite the unresolved YouTube gate and explicitly removes the file-size limit.

| Review | Finding / action | Result |
|---|---|---|
| 1. YouTube integration | Pinned engine/EJS; actual Python, QuickJS and FFmpeg execute on API35. HTTP403 followed by sign-in challenge remains. | Stable gate blocked |
| 2. Network | Fixed public fixture hashes match across direct, Java CONNECT and Python CONNECT. Two size-boundary tests now confirm >2GiB declarations proceed to transfer. | Fixture passed; YouTube unresolved |
| 3. Formats | Only enumerated formats; compatible m4a required for mp4 video-only; output checks height and expected audio. Added deterministic mapping test. | Covered mapping; live YouTube not passed |
| 4. FFmpeg | Version 7.1.1 executes. No duplicate FFmpeg dependency. | Actual YouTube merge not passed |
| 5. Storage | Removed fixed output-size cap; retained nonempty and media checks. Verify staged destination before publication; exact-byte MediaStore and sparse >2GiB tests added. | API35 tests required; provider matrix open |
| 6. Reliability | Interrupted direct response fails. Cancel remains restart-only; no fake pause control. | Process-death recovery open |
| 7. Kotlin | UI extracted from activity; focused ContactDraft validation; byte-speed calculation uses Double intermediate to avoid large-byte multiplication overflow. | Build/lint/test gate |
| 8. Concurrency | Single-flight ownership and double-tap regression added; network/media stay on IO. | Native cancellation race still open |
| 9. Performance | Streaming retained; native dependencies unchanged; APK grows by pinned engine asset. | See measured PERFORMANCE.md |
| 10. Security | Fixed output paths, public DNS checks, no raw diagnostic URLs, no credentials. Contact uses encoded mailto and no backend. | Native advisory and exhaustion hardening open |
| 11. Android 16 | CI device matrix expanded to API35/API36. Compile/target remain 35; do not claim target36. | Record real CI outcome separately |
| 12. UI fidelity | Light default, original brand, blue actions, segmented type, rounded selection cards, progress circle/bar and green success. | Actual screenshots; no fabricated thumbnail |
| 13. Arabic | RTL-aware layout, Arabic/English resources, LTR URL field, exact supplied About description with preview caveat. | Visual inspection required |
| 14. Accessibility | Scrollable bounded width, inset/IME handling, text labels, 48dp+ interactive controls and scalable text. | TalkBack and large-font device audit open |
| 15. Regression | Direct/native sample, classification, storage, formats, contact validation, navigation and retained draft tests. | Counts recorded after execution |
| 16. Refactoring | Shared Page/Brand; service retains ownership, activity handles platform intents only. Default-folder action now releases old persisted grant. | No blanket rewrite of engine |
| 17. Independent testing | No independent tester available. YouTube playback acceptance is absent. | NOT PASSED |
| 18. CI / release | API matrix, checksums, exact tagged source, clearly labeled debug-signed preview. | Stable release prohibited |
| 19. Documentation | Size cap removal documented; historical evidence preserved. Component notices include pinned engine/EJS. | Full native corresponding-source audit open |
| 20. Release approval | YouTube, physical ARM, full SAF/process-death and native audit gates unresolved. | Development prerelease only; NOT production approved |

## Size-limit change

No fixed cap in direct transfer, native yt-dlp arguments or final output verification. Transfers still require space for private staging and public output; merging can require additional temporary space. This is not infinite physical storage. Filesystem/provider restrictions and Android background execution limits remain. Tests using sparse files or declared lengths are not multi-gigabyte network benchmarks.

## Release-critical limitations

No verified complete YouTube video/audio/playback workflow. No MP3 conversion unless the source itself exposes MP3. No resumable transfer, persistent history, process-death transaction journal, fabricated speed/thumbnail, or claim of successful email delivery. The email form opens a composer or supports copy; the user must send from their mail client.
