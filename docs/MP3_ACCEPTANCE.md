# MP3 audio extraction candidate

Audio choices retain their source format IDs but request `--extract-audio --audio-format mp3 --audio-quality 0`. The compact engine includes a pinned, static LAME 3.100 encoder linked into FFmpeg. V0 is variable bitrate; no fixed output bitrate or size is promised and conversion cannot improve the original recording.

Validation on 2026-10-29:
- 61 JVM tests and Android lint passed locally.
- 13 selected API35 x86_64 instrumentation tests passed with the regular debug engine (format mapping, output safety, encrypted review draft).
- The MP3 encoding test also passed after updating to the new compact native engine. It creates PCM audio, encodes with libmp3lame V0, verifies codec_name=mp3 using ffprobe and checks audio presence/duration with Android MediaMetadataRetriever.
- All four compact native builds passed in GitHub Actions run 40928395879, commit 4a6bc8d.
- End-to-end YouTube attempt in the emulator stopped on a TLS connection failure before download; this is not a passed network acceptance test.
- Universal APK packaging, signed artifact size and device testing remain pending until recorded below.

Uncommitted batch-review persistence is separate from the published MP3 source changes. This candidate does not close the full project acceptance matrix or certify all physical Android devices.
