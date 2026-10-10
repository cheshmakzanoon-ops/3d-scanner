# RealityForge

**Fully local Android photogrammetry — engineering milestone 09 (0.9.0-m09).**

This package contains actual Android application source, a compiled-and-tested portable C++
geometry core, regression fixtures and build automation. **This is an engineering prototype, not a released app. M08 passed native, Android and API-35 emulator CI; M09 requires its own CI and physical ARM64 camera validation.**

The app reconstructs **sparse SfM points** using registered cameras and M06 joint refinement. M07 also provides a **bounded CPU dense stereo point-cloud preview**. M08 adds conservative **camera-supported open triangle meshing** with confidence-weighted cross-view vertex/color fusion. M09 adds an **optional camera-projected photographic UV atlas with embedded PNG inside indexed textured GLB**, using actual higher-resolution local photo pixels. Accurate full-resolution MVS, seam-free materials, strong topology repair and physical-device validation remain future milestones. Nothing substitutes a stock 3D model
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
- M06 adds a coupled global Huber-weighted Schur/LM optimization of registered camera extrinsics and sparse points (first two anchor poses fixed; camera intrinsics not refined).
- M07 adds offline rectified CPU photometric plane sweep, reciprocal depth-map consistency, bounded deduplicated dense POINTS, and separate dense-point GLB/PLY outputs. Both sparse and dense GLBs contain GLTF POINTS, not triangle faces.
- M08 creates indexed triangle meshes from **reciprocal-verified neighboring depth pixels**, with confidence-weighted spatial vertex fusion, conservative discontinuity gating and normal generation. Exports GLB TRIANGLES and face-indexed PLY separately from original POINTS.
- An orbitable shaded surface mesh preview/wireframe and GLB/PLY export controls complement sparse and dense point-viewer modes. The original M08 vertex-color formats remain distinct from new M09 photo-textured GLB/PNG.
- M09 selects visible camera images for the verified open mesh, creates UV charts,
  a PNG photo atlas, and exports textured GLB with `TEXCOORD_0` and embedded `image/png`.
  Texture failure does not discard already-verified M08 mesh/point outputs.
- Source manifest strips INTERNET and ACCESS_NETWORK_STATE. No analytics, login,
  advertising SDK or cloud-processing client is included.

## Executed validation

The portable geometry core was tested using GCC and Clang -Werror,
CTest native targets, ASan/UBSan and Python integration tests. On a
synthetic four-camera geometry fixture, median normalized reprojection error
fell from ~0.007915 to ~0.000179 while anchor poses stayed fixed. On three
actual *synthetically rendered photographs* passed through SIFT/SfM/PnP, the
Huber objective decreased by ~32%; these are not measured physical-object scans.
See [M06 validation](docs/M06_VALIDATION.md).

M08 CI passed Android build, lint, native tests, real-image mesh benchmark and API-35 emulator instrumentation. M09 native tests validate the embedded photo atlas and indexed UV GLB.
No physical-phone camera/thermal measurement has been performed.

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
third-camera registration, run `python3 tools/three_view.py`. For M06 joint
camera/point optimization through actual rendered photo matches, run
`python3 tools/bundle_three_view.py`. The host harness
is a development/test tool, not a service required by the Android application.
For the **independently measured real-photo dense stereo gate**, run
`python tools/benchmark_dense_middlebury.py --gate`; see [M07 validation](docs/M07_VALIDATION.md).

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

M07 examines up to 12 input photos for sparse reconstruction, then uses
up to three registered views for a low-resolution CPU stereo depth preview.
M06 Schur bundle adjustment still holds both anchor cameras fixed. Calibration from
M04 can correct Brown–Conrady distortion but M06 does **not** optimize
intrinsics, provide an absolute metric scale or guarantee registration of all
views. Sparse and dense GLB/PLY remain POINTS, M08 mesh GLB/PLY contain TRIANGLES, and M09 textured GLB contains camera-projected UVs plus photographic PNG. These products are separate and not globally complete, watertight or metric.
M05 API-35 emulator tests passed; a real arm64 camera/thermal run is still
outstanding. Do not distribute as a finished consumer scanner.

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

## M04 — Offline camera calibration

M04 adds an optional per-project **checkerboard-derived Brown–Conrady lens model** (fx/fy/cx/cy and five distortion coefficients), fully offline. Capture 10–40 photos of the bundled [9×6-inner-corner printable checkerboard](docs/checkerboard-9x6.svg) using one lens and orientation, then select **Calibrate this lens** inside the project. The app detects/optimizes 2D corners with OpenCV, measures fit RMS, rejects insufficient geometry, and uses calibrated undistorted normalized rays in anchor matching, PnP and M03 multi-view tracks. The profile is local to the project; changing it invalidates old sparse results. Incompatible aspect or known EXIF focal ratio causes an explicit error, not a silent pinhole fallback. Without a profile the existing estimated-pinhole pipeline remains available.

