# Charter feature design — UI workstream

Baseline: v1.1.2. This is a design and implementation tracker, not a claim of acceptance.

| Feature | Existing evidence | User value | Design / dependencies | Integrity and resource safeguards | Acceptance | Status |
|---|---|---|---|---|---|---|
| Batch review | ClipboardInputTest rejects multiple links | High | At most 20 links, explicit review/selection, sequential analysis, explicit enqueue | Safety.url per entry; no automatic transfer on paste; bounded input/errors per item; reuse normal engine | Mixed valid/invalid/duplicate links; no enqueue until confirmation | Implementing |
| Advanced media | Existing resolution and format menus | Medium | Render per-choice reliable size, bitrate/language supplied by engine | Never infer unavailable metadata | Metadata fixtures + UI choices | Coordinating |
| Collections / large files | LibraryScreens already filters/sorts | Medium | Preferences store URI-to-collection metadata; local filters and MIME search | No moving/deleting files; bounded collection labels | Collection filter and large-file predicate | Implementing |
| Offline preview | Existing external intent open and thumbnails | Medium | Local content URI only; bounded image decode; platform VideoView for audio/video | No remote URI; release player on disposal; unsupported-file fallback | Local valid/corrupt preview; external open fallback | Implementing |
| Privacy center | Existing single history hiding | High | Clear history separately from files; retention setting; explicit safe cache cleanup; permission descriptions | Never delete active/resumable files; no trackers; no automatic outgoing message | Clear history leaves bytes unchanged; cache exclusions | Implementing |
| Diagnostics | Existing safe codes, debug logs | High | Copy/share allowlisted version/platform/code/stage/retry guidance | Exclude URL/title/URI/exception/native stderr entirely | Secret sentinel absent from export | Implementing |
| Queue controls | FIFO queue already durable | High | Priority, move, pause, resume controls call queue APIs | Single active worker preserved; no fabricated concurrency | Ordered queue with paused entries | Coordinating |
| Network settings | No network policy switches | High | Wi-Fi/unmetered settings call service policy | Source restrictions unchanged; explanatory wait states | Network transitions | Coordinating |

APK/CPU/battery impact remains unmeasured until integration. No new third-party dependency is planned. Adaptive parallelism and HTTP resume belong to the transport workstream.
