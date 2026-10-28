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
