# Compact-engine acceptance matrix

The size target never overrides functional acceptance. No production engine replacement has occurred.

| Capability | Evidence / required next test |
|---|---|
| Four ABI build | Static prototype built ARM32/ARM64/x86_64; x86 PIE fix rebuilding. Shared-library variant building separately. |
| x86_64 loader | Both static ffmpeg and ffprobe execute successfully on API35 emulator. |
| MP4 remux | Existing W3C trailer copied into a new MP4 with H264/AAC streams. |
| Separate stream merge | Video-only MP4 and audio-only M4A extracted then merged; ffprobe confirms H264/AAC. |
| Decode verification | Null-output smoke test cannot run yet: wrapped_avframe encoder omitted. This is a test limitation, not a passing decoder test. |
| Actual app integration | Pending shared build, size, packaging/signature and install. |
| YouTube | Pending candidate app analysis/download/merge/playback; previous production engine success is not evidence for this replacement. |
| WebM VP8/VP9/AV1 + Opus/Vorbis | Pending fixtures and decoder/playback compatibility. Copying encoded packets does not require re-encoding, but all fixup paths must be tested. |
| HLS/DASH | Pending local fixups, fragmented MP4, timestamp handling and fallback paths. Native FFmpeg network protocols disabled in prototype; any supported input requiring them blocks adoption until restored. |
| Audio formats | Current app selects actual offered audio formats rather than arbitrary conversion. Test every currently offered M4A/Opus/MP3 path; do not claim new MP3 transcoding. |
| Subtitles | Sidecar feature remains pending. Conversion/embed fixtures needed if implemented. |
| Update | Test replacement of previously extracted FFmpeg libraries and preservation of app data. |
| 16KB | Build sets linker page-size; verify every ELF library and test in a 16KB environment. |
| Licenses | Preserve pinned source, configuration, notices and applicable relinking requirements before release. |

Local JVM suite after cancellation repair: 59 tests; lintDebug passed. ARM binaries were built but not executed on physical ARM devices.

## 2026-10-29 device evidence

The signed 72,397,560-byte universal candidate (SHA256 a2fd994b4956e6ab7b6f65b85f991fc39a3375bdd306de3c56cbda12ad5b88ba) installed successfully on a new independent API35 x86_64 emulator. All four ABI builds succeeded in run40705866559 (normalized library filenames). The tested candidate combines equivalent earlier successful builds with locally normalized library names; full source/artifact provenance remains to be packaged before publication.

YouTube link oexYI9IGKkM analysis returned qualities through2160p. Selected720p MP4: completed download/publication29.39MiB; tapping the saved entry opened the in-app preview and a video frame rendered. Screenshot: work/compact-playback.png (local evidence). No AndroidRuntime crash logged during these steps. This does not certify all codecs/devices or the whole charter. Audio and other formats remain under test.

### Additional acceptance and repair

- M4A audio download completed (4.4MiB).
- A first attempted WebM test actually produced MP4: the quality picker silently reset the container. This was diagnosed, not counted as WebM success.
- A subsequent explicitly selected720p WebM transfer completed (29.18MiB).
- Fixed quality menu to preserve selected container and audio language; two regression tests added. All61 JVM tests, lintDebug and assembleRelease passed.
- Reassembled and signed updated candidate:72,397,560 bytes; SHA256 c50a23c71464f7ddeabf0dc93b1ea7b34de8b47a540b79bfffeb32adde35ea67. Installed over prior compact candidate successfully; four downloaded entries remained available.
- Updated candidate completed direct W3C trailer MP4 download (4.17MiB).
- Playback lifecycle tested on updated candidate: media.player dump shows foreground renderer paused(0), state5; after HOME renderer paused(1), state6. Evidence in work/compact-player-foreground.txt and compact-player-background.txt.

Still not approved as full charter completion. HLS/DASH adversarial coverage, all devices/ABIs, physical ARM, native supply-chain/16KB acceptance, original stable-to-compact migration, durable scheduling/multi-active jobs and other charter items remain open. Do not publish as stable merely because the size target passed.
