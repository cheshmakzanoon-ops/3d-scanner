# RealityForge — offline Android photogrammetry

**M12 engineering build, 0.12.0-m12. Not a completed consumer scanner.**

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

## M12 session safety

Saved previews are size/nesting bounded and must match both the database revision
and artifact identity. In-flight loads cannot republish stale data after a new
operation. Exports, deletion, imports and calibration reserve a single operation;
export cancellation reports incomplete destinations explicitly. Capture archives
are rejected when the project revision changes during copying.

The emulator suite now includes the entire real application/controller workflow
using the existing **synthetic** three-photo relief fixture: import and duplicate
checks, native reconstruction, textured-file parsing, database reopening, exact
export hashes, original preservation and a rendered photographic screenshot.
This is not physical-phone or real full-object quality evidence. M11's 20 emulator
tests previously passed; M12's additional cases require their commit-specific CI.

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

M12 local native verification: seven CTest targets and 105 Python checks passed.
Android build/lint, JNI tests, real GLES pixel readback, activity resume and model
validation must pass the commit-specific GitHub Actions run before Android success
is claimed. Synthetic analytical fixtures are labeled; real benchmark data is
used only as documented. The app itself never downloads those benchmark datasets.

## Engineering records

[Approved 20-phase plan](PROJECT_PLAN.md) · [Current status](docs/MILESTONE_STATUS.md) ·
[M10 fixes and measurements](docs/M10_VALIDATION.md) · [M11 viewer/tests](docs/M11_VIEWER.md) · [M12 session/tests](docs/M12_SESSION_RELIABILITY.md) ·
[Next gates](docs/NEXT_MILESTONE.md) · [Privacy](docs/PRIVACY.md)

Native code is in `reconstruction/`, Android source in `app/`, regression harnesses
in `tests/` and `tools/`, and measured results in `reports/`. Historical M01–M10
documentation remains in `docs/`. Do not interpret isolated successful benchmarks
or green builds as production quality or flawless operation on physical phones.

## M14 native multi-view backend

The shared C++/JNI depth, fusion and photographic atlas now accept up to eight
calibrated views with capped source caches and workload validation. Existing
Android selection defaults remain unchanged until the next UI integration gate.
See [M14 implementation and measured bounds](docs/M14_MULTIVIEW.md).
