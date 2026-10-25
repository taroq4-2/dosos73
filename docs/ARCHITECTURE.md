# Architecture

Single Android application module; Kotlin + Compose + lifecycle + coroutines. UI → MainViewModel analysis → direct HTTP probe or MediaEngine → DownloadService → private file validation → OutputStore commit → content URI.

`Transfers` synchronizes a single process-local reservation. Activity rotation does not own or duplicate the download. Foreground dataSync service starts only after a visible user action; it stops after completion, cancellation or failure. No periodic work is scheduled. Android 15 service timeout cancels transfer. A killed process is not automatically resumed: no potentially stale byte range is appended.

Android 14 UIDT jobs were evaluated. They are the preferred future mechanism for long downloads, but this preview uses a foreground dataSync service for both transfer and native media processing across API 29–35. The six-hour Android 15 limit applies. Device behavior requires verification; this is not claimed equivalent to UIDT.

Direct downloads use OkHttp with checked DNS, manual bounded redirects, timeouts, 64 KiB buffers and a 2 GiB ceiling. Media extraction initializes lazily and runs through a loopback HTTPS CONNECT proxy which resolves and pins public destinations before opening a socket. Python retains responsibility for end-to-end TLS validation. No executable self-update API is called.

The native wrapper is isolated in MediaEngine. Choices map actual format IDs into compatible original video/audio containers. Conversions to MP3/FLAC/WAV are deliberately not advertised without codec verification. Separate streams can merge with the wrapper's FFmpeg. Android MediaMetadataRetriever validates playable metadata before storage publication.

MediaStore output stays pending during copy. SAF creates a unique `.partial` document and renames after writing. Provider-specific atomicity is not guaranteed. Copy errors delete only the newly created output. The app never overwrites an existing user-selected file.
