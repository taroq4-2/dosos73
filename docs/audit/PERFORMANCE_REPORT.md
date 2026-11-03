# Performance and package size

Readiness baseline `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. Artifact and device measurements in this document are reported with their evidence scope.

| Measurement | Evidence |
|---|---|
| Stable universal APK | Published `v1.2.0` asset is 62,059,289 bytes. |
| Prior signed universal candidate | 62,116,918 bytes at `e599d58b058006a7c44fec8bd84307b0f68dcf04`. Existing [ABI measurements](abi-size.json) refer to the prior audit identified in that file. |
| Prior launch and memory comparison | On the owned x86-64 emulator the earlier readiness trial measured median launch 4.0345 s to 2.785 s and steady PSS 44,001.5 KiB to 44,454.5 KiB. Treat this as historical and machine-specific. |
| Current parallel fixture | The JVM fixture reports separate exact-byte sequential and two-range trials with a deterministic 4 MiB payload and per-connection throttle. See the current [test evidence](TEST_EVIDENCE.md) for any recorded timings. |

Current fixes add no application dependency, use the existing transfer buffers and keep test-only pipe retention at 4 KiB. The throttle correction removes the extra full GET after a rate limit. See [download-engine review](DOWNLOAD_ENGINE_REVIEW.md) for how existing progress publication and staging work.

Measure the actual compact universal APK after assembly and compare against the prior signed candidate. Record artifact hash, compressed per-ABI bytes and alignment. Repeat launch, CPU, memory, disk and battery measurements on ARM devices across large files and conversion flows. Native runtime replacement is conditional on feature parity, compatibility and licensing. Physical energy, memory pressure and real-world throughput are **NOT VERIFIED**.
