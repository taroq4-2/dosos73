# Reliability review — 2026-10-26

The supplied review was treated as hypotheses, not a literal implementation plan. The owner retains the no-fixed-size-cap requirement and approved light default. Official release remains the goal; it requires verified YouTube download and playback.

| Area | Change | Evidence / limits |
|---|---|---|
| Service ownership | Terminal state waits for private-file cleanup. Job reference released on main without suspension before terminal publication. | Direct download/playback regression; lifecycle stress remains open |
| Completion | Persistent completion notification with title and Open action. | Source/build; interaction matrix pending |
| Native lifecycle | ViewModel/service teardown cancels native execution; destruction dispatched off main. | Registration-race stress remains open |
| Local proxy | Random per-session Basic credentials, constant-time comparison, strict public address/port rules retained. | JVM missing/wrong/other-session credentials and address/port tests |
| Proxy timeouts | Bounded CONNECT handshake; native client's socket timeout owns tunnel idle deadline; spare pool worker. | Long idle stress pending |
| HEAD compatibility | HEAD403/405/501 fallback to GET bytes0-0; body immediately closed; real Content-Range total; consistent UA and case-insensitive MIME. | Two new MockWebServer tests |
| Audio | Highest finite compatible audio bitrate, independent of JSON order. | Reversed-order instrumented test |
| Storage | Available-space check before known-size transfer and publication, overflow-safe arithmetic. | Two policy tests; no fixed file cap reinstated |

## YouTube evidence

Repeat Android extraction failed while fetching the YouTube player script: TLS record-layer errors/incomplete reads prevented EJS from loading its input, followed by an unavailable-format error. This demonstrates the chain in that run, not a universal cause of the original403.

Controlled fixed public fixture: default HTTP handler succeeded once and failed once; legacy failed twice. Default TLS/TLS1.2 and software-crypto comparisons failed intermittently too. No production handler, TLS or CPU override was justified or applied. Diagnostic tests completing means observations were recorded, not that every transfer succeeded. Certificate verification remains enabled.

No cookies, authentication bypass, token spoofing or trust-all certificate configuration. Complete YouTube download/merge/publication/playback still has not passed. Stable release is blocked.

## Deliberate deviations from attachment

No obsolete size cap restored. No automatic scheduled workflow. No dark-default inversion of approved light design. No silent selection among multiple shared links. No generic fallback after disk/access failures. No queue-delay workaround: terminal publication moved after cleanup instead.

Native registration stress, SAF providers, process-death recovery, physical ARM, complete corresponding-source audit and independent review remain open. README and release status must describe these facts accurately.
