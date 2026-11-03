# Performance and size acceptance

Before measurement, the release thresholds are: universal APK below 80,000,000
bytes without removing an ABI or media capability; process-cold startup median
within 10% and P95 within 20% of the previous signed candidate on the same idle
emulator; settled PSS median within 10%. A breach requires investigation and a
matched repeat, not selective deletion of slow samples. These are engineering
regression thresholds, not a physical-device performance certification.

Use `tools/measure_performance.py` with ten launches, three seconds of settling,
the same emulator, data, font, window, network and host load. It preserves raw
launch/memory/frame records, including a failed measured launch. OS disk cache
is retained. Do not use the harness while a transfer is active.

## Measured delivery sizes

The signed universal APK is **62,116,918 bytes**, 73,728 bytes smaller than
the signed rc.1 baseline (62,190,646 bytes). All 28 top-level native files and
both shared Python assets are byte-identical. The signed AAB is 62,696,449 bytes.
`bundletool get-size total`, API35, density420, locales ar/en:

| Architecture | Estimated compressed download bytes |
|---|---:|
| ARM64 | 21,059,137 |
| ARM32 | 19,473,415 |
| x86-64 | 21,199,861 |
| x86 | 20,882,324 |

These estimates are not installed size or observed Play downloads. The three
locally signed x86-64 split files total 23,272,779 bytes before installation.
Emulator startup and memory observations do not establish physical-device
CPU, battery, scrolling, throughput, or soak performance.

## Matched emulator observation

Ten starts each on the same API35 x86-64 AVD, identical display/font settings
and three-second settling, with the signed rc.1 and rc.2 APKs installed in
sequence. The signed APK was installed after the instrumented regression run.

| Metric | rc.1 P50 / P95 | rc.2 P50 / P95 |
|---|---:|---:|
| Process startup, ms | 4,034.5 / 4,793 | 2,785 / 3,337 |
| Settled PSS, KiB | 44,001.5 / 45,157 | 44,454.5 / 44,611 |
| Observed startup frame duration, ms | 85.95 / 3,253.02 | 111.26 / 3,338.22 |

The startup and PSS acceptance limits were met in this observation. The
startup frame sample did not improve; it is not a scrolling benchmark. These
ten-run observations do not establish a causal speed improvement on phones.
The before/after JSON and per-run raw records are retained locally.
