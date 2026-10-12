# RealityForge

Fully offline native Android photogrammetry — **M10 / 0.10.0-m10**.

This is a working engineering prototype, not a finished consumer 3D scanner.
It reconstructs camera geometry, bounded depth maps, open triangle surfaces and
self-contained photographic GLB files. Originals stay in private local storage;
there is no account, analytics or app internet permission.

## Current implementation

Kotlin/Compose and CameraX provide capture/import, lens calibration, project
storage, diagnostic viewers and local exports. The same C++20 library runs in
host tests and on Android via JNI. The pipeline includes reciprocal SIFT matching,
calibrated camera registration, joint sparse bundle adjustment, CPU plane-sweep
stereo, confidence-weighted surface fusion, indexed meshes and photographic UVs.

M10 repairs two real texturing defects: a missing cross-product Z component
rejected valid faces, and a mistaken glTF V flip mirrored photographs. New
visibility-supported exposure balancing estimates bounded camera RGB gains in
linear sRGB. Deterministic camera-label smoothing reduces unnecessary seams
without allowing invalid photo views. Originals and mesh geometry are unchanged.

## Measured evidence, not quality guarantees

On the same two calibrated Middlebury Motorcycle photographs and fixed geometry,
M10 textures all **3,530 existing mesh triangles**, compared with M09's 1,079.
This is coverage of the existing partial stereo mesh, **not** 100% of an object.
Median geometric disparity error remains 0.47948 pixels. Shared-surface color
disagreement falls about 20.76%; seam-edge reduction is modest (791 to 787).

Seven native CTest targets and 98 Python tests pass on the development host.
GCC/Clang strict builds pass; the eight new photometric/UV cases also pass
ASan/UBSan. Check the commit-specific GitHub Actions run for Android build and
emulator results; older runs do not establish this version's status.
See [M10 validation](docs/M10_VALIDATION.md) and [measured comparison](reports/m10-comparison.json).

## Build and test

Open this repository root in Android Studio. The pinned build uses Gradle 8.13,
Android API 36 and NDK 28.2.13676358; dependency installation needs a connection,
but scanning does not. See [build instructions](docs/BUILDING.md).

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pip install -r tools/requirements-real-benchmark.txt
python -m pytest tests -q
python tools/benchmark_texture_middlebury.py --gate
```

## Boundaries and next work

Android currently processes at most 12 input photographs for sparse alignment,
with at most three depth views at 256-pixel edge and separate photographic inputs
up to 768-pixel edge. These are prototype limits, not the final high-quality plan.
The mesh is open, unscaled and potentially overlapping/nonmanifold; textures can
retain seams. No physical-handset 360-degree capture, sustained thermal profile,
full-object completeness, print readiness or store release has been validated.
The interactive viewer is still a vertex-color diagnostic; real textured-model
inspection is the next implementation gate.

The complete approved direction is in [PROJECT_PLAN.md](PROJECT_PLAN.md).
[Current status](docs/MILESTONE_STATUS.md), [next gate](docs/NEXT_MILESTONE.md),
[privacy](docs/PRIVACY.md) and historical M02–M09 validation reports remain in `docs/`.
