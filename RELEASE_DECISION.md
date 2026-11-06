# Release decision

## 1.3.0 GitHub distribution

The signed 1.3.0 universal APK passed the release artifact checks and the project owner reported a successful device test. The [1.3.0 verification report](docs/RELEASE_1_3_0.md) binds that result to the package hash. This decision covers direct APK distribution on GitHub. Google Play submission has a separate checklist and owner-controlled account and policy gates.

## Historical readiness candidate

**Production / Google Play: NO-GO.**

The implementation candidate may be prepared and tested, but production approval
is blocked by unresolved native version/provenance security review, absent
physical ARM/OEM acceptance, and owner-controlled Play policy/account/legal gates.
`SECURITY_REVIEW.md` and `PLAY_STORE_SUBMISSION_CHECKLIST.md` contain exact scope.

**Available for controlled testing:** the 62,116,918-byte signed universal APK
and signed AAB passed artifact validation. The x86-64 files derived from that
AAB and the universal APK both installed and opened on an API35 emulator; all
six retained files kept their byte counts and SHA-256 hashes. The
[CI build and all eight device jobs](https://github.com/taroq4-2/dosos73/actions/runs/41637321085)
passed. The local full suite recorded one Python startup timeout; its result
is retained in `QA_TEST_MATRIX.md` separately from CI and focused retesting.

No public release or Play publication was performed. The stable release remains
available. Candidate test success cannot override an open production blocker.
