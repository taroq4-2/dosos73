# Performance evidence — 0.2.0-preview

Measured on 2026-10-25, local Android 15/API35 x86_64 emulator. These are isolated debug-build observations, not a controlled benchmark or physical-device battery profile.

| Measurement | Observed value |
|---|---|
| Installable debug APK, both ABIs | 137,162,099 bytes (130.81 MiB) |
| Optimized unsigned release APK | 110,639,840 bytes (105.51 MiB) |
| Previous debug artifact | 137,124,348 bytes; current difference +37,751 bytes |
| Cold launch after build finished | TotalTime 1,831 ms; WaitTime 1,834 ms |
| Earlier cold launch during heavy build | Timed out after 34,415 ms; retained as adverse evidence |
| Existing top-activity intent | WaitTime 50 ms, TotalTime 0; NOT a fresh warm-render benchmark |
| Idle memory snapshot | PSS 71,609 KiB; RSS 147,084 KiB |
| Live-suite memory snapshot | PSS 63,076 KiB; RSS 164,856 KiB; stage not correlated, NOT peak transfer memory |
| CPU snapshot | App 0%, system 11%; a momentary sample, NOT sustained download CPU |

Raw observations: docs/evidence/v4-cold-start*.txt, v4-warm-start.txt, v4-idle-memory.txt, v4-test-memory.txt, v4-cpu-snapshot.txt. Runtime initialization timing, when measured, is in v4-runtime-preview.txt and refers to already installed native files.

Direct transfers and output publication use 64 KiB buffers, CONNECT forwarding 32 KiB buffers, at most eight tunnels, and one user transfer. Metadata remains limited to 4 MiB/1000 formats. There is no fixed media-file size limit. Files remain streamed rather than loaded into RAM.

No new production dependency was added for UI/contact. Existing Python/FFmpeg/QuickJS dominate APK size. Compose UI test dependencies are test-only. Startup is lazy with respect to native initialization.

Still unmeasured: physical ARM memory/CPU, peak native merge RSS, battery/thermal effects, large-file throughput, statistical cold/warm launch distributions and first-ever native extraction overhead. A synthetic sparse file above 2 GiB is not a network or disk-throughput benchmark.

