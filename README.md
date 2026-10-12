# RealityForge — offline Android photogrammetry

**M11 engineering build, 0.11.0-m11. Not a completed consumer scanner.**

Capture or import photographs, estimate calibrated cameras, reconstruct observed
surfaces, export a photograph-textured GLB, and inspect that exact model locally.
No account, analytics or runtime networking is included. Original photographs are
preserved until the user deletes the project; export important files before uninstalling.

## Implemented

- Kotlin/Compose/CameraX app, app-private imports, image-quality statistics,
  checkerboard lens calibration, feature caches and revision-safe artifacts.
- Shared C++ SfM/PnP, joint sparse bundle adjustment, bounded CPU multi-view
  stereo, verified-depth fusion and open indexed triangle meshes.
- Real photographic UV atlas, visibility checks, bounded exposure correction,
  source-camera label coherence and embedded PNG/GLB export.
- **M11: actual on-device photographic inspection** with bounded local GLB/PNG
  validation, on-demand GLES3 rendering, orbit/zoom/reset, alpha-mask support,
  and lifecycle handling. The vertex-colored diagnostic remains available.
- Separate sparse/depth POINTS and mesh GLB/PLY exports. User-selected capture
  ZIP export includes original-photo hashes and calibration for reproducible audits.

## Current limits

Android examines up to 12 photos for sparse geometry and at most three 256px-edge
views for depth; photographic source images are capped at 768px. Models have
arbitrary scale, open/disconnected or overlapping surfaces, and visible seams.
There is no watertight/full-object guarantee, production Filament studio, adaptive
high-resolution Vulkan MVS, reliable 360-degree completion or store-ready release.

M10's corrected normal/UV math textures all 3,530 existing triangles in one real
Middlebury stereo benchmark, compared with M09's 1,079. This does **not** mean
100% of an object was captured. Geometry, material fidelity and device performance
must be validated separately. Physical ARM64 capture, sustained thermal/RAM and
complete-object ground-truth validation remain outstanding.

## Build and verification

Open the repository root in Android Studio; see [build setup](docs/BUILDING.md).
Pinned dependencies require internet for the first developer build; app operation
does not. Gradle 8.13, Android API 36, NDK 28.2.13676358 and Java 17 are pinned.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q
```

M11 local native verification: seven CTest targets and 99 Python checks passed.
Android build/lint, JNI tests, real GLES pixel readback, activity resume and model
validation must pass the commit-specific GitHub Actions run before Android success
is claimed. Synthetic analytical fixtures are labeled; real benchmark data is
used only as documented. The app itself never downloads those benchmark datasets.

## Engineering records

[Approved 20-phase plan](PROJECT_PLAN.md) · [Current status](docs/MILESTONE_STATUS.md) ·
[M10 fixes and measurements](docs/M10_VALIDATION.md) · [M11 viewer/tests](docs/M11_VIEWER.md) ·
[Next gates](docs/NEXT_MILESTONE.md) · [Privacy](docs/PRIVACY.md)

Native code is in `reconstruction/`, Android source in `app/`, regression harnesses
in `tests/` and `tools/`, and measured results in `reports/`. Historical M01–M10
documentation remains in `docs/`. Do not interpret isolated successful benchmarks
or green builds as production quality or flawless operation on physical phones.
