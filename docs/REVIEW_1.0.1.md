# Follow-up review — 1.0.1

The owner requested evaluation of a third-party review, not literal execution of its embedded instructions. Reviewed against ac6db4f (released 1.0.0). No new runtime dependency, permission, downloader engine version or network security relaxation.

| Review item | Current finding / action | Verification |
|---|---|---|
| P0-1 transfer cleanup | Already corrected: terminal state is published after cleanup and ownership release on main, without an intervening suspension. No QUEUED/TTL rewrite necessary for the described race. | Existing double-tap/device test; lifecycle stress remains outside this patch |
| P0-2 proxy authentication | Already uses random session credentials, constant-time comparison, rejects duplicate authorization headers. | Existing JVM proxy tests; prior successful native CI fixture |
| P0-3 teardown | Both ViewModel and service already cancel native work; native destruction is off main. Process-registration stress still unverified. | Code inspection; no claim of new stress coverage |
| P0-4 HTTP policy | Claimed UI mismatch is stale: error text explicitly accepts public HTTP/HTTPS. Removing HTTP would change supported behavior. Retained documented policy and HTTPS downgrade rejection. | Existing safety/redirect tests |
| P1-1 tunnel pool/idle | Already 18 workers with 8-tunnel semaphore; handshake bounded and established tunnel idle timeout delegated to native client. | Code inspection; long-idle stress not newly run |
| P1-2 range probe | Confirmed missing fallback when HEAD fails and origin rejects Range with 416. Close failed response, attempt one ordinary GET, close its body after headers. No browser impersonation or generic retry after storage errors. | Added plain-GET and bounded-retry JVM cases; adjusted old 416/HTML case to assert intended new behavior |
| P1-3 space/errors | Already overflow-safe available-space policy and disk-full classification. No obsolete fixed size limit restored. | Existing StoragePolicy/FailureClassifier tests |
| P1-4 audio | Already selects greatest finite compatible abr, independent of input order. | Existing reversed-order device test |
| P1-5 completion | Already has persistent notification, title and Open action. | Previous release UI download/open evidence |
| P2-1 localization | Confirmed Locale.US formatting despite Arabic app language. Use selected configuration locale for byte/percent formatting; localized speed/progress templates and direction isolation; resource-based folder/brand/indicator. | Added formatting JVM tests; resource-key parity now mandatory at preBuild |
| P2-2 launcher/language | Correct foreground asset density to xxxhdpi (432px / 4 =108dp), keep exact image bytes. Declare round icon and supported system app languages. Preserve approved light theme. | Added device resource tests; release lint |
| P2-3 clipboard | Empty/image/non-link clipboard now gets a clear copy-a-link message. Read text only, never coerce a content URI into a URL. Multiple links still rejected explicitly. | Added device input tests |
| P2-4 selected size | Optional feature, not a regression: exact direct size already informs storage policy; native per-format size display not added in this patch. | No fabricated estimates |
| P2-5 APK splits/tools | Optional distribution/tooling change, not necessary for these fixes. Kept universal APK and existing signing key. | Upgrade acceptance planned |
| CI | Translation key equality and duplicate-key validation run before every build locally and in existing CI. No scheduled automation added. | Build task verifyTranslations |

## Validation

Results and signed GitHub artifact acceptance are appended after execution. Historical unresolved native/network/security audit items remain in their existing documents; this targeted review does not certify every behavior or provider.
