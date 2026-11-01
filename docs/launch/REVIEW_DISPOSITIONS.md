# Maintainer dispositions of reviews01–60

**Current release follow-up —1November2026:** source `72783e39d2704f18722a91040443a51f88adb245` includes the subsequent08/16/21 repairs and large-font layout refinements. Full build/lint and106JVM tests passed; API29–36 source matrix passed in run41263479501. Exact APK identity, minified acceptance and source-download receipts are maintained in [verification](../VERIFICATION.md). Earlier snapshots below are historical review/triage evidence and do not override that release record.

Reviewer08: encrypted write-ahead SAF rename recovery and14regressions address index/preference failure; ambiguous or uncooperative provider responses still fail closed. Reviewer16: only two historically plaintext journals can migrate through authenticated pending/sealed format markers; established records reject plaintext downgrade, with11format regressions. Reviewer21: fifteen-second waiting releases foreground work; one persisted eligibility reminder opens the app for resume, with4service/constraint/cancellation regressions. These replace the corresponding prior open statuses below, while exact-time/background auto-download and physical/OEM limits remain explicit.

Review inventory snapshot: **31 October 2026**, after source/test/review commit `3f01fd7d13b27ee1c9d3e0b0ccd2f34727eb625e` was pushed. CI for that commit is starting; its result remains pending. All **60 original AI review reports** are preserved in [reviews/](reviews/). They describe different source snapshots and scopes; their original findings, reproductions, test gaps and limitations have not been rewritten to imply later success. These are AI source reviews, not 60 human audits or independent certification.

This document records maintainer triage. **It does not declare every finding closed or the release ready.** “Implemented” below means a source change addresses the reported path; acceptance remains limited to the named checks and the exact source/artifact tested. A report with no new proven finding does not certify its untested cases.

## Verification snapshot

- The latest source checks reported **106 JVM passes**, with the full Debug and Release lint/build checks passing.
- Focused JVM suites for review41/45/47 reported **4/9/3 passes**, respectively: proxy lifecycle, network recovery and runtime preparation.
- The first new **116-case local instrumentation run had one failure** in `UiFlowTest.aboutContactAndDraftSurviveNavigation` while finding the contact node. The same case passed separately. After adding a bounded readiness wait without removing assertions, the corrected full rerun **passed all116 cases with zero failures** (`work/launch-recovery-device-verified-suite.txt`). The first failed run remains historical evidence, not a pass.
- Review51’s **10 new instrumentation regressions**, the new destination preflight regression and network-registration stress case passed, including in the corrected full116-case run. Actual current-source **Debug YouTube720p video (40.774s) and MP3 audio (24.1s) opt-in cases also passed**; these are Debug results, not final signed minified acceptance.
- Actual YouTube720p, MP3 and direct W3C downloads were accepted on the **older** signed artifact SHA-256 `95ea86067e2dcc73bbca85f631566e2ed6474d4e4e0309ba045fd5aead7969dd`, **62,018,329 bytes**. Those results bind to that artifact and its older source; they do not accept the newer fixes.
- Source/test/reviews are pinned at the commit above. Its API matrix is pending; final source-pinned universal packaging/signing is in progress. The final signed minified APK and its artifact-bound acceptance remain separate gates. See review60 and [the launch checkpoint](CHECKPOINT.md).

No build, test, device action, staging or commit was performed to author this disposition document.

## Repairs and their regression evidence

**A — Native ownership and service controls (01,03,35).** `NativeProcessSession` authenticates process-group identity and checks ownership independently of leader liveness; cleanup remains bounded and fails closed rather than signaling an unverified group. `NativeCancellationTest.exitedLeaderCannotLeaveAHelperHoldingItsOutputPipe`, `mainThreadCancellationNeverWaitsForTheGroupCreationGate` and `aRecordNamingAnotherLiveGroupCannotKillThatSession` exercise the repaired boundaries. Cold Resume/Cancel now handle a persisted paused lease without a live worker, covered by both `ColdServiceControlTest` cases. `DownloadQueue.rememberActive` preserves the current lease’s pause fields rather than replacing them with a stale worker snapshot; `PauseStartupRaceTest` covers the ordering. Remaining group-proof/cache/OS limits in review01 are retained.