This milestone also repairs `ProjectRepository.artifact`: versioned sparse artifact keys now match the file-lookup pattern, restoring reopened/exported preview access. See [calibration guide](docs/M04_CALIBRATION_GUIDE.md). This is still a sparse prototype: **no dense mesh, no texture, no full Ceres BA, and no verified physical-scene accuracy**. CI compiles and validates; real phone testing is outstanding.

## M05 — Real-photo acceptance benchmark, user-controlled capture ZIP and emulator QA

M05 adds a **real physical-scene image test** using the Middlebury 2014 Motorcycle stereo dataset through scikit-image, cross-checking actual native sparse SfM against independent stereo disparity ground truth. The input images are not redistributed with this repo. The benchmark and conservative regression gates run in GitHub Actions using `tools/requirements-real-benchmark.txt`. See [M05 validation](docs/M05_REAL_VALIDATION.md) for real observed errors (including outliers).

Capture projects can now export an **opt-in ZIP** containing original local photographs, SHA-256 hashes, metadata and the optional offline lens-calibration profile. Run `python tools/capture_audit.py /path/to/realityforge-capture.zip` to inspect it without contacting a server. This is a capture audit, **not** a reconstructed surface. Stricter reciprocal-SIFT filtering (.67 instead of .75) is used consistently in the host and Android implementations.

The GitHub workflow also runs **API 35 x86_64 emulator instrumentation** on commits to `main` to validate JNI/OpenCV, database behavior, and ZIP export. Emulator validation cannot replace measurements from a physical arm64 camera, including calibration, thermal behavior and actual 3D reconstruction quality. Dense MVS, textured geometry, global Ceres-based BA, and metric scale remain unimplemented.

## M06 — Joint sparse bundle adjustment

`reconstruction/src/bundle_adjustment.cpp` implements genuinely coupled robust
camera/point optimization using a Schur complement and damped steps (up to 12
cameras and 20,000 sparse points). Two anchor cameras remain fixed. Android
invokes it only after accepted M03 multiview geometry, checks loss and point
retention, and retains M03 geometry if the new optimizer rejects the scene.
The source and real-photos remain entirely on device. This **does not add Ceres,
optimize intrinsics or generate dense surfaces**. [M06 validation](docs/M06_VALIDATION.md).

## Milestone M07 — CPU dense depth proof of concept

M07 produces real multi-view depth hypotheses and validates them against
reciprocal depth maps, then exports **dense points**, not fabricated triangle
surfaces. Its stage is optional and failure-safe: an unsupported image set
still retains usable M06 sparse results. See [M07 technical report](docs/M07_VALIDATION.md)
for real/synthetic benchmark metrics, hard memory limits and the M08 plan.

## M08 — open triangle mesh validation

[Read the M08 validation report](docs/M08_VALIDATION.md). Native tests on a
calibrated three-camera rendered plane produce **2,570 vertices / 7,125
triangles**, with 2,101 cross-view fusion events. On real, independently
calibrated Middlebury stereo photos, the engine reconstructs **2,454 vertices /
3,530 triangles** with median GT disparity error 0.479 pixels and 90th
percentile 2.315 pixels. These are bounded low-resolution tests, **not** a
complete real-object scan or a promise of commercial photographic quality.

The Android project now creates `.mesh.glb` and `.mesh.ply` when there is enough
reciprocal support. Each mesh contains indexed faces, vertex colors, normals,
and measured open-surface gaps where coverage fails. The existing `.dense.glb`
and `.dense.ply` remain **point clouds**; they must not be confused with mesh
exports. There are **no UV textures, watertight guarantees, metric measurements,
or physical-device thermal tests** yet.

Run the new CI gate via `python tools/benchmark_surface_middlebury.py --gate`.
The complete development roadmap remains in `PROJECT_PLAN.md`.

## M09 — Photographic UV textures and camera-ranked atlas

M09 projects verified M08 surface triangles back into actual undistorted source photographs,
checks face orientation, photo validity and calibrated z-buffer agreement for alternate
views, and builds lossless RGBA8 photo tiles with 3px safe gutters. It splits UV seams
without inventing any unsupported geometry; emits standalone `texture.png` and a
**self-contained textured GLB** with actual `TEXCOORD_0`, embedded `image/png`,
NORMAL and indexed triangles. Android uses photo RGB at up to 768px while the CPU MVS
stage remains at 256px. Mesh and POINTS results survive photo preprocessing/texture errors.

The separate [M09 validation report](docs/M09_VALIDATION.md) documents synthetic/native
and independently measured real stereo tests, texture coverage limitations, memory bounds
and the next field-validation gate. Real physical 360° photos, texture seam harmonization,
full-object geometry and production-quality scans are not yet validated.
