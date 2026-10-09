# RealityForge

**Fully local Android photogrammetry — engineering milestone 01 (0.1.0-m01).**

This package contains actual Android application source, a compiled-and-tested portable C++
geometry core, regression fixtures and build automation. **It does not contain an installable
APK. Android compilation and device operation were not verified in the authoring workspace.**

The current reconstruction is a **two-view sparse point cloud**, not a high-resolution,
textured surface mesh. Dense multi-view stereo, bundle adjustment, surface generation and
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
  dependency-free C++20 two-view initializer reached through JNI.
- Robust essential-matrix estimation, pose disambiguation, triangulation, cheirality,
  parallax and reprojection checks.
- Sparse point viewer and local GLB POINTS / PLY export. Neither export contains mesh faces.
- Source manifest strips INTERNET and ACCESS_NETWORK_STATE. No analytics, login,
  advertising SDK or cloud-processing client is included.

## Executed validation

The portable core passes **17 C++ cases** and **41 Python integration/static-source checks**.
The native cases were also run under Clang and GCC address/undefined-behavior sanitizers.
JNI C++ syntax was checked against installed JDK headers; this is not an Android ABI test.

The bundled synthetic stereo fixture produced **579 triangulated points from 589 reciprocal
SIFT matches**, with 0.109 px median reprojection error and 0.046° rotation error against its
known camera. These are **synthetic regression results**, not evidence of physical-object
scan quality or phone performance. See [validation report](docs/VALIDATION_REPORT.md).

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
Android, and writes `sparse.glb`, `sparse.ply` and reconstruction diagnostics. The host harness
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

M01 examines up to 12 photos, matches local neighbors, and retains **one** reliable camera
pair. It does not register all photos. Intrinsics are estimated from EXIF or a visibly stated
fallback, with no distortion calibration. No metric dimensions, texture atlas, watertight
mesh, ARCore depth, Gaussian splats, animation topology or reconstruction-quality guarantees
are provided. The capture screen does not yet lock exposure or provide measured coverage.

The source includes Android unit and instrumentation tests, but those were **not executed**.
The CI workflow is supplied, **not run or deployed**. No GitHub repository was created or
modified. Do not distribute this as a finished consumer scanner.
