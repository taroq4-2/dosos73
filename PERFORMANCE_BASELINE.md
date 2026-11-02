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

Current run results and remaining physical-device/CPU/battery/soak gaps will be
recorded here after the signed build is measured. No speed or battery improvement
is claimed from a code refactor alone.
