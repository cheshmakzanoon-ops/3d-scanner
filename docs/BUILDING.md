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

## Integrated campaign QA

This section applies after Agent100 integrates the 081–100 QA sources. The isolated
098 baseline branch does not contain other roles' tool implementations. The actual
integrated checkout supplies the paths below; their presence is separate from
their runtime or physical acceptance. Do not run the full merged pytest suite with
only the original scientific requirements:090 needs Trimesh/Khronos/Blender and096
needs its isolated scanner Python.

Agent095's pinned, hash-verified installer is published at
`1a741bc7ffc6618b0a001ffbe217ef65c16c0db4`. From the integrated root:

```bash
QA_ROOT=$(mktemp -d /tmp/realityforge-qa.XXXXXX)
python -m tools.dependencies.install_qa --root "$QA_ROOT"
source "$QA_ROOT/activate.sh"
python -m pytest tests -q
```

The installer creates an external scientific/pytest/Trimesh environment, a separate
scanner environment, verified Node and Khronos validator, and the exact Blender
version. Activation supplies `RF_GLTF_VALIDATOR`, `RF_SECURITY_PYTHON` and
`SCANNER_PYTHON`; `installation.json` records actual hashes and versions. Committed
locks are `tools/dependencies/qa-requirements.lock`, `security-requirements.lock`
and `qa-tools.json`. No Android runtime fetches or dependencies are introduced.

Fresh GitHub Actions uses the same installer with environment/path outputs and
requires the portable Blender branch:

```bash
python -m tools.dependencies.install_qa --root "$RUNNER_TEMP/rf-qa" --github-env "$GITHUB_ENV" --github-path "$GITHUB_PATH" --require-portable-blender
```

The current cloud run verified an existing Debian Blender4.3.2 system binary.
The proxy denies `download.blender.org`, so portable archive installation is not
claimed here. A fresh CI runner must reach the official publisher checksum/archive
and pass exact-name checksum verification; denied access or mismatched bytes fail
explicitly. An installed executable hash is not a portable archive checksum.
See `tools/dependencies/README.md` and
`tools/dependencies/evidence/qa-install-validation.json` in the integrated source.

| Required evidence area | Executable/protocol location in integrated source |
|---|---|
| Native compiler/sanitizer matrix and numerical checks081 | `tools/qa/native_regression/` |
| Hostile-input fuzz082 | `tools/qa/fuzz/run.py` |
| Authorized corpus083 | `tools/qa/corpus/` |
| Geometry084 and held-out texture085 | `tools/qa/geometry_quality/`, `tools/qa/texture_quality/` |
| Physical capture086 and resources087 | `tools/device/capture_validation/qualify.py`, `tools/device/resource_profiling/profile.py` |
| Process/provider recovery088 and offline089 | `tools/device/failure_recovery/controller.py`, `tools/device/offline_privacy/` |
| Independent export readers/target tools090 | `tools/qa/export_interoperability/` |
| APK/AAB/R8/signing091 and installed compatibility092 | `tools/release/package_validation/`, `tools/device/compatibility/` |
| CI evidence093 and source snapshots094 | `tools/ci/validation/`, `tools/source_manifest.py` |
| Dependency/license inventory095 and security096 | `tools/dependencies/`, `tools/security/scan.py` |
| Physical startup/viewer performance097 | `tools/device/ui_performance/` |
| Runbook098 and candidate qualification099 | `tools/docs/check_runbook.py`, `tools/release/qualification/` |
| Ownership/integration100 | `tools/agents/campaign.py`, `docs/agents/integration/` |

Use each command's `--help` and its committed protocol to select actual inputs,
budgets, package type, device and output paths. Dedicated capture/recovery/offline/
resource/release/099 journey controllers are separate from ordinary compatibility
scope. Their absence is NOT_RUN, never a passing complete instrumentation suite.
Agent099 verifies actual installed app/test APK hashes and the exact selected raw
test events; its one-method regression still does not close every physical journey.

Integration metadata lives at `docs/agents/integration/campaign-base.json`,
`deliveries.json` and `gate-applicability.json`. The applicability file freezes all
18 qualification gates as REQUIRED before final combined measurement. Optional
Vulkan remains disabled/unqualified and cannot waive the CPU or physical gates.
The current discovery report is generated explicitly outside Git:

```bash
python -m tools.agents.campaign index --base ad3029193f304c70931b94ea7a0df90b69d84b07 --output /tmp/realityforge-delivery-index.json
```

Its `campaign_complete=false`, missing/invalid/not-DONE deliveries or unresolved
owner requests remain blockers. Remote presence and Markdown receipts are discovery,
not proof of integration or runtime behavior. The final Agent100 source/APK hashes
and Agent099 per-gate decision must be read from the actual frozen candidate records;
they are not inferred from this documentation or earlier baseline counts.

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
