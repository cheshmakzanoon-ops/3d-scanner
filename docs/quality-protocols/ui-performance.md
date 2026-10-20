# UI performance protocol a097-ui-performance-v2

Freeze `tools/device/ui_performance/protocol.json` before accepting a measured result.
Its initial qualification budgets are cold startup p95≤3000ms, warm startup p95≤1000ms,
frame CPU p95≤33.333ms and p99≤50ms, input response p95≤200ms, peak PSS≤256MiB small
and≤512MiB large. These are acceptance thresholds, not observed results. Qualification
requires physical ARM64 hardware, battery≥30%, thermal status≤LIGHT before/after,
ambient temperature and refresh rate recorded. Unsupported required counters remain
NOT_RUN; trace integrity success is not budget success. Do not weaken budgets after
seeing results; a revised version needs a documented owner decision and new run.

The seven executable AndroidX macrobenchmarks cover actual MainActivity cold/warm
launch, actual library→disposable project→library navigation, actual production photographic viewer
reopen/atlas upload for two bounded synthetic grids, orbit/pinch/reset and orientation/
background/close/reopen lifecycle. The fixture host is exclusive to the benchmark
variant and invokes production PhotographicModelViewer/PhotographicGlb/PhotoGlView;
it does not implement a replacement viewer. It rejects changed GLB hashes. Screenshot
quadrant checks require all four photographed test colors to render; parser/viewer error
text is a failure. Synthetic models exercise performance/correctness, not scan quality.

Small model:16×16 grid,512 triangles,64² atlas. Large:128×128 grid,32768 triangles,
1024² atlas. `python tools/device/ui_performance/fixtures.py` deterministically recreates
them and their SHA256 manifest. Production-release APKs must contain neither the host
activity nor a097 fixture assets. Integration is detailed in097-to-004; current UI
text/content descriptions are used until owner stable identifiers exist (097-to-070).

After conventions are applied:

```
gradle :app:assembleBenchmark :macrobenchmark:assembleBenchmark
python tools/device/ui_performance/validate_target.py --target-apk app/build/outputs/apk/benchmark/app-benchmark.apk --benchmark-apk macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk --mapping app/build/outputs/mapping/benchmark/mapping.txt --output /tmp/benchmark-target.json
python tools/device/ui_performance/run.py --serial DEVICE --apk app/build/outputs/apk/benchmark/app-benchmark.apk --benchmark-apk macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk --ambient-c 22 --refresh-rate-hz 60 --output /tmp/ui-run
python tools/device/ui_performance/report.py PATH_TO_ACTUAL_ANDROIDX_BENCHMARK_JSON --context /tmp/ui-run/context.json --output /tmp/ui-report.json
```

The runner refuses emulator/x86 qualification. It records three separate warmup suite
runs, then ten measured iterations per scenario. Full app/model/protocol hashes,
fingerprint, thermal, battery, ambient and refresh conditions bind reports. AndroidX
startup and frame metrics come from real framework traces. GPU counters and true
input-to-display latency are not invented. Current runner captures an uncalibrated
post-run meminfo snapshot and marks required fixture peak/input counters unavailable.
Use087 sampler and069 timing adapter for full qualification; current report therefore
remains NOT_RUN until real bounded fixture-specific peak/input evidence is supplied.

Version2 keeps every numeric budget unchanged and tightens candidate identity before
any physical qualification exists. Start from a clean source checkout; archive generated
Room schemas outside it after building. The runner binds the full source commit and
both supplied target/driver APK hashes, verifies their actual installed package hashes,
and repeats those checks after every Gradle invocation. A rebuilt or stale-installed
candidate fails and records executionStatus=FAIL rather than describing it with the
original hash. Raw-suite completion remains distinct from a measured budget pass.
Profile comparisons also require the identical benchmark driver.

Generate a proposed profile with
`gradle :macrobenchmark:connectedBenchmarkAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.realityforge.scanner.macrobenchmark.BaselineProfileGenerator`.
Run identical device/data/protocol conditions before and after installing the profile,
supplying the same `--unprofiled-apk` identity for both runs. `compare.py` requires both
qualification reports PASS, rejects any>2% p99 regression and retains a profile only
if a p95 metric improves by≥5%. This cannot establish statistical significance by itself:
repeat paired campaigns and inspect trace distributions before owner acceptance.
No baseline-prof.txt is retained without the required physical measurements. A measured
no-benefit result is valid and must leave the optional optimization absent.

Cloud software emulators support functional checks; no physical ARM64 handset is connected. Physical startup/viewer qualification,
GPU/peak/input timing and baseline-profile comparison are BLOCKED_EXTERNAL/NOT_RUN.
Compilation/fixture/report checks establish runnable harnesses only.