**B — Network boundaries, recovery and input (02,05,14-01,17,39,41,45,54).** Literal destinations are checked before socket creation, every encoding header is inspected, analysis cancellation closes its transport, and recovery observes the bound network as well as default-network changes. `LiteralNetworkBoundaryTest` asserts zero DNS/socket attempts for forbidden literals and tests a private redirect; `HeaderRepresentationTest` covers repeated/conflicting representation fields. `BatchHandoffTest.analysisGuardianCancelsDirectCallOnTimeout` and its scope-cancellation counterpart cover blocking-probe cleanup. `NetworkRecoveryTest.acquisitionFlapReselectsWithinTheSameBoundedRecovery`, `oldBoundLossRetriesWhileTheNewDefaultRemainsPermitted`, registration-before-transport and cleanup cases cover the new recovery protocol. `NetworkRegistrationTest.repeatedSubscriptionsReleaseSystemCallbacks` supplies Android callback stress evidence. `ProxyLifecycleTest` covers absolute header deadlines, silent-origin reclamation and continuing downstream traffic; its four focused JVM cases passed. These deterministic checks do not certify real Wi-Fi/cellular handover on every device.

`ReviewInputRegressionTest` covers canonical schemes, prose delimiters, a maximum-length delimiter suffix, terminal-error selection and exact call-timeout classification without treating ordinary interruption as timeout. Network guards receive the analysis stage at analysis call sites. The longer input case verifies behavior, not a measured ANR or battery threshold.

**C — Draft handoff and private records (14-02,15,16-01,36).** Batch rows have durable delivery IDs, acceptance receipts commit with queued tasks, receipt cleanup follows durable draft removal, and actors compare full snapshot tokens under a consistent protocol→queue lock order. `BatchHandoffTest` covers failed acceptance/removal, cold reload, stale actors, retry and pending-share retention. `BatchChoiceUiTest` was adapted to the suspending `queueBatch` API and draft readiness; its former removed-API call is not retained as a passing test. `PrivateJournal` bounds encoded reads to16MiB before allocation/decryption/migration. `FileDigestUiTest.oversizedPrivateJournalFailsWithoutOverwritingIt` and `ConcurrentJournalTest` cover oversized preservation and separate-instance serialization. **The legacy-plaintext trust-boundary issue from16-02 remains accepted, not fixed**, as detailed below.

**D — Publication truth and resume accounting (06,07,18,37).** Recorded-URI recovery requires destination membership/ownership and a matching full fingerprint; normalized names no longer require another rename once ready. Commit callbacks preserve COMPLETE across cancellation, a cancellation checkpoint precedes publication, and a fallible post-commit reopen was removed. `PublicationCommitRaceTest` covers cancellation during verification, user/system cancellation after rename, unavailable storage after rename, normalized ready/staged records and ambiguous/changed contents. `PublicationRecoveryTest` retains controlled creation/rename/unknown-size failures. Recovery carries verified byte counts instead of indexing unknown-size output as0B. `ResumeSpaceTest` covers retained-byte credit while preserving publication reserve. Real provider disconnection/ENOSPC matrices are still open.

**E — Library, destination and retained data (08,11,51,52,55).** Rename metadata queries are optional, returned URIs survive in memory and successful index writes, hidden/collection URI references migrate, and hidden-history updates share a mutex. `LibraryMutationTest` covers failed name queries, failed collection migration, hidden/cleared history through URI-changing rename and concurrent hides/clear. Visible collection chips derive from current indexed members; confirmed-missing file operations are disabled and filtered-empty wording differs from a genuinely empty library. **A private-index write failure followed by process death after an URI-changing SAF rename remains unresolved.**

