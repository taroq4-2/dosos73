# Performance and package size

The 4 November source-built signed candidate is **62,051,382 bytes**, which is
69,632 bytes smaller than the 62,121,014-byte signed remediation APK.
It retains all four ABIs. The producer's raw Python core archives were
79–82 MB per ABI; selecting the shipped runtime modules, sharing identical
Python source files and removing non-runtime symbol information avoids
embedding that build tree.

The final APK preserves 485 non-Python APK entries byte-for-byte, including app
code/resources, FFmpeg and QuickJS. Selected inherited vendor modules and trust
store retain their bytes. ELF stripping checks preserve dynamic symbols,
dependency names and load segments; kernel/runtime checks are documented in
[TEST_EVIDENCE.md](TEST_EVIDENCE.md). Current single install/open timings provide
diagnostic samples. Comparative hardware speed and battery measurements remain open.

## Current signed-APK emulator comparison

The same owned API35 x86-64 emulator ran ten process-cold launches for each
signed APK with two seconds of settling and retained app data. The baseline
was measured first, followed by the rebuilt candidate. OS disk caches were
retained and all samples were saved.

| Measure | Previous signed APK | Source-built candidate |
|---|---:|---:|
| APK bytes | 62,121,014 | 62,051,382 |
| Median launch TotalTime | 4069.5 ms | 2930.5 ms |
| Launch p95 | 7902 ms | 4383 ms |
| Median settled TOTAL PSS | 29,078 KiB | 30,704.5 KiB |
| PSS range | 26,541–38,100 KiB | 30,346–39,682 KiB |

The memory median rose by 1,626.5 KiB (about 1.59 MiB). Launch times were
variable. These observations cover activity startup in this emulator and its
current state. Hardware energy, sustained native workloads and long-transfer
performance remain open measurements.

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
