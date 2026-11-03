# Android architecture, lifecycle and accessibility review

Mission date: 3 November 2026. Inspected baseline: `1e46c56ce6028bdbae51ae1f80091115cd614e0b` on `release/production-readiness`. This is the Android engineering, architecture and UI/accessibility workstream of the virtual audit. Source findings below are independent of the historical CI result; the new regression results belong in [TEST_EVIDENCE.md](TEST_EVIDENCE.md).

## ARC-UI-01 — incoming share could trap a user during draft recovery

- **Severity:** MEDIUM. The affected window blocks normal app navigation, but the finding does not show durable draft corruption or automatic download.
- **Evidence at the baseline:** `AppRoot.kt:609` showed a modal as soon as an incoming share was staged and analysis was idle. Its `onDismissRequest` did nothing and the only button invoked `MainViewModel.acceptPending()`. At `MainViewModel.kt:248`, that action correctly refused to proceed while the persisted batch draft was being restored, saved or awaiting recovery after a storage error. The presentation ignored the save/recovery flags, so it could display a dialog with no usable exit while the retry control remained behind it.
- **Reproduction:** With a failed draft restoration, share a link to the app. The existing `BatchHandoffTest.failedDraftReadKeepsIncomingShareUntilExplicitAcceptanceAfterRetry` creates an isolated readable journal with an invalid schema to reach this state without altering user storage. On the baseline, with analysis idle, the pending-share dialog appears. Review is a no-op, Back does nothing, and the user cannot navigate to batch Retry. When analysis finishes normally, the same dialog also offers no way to keep the current result and decline the new share.
- **Change:** `AppRoot.kt` now delegates to `DownloadReviewDialogs.kt::PendingSharedReview`. The modal waits while analysis, draft saving or recovery blocks review. When shown, its Cancel action or system Back discards only `pendingShared`; an accidental tap outside does not discard it. `MainViewModel.kt::edit` preserves a staged share while the user works on the current input. A newly accepted single share still replaces the input and starts its usual analysis. English and Arabic hints explain the choice.
- **Preserved boundaries:** No queue handoff, permission, persistent draft or I/O behavior moved. The staged share remains bounded as before. Cancel leaves the current URL, analysis, format selection and batch draft intact. No theme, dependency, service or manifest changes.
- **Regression coverage:** `ReviewLayoutTest.pendingShareDoesNotCoverDraftRecovery` covers saving, an actionable underlying retry control, and a recovered review action. `UiFlowTest.cancellingPendingShareKeepsCurrentAnalysisAndSelection` drives the actual Activity and pauses injected analysis without a live network. The existing isolated batch fixture now also verifies that editing during a storage error does not drop the staged share. Implementation is complete in the worktree; check the new run tied to the exact integrated commit before marking the gate passed.

## Ownership review

| Boundary | Source evidence and risk decision |
|---|---|
| Activity and saved state | `MainActivity` owns locale, `singleTop` share delivery, and a resumed/focused/unlocked clipboard entry. It consumes the intent text and restores bounded ordinary input. Quick clipboard text stays in the retained ViewModel for recreation and is excluded from the saved instance bundle. The pending-share correction keeps that boundary. |
| ViewModel and analysis | `MainViewModel` owns the analysis generation and uses `viewModelScope`, cancellation and a cleanup gate before reusing analysis engines. Draft commands serialize durable writes/handoffs and reject stale snapshots under the protocol lock. No separate Compose transfer worker was added. |
| Compose lifecycle | `AppRoot` collects state with lifecycle awareness and retains navigation, search and pending permission snapshots with `rememberSaveable`. Per-page saveable state and navigation callbacks are separate from durable queue ownership. Root still coordinates several features; splitting it further without a focused ownership/regression reason would not resolve this fault. |
| File library presentation | `SavedFileRow` uses a `LaunchedEffect` keyed to URI/date/refresh and a started lifecycle to inspect availability and request a thumbnail. File browsing is lazy. This review did not certify all provider latency or memory cases; physical scrolling and cloud provider testing remain separate. |
| Android service boundary | Queue persistence and foreground transfer ownership remain in `DownloadQueue` / `DownloadService` and are covered by the download workstream. The Activity can recreate without making a second transfer owner. |
| Accessibility | `IconAction` supplies names and 48dp minimum bounds, switch rows expose `Role.Switch`, and transfer/error state has polite announcements in existing screens. The pending modal now has explicit choices and does not cover unavailable review. Retain the established dark palette and navigation order. An automated semantics assertion does not substitute for a TalkBack session. |

Related evidence: [prior readiness QA](../../QA_TEST_MATRIX.md), [scope of UI and performance testing](../readiness/ARCHITECTURE_UX_REVIEW.md), [compatibility limits](COMPATIBILITY_MATRIX.md).

## Open checks and reproduction

Use a disposable test installation with matching app/test signatures. The release candidate already installed on a user profile may use a different certificate from default debug outputs; never uninstall it to make this run pass.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
adb -s "$SERIAL" shell am instrument -w -r -e class 'com.os73.dosos73.ReviewLayoutTest,com.os73.dosos73.UiFlowTest,com.os73.dosos73.BatchHandoffTest' com.os73.dosos73.test/androidx.test.runner.AndroidJUnitRunner
```

Manual accessibility still **NOT VERIFIED** for this revision: enable TalkBack on the intended device; switch between Arabic and English and use 200% text; share a link during analysis and reach Review and Cancel by linear navigation; use system Back and verify the original result remains; also navigate the queue and file action menu. Repeat with a narrow portrait window and actual landscape window. Record device model, API, ABI, font setting and any missing announcement or unreachable action. External keyboard, Switch Access, foldable hinge layouts, tablet/desktop resizing and broad OEM testing remain open.