Failure eviction now coordinates queue ownership before deleting exact app-owned inactive partial directories; explicit delete/confirmed-missing forget prune only their hidden-history tombstones. The seven `FailureRetentionRegressionTest` cases plus three `HiddenHistoryPruningRegressionTest` cases passed in the corrected full116-case run, including write failure, unreadable queue, queued/paused ownership, concurrent queue commit and cancellation while awaiting ownership. Sharing uses indexed MIME in `ClipData` without a synchronous provider MIME query. SAF preflight requires explicit directory-create capability with external-grant and same-UID handling; `OwnedProviderPreflightTest.writeGrantWithoutDirectoryCreateCapabilityIsRejectedBeforeCreatingAnything` passed. Broader provider and receiving-app share acceptance remain limited.

**F — UI, formats and small product changes (09,10,12,13,20,22,23,33).** Source shortcuts scroll, URL semantics persist, tool tiles declare Button roles and phase transitions expose a polite live region. Arabic text corruption identified in13 was corrected. CharSequence shares are consumed correctly; typed/pending URL, file-action text and expected-hash input have restoration paths. `FileDigestUiTest` covers real-byte hashing/comparison, digest restoration and CharSequence delivery; `RotationStateTest` covers typed-link orientation retention. These do not establish full TalkBack or physical-device accessibility acceptance.

`MediaEngine.audioChoice` uses consistent converted-audio labels/unknown converted size, and `formatSelector` treats advertised IDs literally. `FormatSelectorTest` covers reserved IDs, malformed selectors and original/converted MP3 metadata. The optional on-demand SHA-256 proposal from09 is implemented in `FileDigest`/`FileDetailsContent` with explicit compute/compare/copy controls. Its “next task” explanation and grouped waiting-task pause proposals remain optional deferred improvements, not silently completed work.

**G — Packaging, provenance and workflow handoff (25,26,28,30,47,57).** Assembly validates ELF class/machine per ABI and emits an APK-payload-bound runtime proof; SBOM generation rejects mismatched engine evidence instead of assigning constant engine identities to arbitrary APKs. The Readline/History8.3.6 replacement mapping and source material are prepared, with runtime/source limits stated in [NATIVE_SOURCES.md](NATIVE_SOURCES.md). This preparation does not complete the final recipient source offer.

`RuntimePreparation` avoids repeating cache invalidation after an upstream singleton initialized successfully; `RuntimePreparationTest` has three focused passing JVM cases, including later-stage retry. The compact workflow now uploads its generated `.runtime-proof.json` beside the aligned unsigned candidate, and [COMPACT_ENGINE.md](../COMPACT_ENGINE.md) supplies the current mandatory version flags and alignment-report argument. Review57’s workflow/recipe source repairs are implemented; a new workflow-produced final artifact has not been accepted merely by changing those files. Maven archive hashes remain build-input evidence, not DEX attestation.

## Explicit residuals, accepted boundaries and deferrals

| Reports | Disposition still open |
|---|---|
| 08; rechecked56 | **Residual defect:** after an URI-changing SAF rename, a failed private-index write followed by process death can lose the durable returned URI. The session-only preservation fix does not supply a durable rename intent/recovery transaction. Do not describe all rename failure cases as closed. |
| 16-02 | **Accepted private-file trust boundary, not repaired:** plaintext beginning with JSON delimiters is still eligible for legacy migration. Valid forged/replacement plaintext is not rejected through GCM authentication. The16MiB read cap addresses16-01 only. This does not demonstrate a remote replacement path. |
| 21-F1 | **Deferred feature:** `notBefore` is a persisted eligibility barrier, not OS-backed wakeup scheduling. Time passing alone does not wake a stopped queue. Multiple active files and subtitle selection likewise remain ledger gaps. |
| 21-F2 | **Open lifecycle/resource limitation:** WAITING can retain the dataSync foreground worker without a local timeout/rescheduling policy. The OS `onTimeout` handler is not a local wait deadline. No all-day idle/battery acceptance is claimed. |
| 25 | **Distribution evidence gate:** Readline8.3.6/source mapping and notices are prepared, but final release-bound corresponding-source delivery beside the APK is pending. Exact CPython producer-lock correspondence remains unavailable. No legal certification is asserted. |
| 26 | Engine/APK proof enforcement is implemented. **Maven archive digests identify build inputs, not independently attested deployed DEX.** Keep that scope explicit and bind regenerated evidence to the final release build; do not claim complete deployed transitive attestation. |
| 27,60 | **Final-artifact documentation/acceptance pending:** RC5 records and older launch-candidate results remain historical. Final code12 hash, certificate, size, clean/update acceptance, images, source offers and public-redownload receipt must match the actual published replacement. |
| 01,04,07,19,24,29,31,38,40,43,44,46,48,55,58 | Original scoped gaps remain visible: fail-closed native cleanup, crash windows, uncooperative providers, receiving apps, long/unknown-size streams, native-helper isolation, fresh minified runtime and physical device/provider/codec coverage are not all established by source inspection. |

