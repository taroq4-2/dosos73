# Universal APK size work — 2026-10-28

Acceptance target: ONE signed APK below 80,000,000 bytes, all four current ABIs, without regression in supported application functionality. No first-use runtime download. This target is NOT yet achieved.

Baseline public APK: 204,892,299 bytes. Embedded compressed FFmpeg packages dominate (~138.5 MB); Python packages account for ~54.4 MB. R8/resource shrinking are already enabled.

`tools/trim_universal_apk.py` is an experimental unsigned postprocessor. It removes only `usr/lib/quickjs/libquickjs.a` from each Python archive and verifies all retained entries byte-for-byte. Static link archives are not runtime shared libraries. Result before signing/alignment: 198,138,835 bytes. This modest saving is not the requested outcome and has not yet passed device runtime acceptance. Never distribute the unsigned output.

`compact-engine.yml` builds a separate experimental FFmpeg/ffprobe for all four ABIs using NDK28.2 and pinned FFmpeg9.0.2 source commit946fcce07b6dcd0331c8cc609192aeff5e1924f8. It does NOT change production dependencies or published APKs. The candidate retains container demux/mux, parsers and bitstream filters with selected codecs; no claim of functional equivalence is made. Network I/O remains in the downloader candidate design. Before integration, prove yt-dlp never needs disabled FFmpeg protocols for supported inputs, and extend the build if needed. All existing application functions, including YouTube separate-stream merge, MP4/WebM, audio outputs, HLS/DASH fixups, metadata and planned subtitles require comparison fixtures. Any failure blocks replacement. Validate ELF dependencies, 16KB alignment, license/source distribution and actual runtime on every available ABI.

Local build host currently has no NDK/Linux subsystem/Docker, so native experiment uses GitHub Actions. No system installation or published release replacement is part of this experiment.

Cancellation repair: check cancellation after readiness gates in parallel transfer/merge and immediately before atomic replacement. Added deterministic EOF-merge cancellation regression test, verifying no output publication, no sequential fallback and cleaned temporary segments. ParallelDownloaderTest suite passed locally. This does not close all native cancellation-registration races.

Official references:
- https://developer.android.com/topic/performance/reduce-apk-size
- https://ffmpeg.org/download.html
- https://github.com/yausername/youtubedl-android/blob/master/BUILD_FFMPEG.md
