# Release readiness — dosos73 1.3.0-rc.2

Execution date: 2 November 2026. Working branch: `release/production-readiness`.
Baseline code: `bcfcdbdf46ba0f9e18dcfad50fa8fb8220312b9e` (1.3.0-rc.1),
not the audit's older 1.2.0. Default branch inspected at
`2efeb7f1271516b13c3fc2ab767fe81753df81ac`; tag/public release v1.2.0 retained.
The ten open entries inspected were dependency-update pull requests, not ten
confirmed application defects. Baseline source ZIP and authenticated repository
snapshot are retained outside the distribution under `work/readiness-baseline-*`.

## Executive decision

Production **NO-GO** until the native component/provenance and physical-device
acceptance gaps and owner-controlled Play requirements are satisfied. Local
implementation and test-candidate preparation continue independently. No public
release, Play upload, signing-key change or stable-release deletion is performed.

## Traceability register

Test execution results and exact artifact identity are consolidated in
`QA_TEST_MATRIX.md`, `PERFORMANCE_BASELINE.md` and the candidate verification JSON.
An added test is not called passed until its executed result is recorded there.

| ID | Classification / priority | Verified baseline / exact change | Virtual owner and disposition |
|---|---|---|---|
| A01 | Confirmed compliance gap / P0 store | compile/target35→36, min29 retained; supported AGP/Gradle/Java; existing edge-to-edge/BackHandler/FGS timeout retained, horizontal safe insets added | 01,25,35,39; new API29–36 execution required |
| A02 | Confirmed delivery gap / P0 store | `bundleRelease`, pinned bundletool, `assemble_compact_bundle.py`, `verify_bundle.py`; same runtime bytes as universal APK, external signing | 36,37; validate signed candidate and derived delivery APKs |
| A03 | Test-coverage gap / P1 | No attached physical ARM/OEM devices; no manufactured pass | 25,40; NOT RUN, owner device gate |
| A04 | Verified URI correctness defect + coverage gaps / P1 | `PublicationReceipt.publish`: validate returned authority/tree/canonical document URI before commit; keep plan/verified file on ambiguity | 18–20 +06; boundary regression and 100 controlled reconstruction runs; true ENOSPC/cloud/OS-kill NOT RUN |
| A05 | Security hypothesis / P1 | Global cleartext flag necessary for existing per-user arbitrary HTTP opt-in; default deny and native HTTPS-only preserved | 07,08,11; retain and test application-layer policy, no TLS weakening |
| A06 | Security hypotheses + provenance gap / P1 | Fresh exact runtime/build coordinate advisory gates and artifact-bound SBOM; native OpenSSL/CPython/Expat review identifies unresolved upstream fixes/producer lock | 10,16,17,38; OPEN production gate, see security report |
| A07 | Performance opportunity / P1 | Host-side raw startup/PSS/frame harness; thresholds established before paired comparison | 26–30; emulator scope only; physical/battery/soak NOT RUN |
| A08 | Confirmed CI gap / P1 | Release lint, AAB identity/ABI/mapping checks, checksums/build identity, fail-closed advisory tools, SHA-pinned actions, read-only token | 36,37; exact CI run/result in QA matrix |
| A09 | Optional product expansion / P2 | Preserve one independently owned active task; no thread-count shortcut | 13,14,19; DEFERRED until multi-owner design and storage/notification tests |
| A10 | Optional capability / P2 | Keep persisted eligibility reminder and user-triggered resume; no exact-time/automatic-start claim | 15,34; existing honest wording retained |
| A11 | Reliability verification / P2 | Retain strict Range/ETag/checkpoints, 200/206/416 rejection/restart and owned native process cancellation | 11,12,16,17; execute existing coverage; extractor full-resume not advertised |
| A12 | Performance/maintenance opportunity / P2 | Split stateless navigation and three review dialogs out of root, shared compact layout; state/launchers/service ownership unchanged | 01–05; callback equivalence cross-reviewed by root; regression required |
| A13 | Verified layout defect + coverage gaps / P2 | Horizontal cutout/navigation-bar protection; scroll long details with accessible close; RTL 200%/48dp/landscape regression | 31–35; manual TalkBack/Switch Access NOT RUN |
| A14 | Size opportunity / P2 | Shared Python plus unchanged required local engines; four-ABI universal remains; same compact runtime transferred into AAB with source matching checks | 30,37; final bytes/splits/install measurements in performance record |
| A15 | Intentional compatibility restriction / P3 | Retain public ports80/443, native443; no arbitrary expansion to8443 without demonstrated need/consent threat model | 07,08,26; documented limitation |
| A16 | Confirmed governance gap / P1 store | Permissions/privacy/FGS/media rights/native notices/listing/account/testing checklist prepared | 09,38–40; owner/legal/Play controlled, NOT approved |

## Reproductions and smallest fixes

1. **Foreign rename return:** debug provider successfully renames a verified
   document, then returns a URI outside the selected authority/tree or a malformed
   canonical document path. Previously the returned URI could become committed
   output metadata. The publication boundary now rejects that URI and retains the
   recovery plan. It deliberately does not reopen a correctly returned committed
   file, preserving prior recovery behavior when a provider goes offline.
2. **Horizontal system UI overlap:** root previously applied status-bar padding
   only; landscape cutouts/side navigation need horizontal safe-drawing insets.
   The change applies top+horizontal safe insets and keeps bottom/IME handling.
3. **Unbounded details text:** a long title/details at 200% in landscape could
   crowd out the close action. The shared compact dialog bounds/scrolls text and
   retains a fixed explicit close action.
4. **Missing delivery checks:** baseline CI omitted `lintRelease`, `bundleRelease`
   and AAB verification. The new pipeline fails on those actual build/validation
   errors and records unsigned CI versus externally signed candidate status.

## Review organization and boundaries

Forty virtual specialist functions were coordinated across eight five-role
workstreams, using three AI subagents and the primary integrator. This is not
forty employees or forty independent approvals. Concrete role contributions:
`docs/readiness/ARCHITECTURE_UX_REVIEW.md` (01–05,26–35),
`docs/readiness/STORAGE_REVIEW.md` (11–25), `SECURITY_REVIEW.md` (06–10,36–40).
Security cross-reviewed the storage URI fix; the integrator reviewed callback
equivalence, packaging trust boundaries and combined build/device evidence.

## الملخص التنفيذي

عولجت فجوات البناء والتسليم وتحقق URI والحواف وتفاصيل النقل، مع الحفاظ على
التنزيل والفيديو وMP3 والدفعات والنسخة الموحّدة. اعتماد الإصدار الرسمي يتطلب
إغلاق مخاطر المحرك الأصلي وإثبات اختبارات الأجهزة الفعلية واستكمال متطلبات
صاحب حساب المتجر. نتائج الاختبارات وأحجام الحزم ستُثبت بالأدلة في سجل الاختبار،
ولا تُعد موافقة متجر أو ضمانًا لغياب كل الأخطاء.