## Reviewer-by-reviewer disposition index

Each link points to the preserved original report. Group letters refer to the source changes and scoped evidence above. “No new finding” preserves a review result; it is not acceptance of every gap listed by that reviewer.

| Review | Maintainer disposition |
|---|---|
| [01](reviews/reviewer-01.json) | A implemented; native ownership proof and bounded cleanup limitations retained. |
| [02](reviews/reviewer-02.json) | B implemented; literal/redirect zero-socket regression. |
| [03](reviews/reviewer-03.json) | A implemented; both cold Resume and Cancel have regressions. |
| [04](reviews/reviewer-04.json) | No new finding; direct-resume crash/range coverage gaps retained. |
| [05](reviews/reviewer-05.json) | B implemented; repeated encoding/header rejection regression. |
| [06](reviews/reviewer-06.json) | D implemented; recorded-URI recovery and committed cancellation regressions. |
| [07](reviews/reviewer-07.json) | No new distinct finding; normalized-URI overlap with06 addressed inD; broad provider gaps retained. |
| [08](reviews/reviewer-08.json) | E partially repaired; durable rename disk-failure/process-death residual explicitly open. |
| [09](reviews/reviewer-09.json) | F hash proposal implemented; other optional queue proposals deferred; receiving-app share acceptance gap retained. |
| [10](reviews/reviewer-10.json) | F scrolling repair implemented; full large-font/device visual acceptance remains scoped. |
| [11](reviews/reviewer-11.json) | E collection/missing-action/filtered-empty source repairs implemented. |
| [12](reviews/reviewer-12.json) | F semantic-role/URL/status repairs implemented; full TalkBack acceptance not claimed. |
| [13](reviews/reviewer-13.json) | F identified Arabic text corrected; no claim of exhaustive documentation language QA. |
| [14](reviews/reviewer-14.json) | B/C implemented; timeout transport cleanup and pending-share storage-error regressions. |
| [15](reviews/reviewer-15.json) | C implemented; stable handoff identities/receipts and failure/restart regressions. |
| [16](reviews/reviewer-16.json) | C bounded-read repair implemented; plaintext legacy trust boundary accepted, not fixed. |
| [17](reviews/reviewer-17.json) | B exact timeout classification and analysis-stage propagation implemented. |
| [18](reviews/reviewer-18.json) | D retained-byte preflight and verified recovered-size repairs implemented. |
| [19](reviews/reviewer-19.json) | No new finding; actual preview-dialog/provider/codec lifecycle coverage remains limited. |
| [20](reviews/reviewer-20.json) | F CharSequence share repair and regression. |
| [21](reviews/reviewer-21.json) | OS wakeups deferred; WAITING foreground retention/local-timeout limitation remains open. |
| [22](reviews/reviewer-22.json) | F converted-audio metadata repair and regression. |
| [23](reviews/reviewer-23.json) | F literal format selector repair and reserved-ID regression. |
| [24](reviews/reviewer-24.json) | No new finding; G improves provenance/ABI checks without closing all native acceptance. |
| [25](reviews/reviewer-25.json) | G source mapping prepared; final source-offer delivery gate remains open. |
| [26](reviews/reviewer-26.json) | G engine-proof check implemented; Maven-input/DEX evidence boundary retained. |
| [27](reviews/reviewer-27.json) | Historical-artifact distinction retained; final code12 verification docs pending. |
| [28](reviews/reviewer-28.json) | G ABI validation implemented; overlaps30. Final artifact checks still required. |
| [29](reviews/reviewer-29.json) | No new finding; fresh minified runtime acceptance must bind to final artifact. |
| [30](reviews/reviewer-30.json) | G ABI-validation duplicate of28; one repair, not a second independent closure. |
| [31](reviews/reviewer-31.json) | No new finding; actual OEM backup/intent behavior outside static review. |
| [32](reviews/reviewer-32.json) | No new finding in compatibility scope; no all-device certification. |
| [33](reviews/reviewer-33.json) | F restoration source repairs; scoped digest/orientation evidence, not universal process-death acceptance. |
| [34](reviews/reviewer-34.json) | No new finding in service/pipeline follow-up;21 limits remain open. |
| [35](reviews/reviewer-35.json) | A stale lease/pause repair and startup-race regression. |
| [36](reviews/reviewer-36.json) | C test integration repaired for suspending handoff/readiness; production protocol had no new finding. |
| [37](reviews/reviewer-37.json) | D pre-publication cancellation and post-commit failure repairs with regressions. |
| [38](reviews/reviewer-38.json) | No new finding; hashing cancellation/blocked-provider gaps retained. |
| [39](reviews/reviewer-39.json) | B canonical scheme/prose delimiter repairs with regressions. |
| [40](reviews/reviewer-40.json) | No new finding; provider Unicode/filename acceptance remains limited. |
| [41](reviews/reviewer-41.json) | B proxy deadline/reclamation repairs; four focused JVM passes. |
| [42](reviews/reviewer-42.json) | Completed resumed review, no new finding; address/native coverage limits preserved. |
| [43](reviews/reviewer-43.json) | No new finding; redirect/target-binding coverage gaps retained. |
| [44](reviews/reviewer-44.json) | No new finding; helper-level header tests do not certify every end-to-end response case. |
| [45](reviews/reviewer-45.json) | B acquisition/handover repairs; nine focused JVM passes and callback stress evidence. |
| [46](reviews/reviewer-46.json) | No new finding in native teardown follow-up; fail-closed limits retained. |
| [47](reviews/reviewer-47.json) | G runtime retry repair; three focused JVM passes. |
| [48](reviews/reviewer-48.json) | No new finding in bounded-memory/resource review; no long-session measurements. |
| [49](reviews/reviewer-49.json) | No new finding in repeated network-operation/analysis cancellation scope. |
| [50](reviews/reviewer-50.json) | No new finding in progress/notification throttling; no battery/timing measurement. |
| [51](reviews/reviewer-51.json) | E eviction/tombstone repairs; ten new instrumentation regressions passed, including corrected full116-case run. |
| [52](reviews/reviewer-52.json) | E provider-free share ClipData construction implemented; receiving-app acceptance remains separate. |
| [53](reviews/reviewer-53.json) | No new finding in notification-intent/service-lifecycle scope. |
| [54](reviews/reviewer-54.json) | B linear delimiter cleanup implemented; maximum-input functional regression. |
| [55](reviews/reviewer-55.json) | E explicit create-capability preflight repair; new scoped regression passed. |
| [56](reviews/reviewer-56.json) | No new distinct finding;08 durability residual preserved rather than counted twice. |
| [57](reviews/reviewer-57.json) | G runtime-proof artifact handoff and current recipe repaired; downstream final evidence still pending. |
| [58](reviews/reviewer-58.json) | No new finding; negative-test/source comparison is not a fresh test run or penetration test. |
| [59](reviews/reviewer-59.json) | No new proven feature-claim mismatch; explicit ledger gaps remain unimplemented/unverified. |
| [60](reviews/reviewer-60.json) | Launch acceptance not established at its snapshot; final source/artifact/evidence/publication gates remain open. |

The preserved review index documents actual work and scope; current acceptance is defined by the artifact-bound verification record. Deferred features and physical/provider coverage are not silently treated as completed.

## Final-integration follow-up

After the review inventory was completed, the maintainer implemented further repairs for08,16 and21. Their new instrumentation tests and final signed-artifact results must be recorded before changing the historical disposition above. The earlier snapshots are preserved as review history, not as the status of a newer APK.
