# Compatibility and limits

Configured minimum Android 10 / API 29, target/compile API 35. Packaged ABIs arm64-v8a and x86_64. No 32-bit-only device support. Real tested devices and scenarios are listed in TEST_RESULTS.md; configuration is not test evidence.

No universal website support claim. YouTube, TikTok, Instagram, Facebook, X, Snapchat and Telegram must each receive actual URL tests before being listed as verified. Login-required, DRM or private media are outside scope. Current wrapper engine freshness may affect extraction.

No resumable transfer or pause control. Retrying starts over. No extra output conversion, batch queues, playlists or background clipboard reading. Media output must be accepted by Android's metadata parser, so some otherwise valid containers may be rejected. A HEAD probe may not identify servers that only support GET; these go through extraction and may fail.

Metadata is capped at 4 MiB; each output at 2 GiB; one download per process. A private staging copy plus destination copy requires sufficient space. SAF folder behavior differs by provider. Android 15 foreground transfer time limits apply.

The source artwork is a presentation image. The faithful central crop retains small text and marks that can be difficult to read at launcher size. No monochrome variant was invented. Further brand refinement requires approval.
