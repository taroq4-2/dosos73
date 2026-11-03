# Performance and package size

Readiness baseline `1e46c56ce6028bdbae51ae1f80091115cd614e0b`. Artifact and device measurements in this document are reported with their evidence scope.

| Measurement | Evidence |
|---|---|
| Stable universal APK | Published `v1.2.0` asset is 62,059,289 bytes. |
| Prior signed universal candidate | 62,116,918 bytes at `e599d58b058006a7c44fec8bd84307b0f68dcf04`. Existing [ABI measurements](abi-size.json) refer to the prior audit identified in that file. |
| Remediation signed universal APK | 62,121,014 bytes at `96337091f968ee36714e1adcb9b215d43191cd77`: 4,096 bytes larger than the prior signed candidate and below the 80 MB target. Compressed bytes per ABI: ARM64 13,490,029; ARM32 11,905,425; x86 13,315,063; x86-64 13,631,362. The shared runtime archive and metadata total 2,870,754 bytes. All 30 native/shared ZIP entries match the prior signed candidate by SHA-256. |
| Prior launch and memory comparison | On the owned x86-64 emulator the earlier readiness trial measured median launch 4.0345 s to 2.785 s and steady PSS 44,001.5 KiB to 44,454.5 KiB. Treat this as historical and machine-specific. |
| Current parallel fixture | On `96337091f968ee36714e1adcb9b215d43191cd77`, a deterministic 4 MiB payload throttled per connection to 256 KiB per 50 ms took 1545/1194/1742 ms sequentially and 636/862/881 ms with two ranges. Median 1545 ms vs 862 ms. All six files matched the expected SHA-256. The controlled fixture does not estimate speed on public networks. |

Current fixes add no application dependency, use the existing transfer buffers and keep test-only pipe retention at 4 KiB. The throttle correction removes the extra full GET after a rate limit. See [download-engine review](DOWNLOAD_ENGINE_REVIEW.md) for how existing progress publication and staging work.

Repeat launch, CPU, memory, disk and battery measurements on ARM devices across large files and conversion flows. Native runtime replacement is conditional on feature parity, compatibility and licensing. Physical energy, memory pressure and real-world throughput are **NOT VERIFIED**.
