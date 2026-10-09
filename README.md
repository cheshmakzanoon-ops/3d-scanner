# RealityForge

**Fully local Android photogrammetry — engineering milestone 03 (0.3.0-m03).**

This package contains actual Android application source, a compiled-and-tested portable C++
geometry core, regression fixtures and build automation. **This source snapshot is not a released app. M02 Android compilation and APK validation passed on
GitHub Actions; emulator and physical-device execution remain unverified.**

The current reconstruction is a **sparse point cloud triangulated from registered photo pairs with bounded gauge-fixed pose/point refinement**, not a high-resolution,
textured surface mesh. Dense multi-view stereo, full/global sparse bundle adjustment with intrinsics, surface generation and
photographic texturing remain subsequent milestones. Nothing substitutes a stock 3D model
for the supplied photographs.

## Implemented in source

- Kotlin / Jetpack Compose project, CameraX JPEG capture, optional camera permission and
  Android's document picker for existing photographs.
- Private original-photo storage, bounded import, SHA-256 duplicate detection, EXIF orientation
  handling, bounded-resolution analysis and exposure/sharpness statistics.
- Room project metadata, photo revisions, interrupted-session recovery, feature caches,
  settings fingerprints, atomic result publication and stale-result invalidation.
- Native OpenCV SIFT through its Android bindings, reciprocal descriptor matching and a
  dependency-free C++20 two-view initializer and bounded, robust PnP pose solver through JNI.
- Robust essential-matrix estimation, pose disambiguation, triangulation, cheirality,
  parallax and reprojection checks; M02 also registers additional cameras to the same
  anchor frame using cycle-consistent feature tracks and robust native PnP.
- M03 builds conflict-aware keypoint tracks across registered images and triangulates new sparse points from camera pairs outside the original anchor; first two cameras remain fixed during damped Huber refinement.
- Sparse point viewer and local GLB POINTS / PLY export. Neither export contains mesh faces.
- Source manifest strips INTERNET and ACCESS_NETWORK_STATE. No analytics, login,
  advertising SDK or cloud-processing client is included.

## Executed validation

The M02 portable core passes **20 C++ cases** and **57 Python integration/static-source checks** on the development host. M01 historically passed 17/41.
M02 C++ tests also passed Clang with warnings as errors and GCC address/undefined
behavior sanitizers with leak detection.
M02 Android builds, JVM tests, lint and APK inspection passed on GitHub Actions; runtime
instrumentation and physical-camera behavior remain unverified.

The bundled synthetic pair reconstructs 579 sparse points from 589 matches. M02 additionally forms
283 three-view tracks, with 282 supporting the third camera, whose rotation differs from the
synthetic ground truth by about 0.023°. These are **synthetic regression results**, not physical-object
accuracy, mobile performance, dense geometry or texture quality. See [M02 validation](docs/M02_VALIDATION.md).

## Open the project

Extract the complete folder, then open **this root folder** in Android Studio. See
[BUILDING.md](docs/BUILDING.md) for SDK/NDK setup and the first-build wrapper bootstrap.
The package pins Gradle 8.13; it does not use a globally installed Gradle 9.x.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The wrapper JAR and SDK/dependencies are **not bundled**. The first build requires network
access to official dependency servers. That is separate from offline operation of the app.
The Linux/macOS and PowerShell bootstrap scripts retrieve the official Gradle wrapper and
check its published SHA-256 before running it. Their download path has not run successfully
in the authoring environment because host DNS/network access is unavailable.

## Run the tested native milestone without Android

Requirements: C++20 compiler, CMake 3.22+, Python 3.10+ and dependencies in
`tools/requirements.txt`. The C++ core itself has no external library dependency.

```bash
cmake -S . -B build/host -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python3 -m pip install -r tools/requirements.txt
python3 -m pytest tests -q
python3 tools/photo_pair.py \
  benchmarks/fixtures/relief/view-01.jpg \
  benchmarks/fixtures/relief/view-02.jpg \
  --calibration benchmarks/fixtures/relief/calibration.json \
  --output build/demo
```

The last command extracts image features, invokes the same native geometry library used by
Android, and writes `sparse.glb`, `sparse.ply` and reconstruction diagnostics. To validate
third-camera registration, run `python3 tools/three_view.py`. The host harness
is a development/test tool, not a service required by the Android application.

## Project map

| Path | Purpose |
|---|---|
| `PROJECT_PLAN.md` | Approved product direction and all 20 implementation phases |
| `app/` | Android UI, camera, persistence, feature adapter and JNI integration |
| `reconstruction/` | Portable native geometry, quality and sparse export core |
| `tests/` | Host integration and static-contract tests |
| `tools/` | Reproducible photo harness, fixture renderer and build checks |
| `benchmarks/fixtures/` | Explicitly synthetic photographs and known calibration |
| `benchmarks/output/` | Actual host-generated sparse reconstruction |
| `reports/` | Executed logs and machine-readable benchmark measurements |
| `.github/workflows/verify.yml` | Native/Android build gates, optional emulator tests |
| `docs/NEXT_MILESTONE.md` | Exact next implementation and validation gates |

## Important boundaries

M03 examines up to 12 photos, chooses an anchor pair and attempts to register other
photos from cycle-consistent 3D–2D tracks. It triangulates additional supported tracks and performs bounded point/pose sparse refinement, but not full Ceres bundle adjustment or intrinsics optimization. Intrinsics are estimated from EXIF or a visibly stated
fallback, with no distortion calibration. No metric dimensions, texture atlas, watertight
mesh, ARCore depth, Gaussian splats, animation topology or reconstruction-quality guarantees
are provided. The capture screen does not yet lock exposure or provide measured coverage.

M02 Android compilation, unit/lint checks and APK validation passed on GitHub Actions.
Emulator instrumentation and physical camera tests remain outstanding. Do not distribute this as a finished consumer scanner.

## Milestone M02 additional camera registration

`tools/three_view.py` runs a genuine three-image SIFT correspondence cycle and native PnP,
not an estimated pose copied from fixture calibration. It reports a third camera in the same
unit-baseline world frame as the initial pair. On Android, additional cameras are attempted
with cached SIFT descriptors and require agreement with both anchor images. Unregistered
images are explicitly listed, not silently counted as geometry. Anchor points remain exactly
anchor-pair derived; **no new mesh vertices, bundle adjustment, textured triangles, or
metric scale are claimed**.

## Milestone M03 — actual sparse multiview increment

See [M03 validation](docs/M03_VALIDATION.md). M03 adds native calibrated triangulation of newly matched 3D tracks, Huber-weighted sparse point and non-anchor camera refinement, the Android JNI bridge, collision-checked track graph, and regression tests. This remains sparse, in arbitrary scale, untextured and not a commercial scanner. Any runtime failure preserves the valid M02 anchor geometry rather than publishing invented detail.
