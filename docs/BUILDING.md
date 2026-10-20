# Build, verify and recover the current source

The source baseline is M19, version0.19.0-m19. Historical build limitations in M02
are retained in milestone records; they do not describe current tool availability.
The audited commit `fdd16fce4aa7b1b7d365c0f43e89964d3028bf42` passed
[Actions38488820626](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626)
(native, Android and API35 emulator jobs). Later agent/candidate checks require
new exact-source receipts. A successful emulator does not measure physical capture.

## Prerequisites and local setup

Use Python3.12, C++20 GCC/Clang, CMake3.22+, Ninja, JDK17, Gradle8.13, Android
SDK/API36, BuildTools36.0.0, NDK28.2.13676358 and Android CMake3.22.1. Runtime
scanning stays offline; first-time build dependencies need network access.

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tools/requirements-real-benchmark.txt
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
```

Accept required SDK licenses through the official SDK tool. Set `ANDROID_HOME`
(or private ignored `local.properties`) to that SDK. Do not commit local SDK paths,
credentials, original photographs or generated APK/build/cache directories.
On the campaign cloud machine, use the prepared environment instead of reinstalling:

```bash
source /workspace/.setup/3d-scanner/env.sh
```

The wrapper launcher bootstraps its missing JAR/checksum from official sources.
Bootstrap changes tracked wrapper properties; for the campaign use the installed
Gradle8.13 path below and preserve the staged source tree. Never disable TLS,
signature/checksum validation or use a different Gradle version to bypass a failure.

## Host build and functional checks

Run from the repository root. One compile worker is the campaign resource limit.

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 1
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q
python tools/bundle_three_view.py
python tools/benchmark_multiview_coverage.py --gate --output reports/m14-eight-view-coverage.json
python tools/benchmark_middlebury.py --gate --output reports/m05-middlebury-real.json
python tools/benchmark_dense_middlebury.py --gate --output reports/m07-middlebury-dense.json
python tools/benchmark_surface_middlebury.py --gate --output reports/m08-middlebury-surface.json
python tools/benchmark_texture_middlebury.py --gate --output reports/m10-middlebury-texture.json
```

Host library: `build/host/librealityforge.so` on Linux (platform suffix elsewhere).
Benchmark reports are under `reports/`; read actual per-scene results and exit
codes. The Middlebury pair is real rectified stereo with disparity truth, not a
physical360-degree unknown-pose object session. Synthetic fixtures stay synthetic.
Use `-DRF_SANITIZE=ON` in a separate build directory for host ASan/UBSan; do not mix
sanitizer and Release caches. Windows multi-config builds need `--config Release`.

## Android compilation and package checks

```bash
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --sdk "$ANDROID_HOME" --report reports/apk-validation.json
```

On the campaign machine serialize the real Gradle invocation with the shared lock
and proxy-aware external helper (run from your own assigned checkout):

```bash
flock /workspace/campaign/android.lock python /workspace/campaign/gradle.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

After successful execution, APKs are
`app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
JVM XML reports are `app/build/test-results/testDebugUnitTest/`; lint HTML is
`app/build/reports/lint-results-debug.html`. The package checker inspects actual
merged privacy permissions, native16KiB ZIP/LOAD alignment and packaged libraries.
Source flags alone do not establish package compatibility.

## Installed instrumentation and recovery

Requires authorized API29+ hardware or a functioning emulator/ADB. The cloud
campaign has no `/dev/kvm` or connected handset: installed tests remain NOT_RUN
there. Camera, lens/EXIF, thermals, power and whole-object evidence need physical ARM64.

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash tools/run-device-tests.sh
```

The existing runner above selects `adb -e` and is emulator-specific. With one
authorized physical handset, set its exact serial and invoke the built test APK:

```bash
adb -s "$RF_DEVICE_SERIAL" shell am instrument -w -r com.realityforge.scanner.test/androidx.test.runner.AndroidJUnitRunner
```

Retain the complete instrumentation output and verify its executed test count;
use Agent092's candidate-aware device harness once integrated for structured
physical receipts. The emulator runner retains its screenshot under
`reports/device/` and Gradle device results under
`app/build/outputs/androidTest-results/connected/`. A zero-test, skipped or missing
XML run never proves device behavior. Build success is a separate gate.

For a manual recovery check: export important originals to a private chosen
destination first; import2–12 overlapping authorized photos into a new test project;
calibrate the same lens when available; start reconstruction; pause/kill/reopen;
verify accepted unchanged-input depth checkpoints are reused and final geometry/
texture is rebuilt; change an input and verify stale results/checkpoints are rejected.
Inspect/export/reopen the completed result and compare original file hashes.
M17 supports cooperative CPU depth/mesh/texture cancellation; sparse JNI checks
occur between stages. M18 does not resume every stage or guarantee power-loss durability.
Capture bundles require3–250 originals, but reconstruction still uses at most12.
Never uninstall/clear real app data for a test without preserving user projects.

## Candidate release and parallel integration

The default release build currently has R8 disabled and no production signing
qualification; `assembleRelease` is not store acceptance. Agent091 owns actual
APK/AAB/R8/signing validation; Agent099 owns exact-candidate physical journeys and
independent gate aggregation. Signing keys are supplied securely, never committed.
No store upload/public release is authorized by these development commands.

Follow [AGENTS](../AGENTS.md) and the shared ownership/manifest protocol in the
[execution plan](../PRODUCTION_100_AGENT_EXECUTION_PLAN.md). Work only on your
assigned numbered branch/worktree. Stage owned changes first and regenerate the
entire `SOURCE_MANIFEST.sha256` from staged Git blobs. Agent094 owns generator
policy;100 resolves semantic integration then regenerates the mechanical manifest.
Receipts under `docs/agents/status/` bind source/artifact SHA, command, environment,
dataset and actual PASS/FAIL/NOT_RUN/BLOCKED_EXTERNAL gates. Do not claim remote
provider branches are integrated until their runtime paths and dependencies pass.
