# Performance evidence

No fabricated benchmarks. APK sizes and device measurements are recorded below when available. Native Python/FFmpeg/QuickJS dominate footprint; this preview is not described as exceptionally small.

Implementation limits: direct streaming buffer 64 KiB, proxy stream buffers 32 KiB, at most eight media proxy tunnels, one user transfer, metadata parser cap 4 MiB/1000 formats, no fixed media file-size cap; disk capacity and OS limits still apply. These are configured limits, not measured RSS or CPU.

Cold/warm launch distribution, idle and active memory, CPU, throughput, battery, cancellation latency and before/after benchmarks are pending. No zero-resource or negligible-overhead claim is made.

Measured artifacts: debug 137,124,348 bytes (130.77 MiB); optimized unsigned release 110,667,648 bytes (105.54 MiB). Both include arm64-v8a and x86_64. Five device tests took 22.133 seconds, NOT a throughput/startup benchmark. Profiling remains pending.

