# ARCHITECTURE — dosos73 vNext

> Companion to [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (the 1.3.2 task-ownership, engine and
> publication guarantees, which all still hold). This file describes the layers introduced on
> branch `feature/dosos73-vnext-overhaul` and why.

## CPU ABIs are not software architecture

`armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` are the **CPU instruction sets** the native engines
(Python, FFmpeg, QuickJS) are compiled for. They say nothing about how the Kotlin code is organised.
The **software architecture** is a single one for every ABI: simplified Clean Architecture +
MVVM + unidirectional data flow, described below. ABI packaging (ARM64-first build, universal
build) is a delivery concern handled in Phase F.

## Layers

One Gradle module (`:app`), one package. Layers are enforced by dependency direction and
contracts, not by extra modules. A separate Gradle module is only justified by a measured need
(e.g. build time or reuse), which does not exist yet.

| Layer | Responsibility | Files |
|---|---|---|
| **Presentation** | Compose screens; `ViewModel`s turn events into immutable state; pure reducers | `AppRoot.kt`, `HomeScreen.kt`, `LibraryScreens.kt`, …, `MainViewModel.kt`, `AnalysisReducer.kt` |
| **Domain** (pure Kotlin, JVM-tested) | Link analysis policy and stages, file-type rules, source resolution, the unified downloads board, publication naming | `LinkAnalyzer.kt`, `FileTypes.kt`, `SourceResolvers.kt`, `DownloadBoard.kt`, `PublicationNames.choose` |
| **Engine contracts** | What presentation may ask engines to do | `EngineContracts.kt` (`LinkInspector`, `MediaExtractor`) |
| **Data / engines** | HTTP with SSRF/TLS policy, embedded media engine, durable journals, queue, service, publication | `DirectDownloader.kt`, `MediaEngine.kt`, `NetworkSettings.kt`, `Safety.kt`, `DownloadQueue.kt`, `DownloadService.kt`, `TransferProcessor.kt`, `OutputStore.kt`, `PublicationReceipt.kt`, `BatchDraftSession.kt` |

Rules:
- Presentation never imports OkHttp or the youtubedl-android runtime. `MainViewModel` holds a
  `LinkInspector` and a `MediaExtractor`, not a concrete engine.
- Domain logic has no Android dependency, except reading `Build.SUPPORTED_ABIS` in a fault-tolerant way,
  so it is covered by JVM tests (`LinkAnalyzerTest`, `FileTypesTest`, `SourceResolversTest`,
  `ArchitecturePhaseCTest`).
- State owners stay single: the board is **derived**, never stored.

## Link → file: the flow

```
 paste / share / type
        │  (MainViewModel.analyze — state: AnalysisReducer.started, stage VALIDATING)
        ▼
 Safety.url + NetworkSettings policy ──✗──▶ FAILED (invalid link / HTTP disabled / offline)
        │
        ▼
 SourceResolvers.match? ──yes──▶ RESOLVING: GitHub REST / F-Droid API ──▶ one file ─┐
        │ no                                    │ several files                      │
        │                                       ▼                                    │
        │                         READY with candidates (user picks one) ──▶ analyze │
        ▼                                                                            ▼
 CONNECTING → LinkInspector.probe (one ranged GET, ≤4 KiB, redirects re-checked) → PROBING
        │
        ├─ File  (bytes match type) ──────────────▶ READY  (verified, category, size)
        ├─ Failed 404/410/type mismatch ──────────▶ FAILED (specific reason, no engine)
        ├─ Page on a file URL / file-only host ───▶ FAILED (WEB_PAGE_NOT_FILE)
        └─ Page that may hold media ──▶ EXTRACTING: MediaExtractor (yt-dlp) ──▶ READY / FAILED
        ▼
 Start → DownloadQueue (encrypted journal) → DownloadService (dataSync FGS, one at a time)
        → TransferProcessor: DirectDownloader (resumable) | MediaEngine | offline conversion
        → OutputStore.validate (media checks; direct files: leading-byte signature)
        → PublicationReceipt plan → MediaStore / SAF → readable final name → LibraryRepository
```

A generation counter in `MainViewModel` turns every late result of a cancelled or replaced
analysis into a no-op. `StaleAnalysisTest` (instrumented) proves it.

## Unified downloads state (for screens, widget, notifications)

```
Transfers.state ─┐
DownloadQueue.tasks ─┤
FailureJournal.failures ─┼─▶ DownloadBoard.observe(): Flow<List<BoardItem>> ─▶ downloads screen (Phase D)
LibraryRepository.files ─┘                    │                              ─▶ widget, notifications (Phase E)
                                              └─▶ DownloadBoard.summarize(): counts + focus item
```

`BoardItem` carries status (ACTIVE / QUEUED / PAUSED / COMPLETED / FAILED), category, real bytes,
total, speed, and an ETA **only** when total and a current rate are known. Filtering hidden
history stays a presentation choice.

## Surfaces outside the app (Phase E)

```
DownloadBoard.observe { TransferFiles.checkpoint(it) } ─┬─▶ WidgetModel.from(...) ─▶ WidgetUpdater (≤1 redraw / 1.5 s while active)
Transfers.state (last outcome) ─────────────────────────┘                          └─▶ AppWidgetManager.updateAppWidget
DownloadService.publish ─▶ NotificationGate (≤1 / s) ─▶ DownloadNotifications.progress (FGS, id 73)
pause ack ─▶ DownloadNotifications.paused (id 75, Resume/Cancel)   processor COMPLETE ─▶ completed (74)   FAILED ─▶ failed (76)
```

- **Cold start:** `DownloadWidgetProvider.onUpdate` handles add, resize, reboot and package update. It loads the durable queue and failure journals (bounded to 8 s via `goAsync`) before drawing, so a paused lease is never shown as "idle".
- **No background work of its own:** the updater only observes flows inside a process that is already alive. `updatePeriodMillis = 0`.
- **Security:**
  - The receiver is exported only for the system's `APPWIDGET_UPDATE`.
  - Every widget and notification `PendingIntent` is explicit and `FLAG_IMMUTABLE`.
  - The Activity accepts only the page names `home` / `downloads` from outside (`AppEntry.page`).
  - Notification actions reuse the service's existing `pause` / `cancel` commands.
- **Privacy:**
  - The widget shows the file type unless the user enables names.
  - Notifications are `VISIBILITY_PRIVATE`, with a public version that never contains the name.

## What Phase C changed and why

| Change | Why | Compatibility |
|---|---|---|
| `BatchDraftSession` extracted from `MainViewModel` (verbatim actor protocol) | `MainViewModel` mixed link analysis, encrypted draft persistence and queue handoff (God ViewModel) | Same public ViewModel API; batch instrumented tests unchanged |
| `AnalysisReducer` (pure transitions) | Every UI state transition becomes testable without Android | Same `ScreenState` |
| `LinkInspector` / `MediaExtractor` contracts | Presentation was holding concrete engines | `DirectDownloader` and `MediaEngine` implement them unchanged |
| `DownloadBoard` read-model | Four state owners were stitched together in Compose code; the widget and notifications need the same view | Additive; no new storage |
| Readable saved names (`Report.pdf`, `Report (2).pdf`) | 1.3.2 always appended the task UUID | Planned name still unique among the destination's files and other pending plans, which keeps exact-name recovery unambiguous. When either set can't be read, the 1.3.2 `name-<id>.ext` scheme is used. Existing files are never renamed |

## Deliberately not changed (yet)

- **One transfer at a time.** `DownloadService` and `Transfers` are single-flight by design: leases,
  network deferral and pause/cancel races are proven for one task. Concurrent transfers need a new
  ownership model and a fresh round of fault-injection tests. Tracked as P1-1.
- **No DI framework.** Constructor parameters and the contracts above are enough at this size;
  Hilt/Koin would add build time and APK weight with no measured benefit.
