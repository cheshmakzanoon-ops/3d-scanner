# Status — M09 (2026-10-10)

| Plan area | Implemented evidence | Gate status |
|---|---|---|
| Specification | PROJECT_PLAN, architecture, privacy and metrics | Written; product targets not validated |
| Reference datasets | Synthetic photographs, camera ground truth, renderer | Real-object corpus still required |
| Desktop reference | SIFT-to-shared-native-kernel harness | Tested; no independent full COLMAP/MVS benchmark yet |
| Portable native core | Geometry/statistics/export C++ and tests | Compiled and host-tested under GCC/Clang |
| Android JNI integration | JNI methods and app bridge | Host C++ syntax checked; Android ABI/runtime untested |
| Camera/import | CameraX, picker, EXIF, duplicate handling, private storage | Source implemented; Android build/device tests pending |
| Features/matching | SIFT, reciprocal ratios, bounded neighbor pairs, caches | Host path tested; Android adapter pending execution |
| Sparse SfM | Two-view initialization, M02 PnP, M03 registered-pair triangulation and track consistency | Synthetic host verified; real scenes and full BA unverified |
| Sparse optimization | M03 alternating damped, robust point/pose refinement with two fixed anchors | Implemented host-side; not production global Ceres BA and no intrinsic calibration |
| Dense stereo/fusion/meshing | M07 bounded CPU inverse-depth plane-sweep ZNCC, reciprocal depth agreement and deduplicated dense points | CPU baseline host-tested; no triangle meshes or texture atlas |
| UVs/photographic texturing | Preserved plan | Not implemented |
| Export | Sparse and M07 dense point-cloud GLB/PLY | Host code implemented; no mesh/OBJ/STL export yet |
| Viewer | Android diagnostic point viewer | Source only; no device-render evidence |
| Runtime/recovery | Feature cache, revisions, fingerprints, atomic publication | Source implemented; interruption/device tests pending |
| Memory/thermal/Vulkan | Bounded prototype allocations; planned production design | No handset profiling or GPU backend |
| CI/release | Build/test/privacy/alignment workflow and test sources | Supplied; M02 debug Android compilation, lint and APK checks succeeded on CI; device runtime unverified |

No phase requiring actual Android execution or high-quality physical-object reconstruction
is marked complete. The main verified outcome is a functioning native two-view geometry
milestone, exercised from synthetic images, inside a concrete Android source project.

## M02 increments

- Added a native calibrated PnP solver with bounded DLT-RANSAC, camera-frame refinement, quality gates, exposed through C/JNI/ctypes.
- Cycle-consistent 3-image track formation on Android, retaining anchor keypoint IDs and requiring agreement from **both** anchor views.
- Attempt registration of further images into the anchor coordinate system without inventing new triangles, points, or metric scale.
- Synthetic three-photo benchmark validates the third pose, plus robustness/degeneracy suites.
- On-device capture, thermal behavior, real-world datasets, triangulation of new multiview tracks, BA and dense MVS remain pending.

## M03 increments

- Adds conflict-aware tracks across registered photos with epipolar verification; triangulates pairs beyond the M02 anchor when supported.
- Robust alternating sparse updates for points and non-anchor cameras; the first two poses remain fixed to resolve gauge. **Not** global Ceres bundle adjustment or camera-intrinsics optimization.
- Adds native C ABI/JNI, Kotlin graph and regression tests; returns calibrated reprojection diagnostics and rejects degenerate camera geometry.
- Continues exporting only POINTS in GLB/PLY. See [M03_VALIDATION.md](M03_VALIDATION.md). Physical camera tests and high-fidelity dense geometry are still outstanding.

## M04 increments

- User-initiated **offline 9×6-inner-corner checkerboard calibration** via Android document picker/OpenCV. Profile persists privately with RMS/view count and optional EXIF focal identity.
- Per-feature Brown–Conrady inversion replaces estimated-pinhole rays throughout two-view matching, PnP and multi-view track graph when a profile exists. Projects without calibration still use explicit pinhole fallback.
- Profile changes invalidate reconstruction revision and fingerprint; wrong aspect/known zoom is explicitly rejected.
- Fixed regression where versioned sparse artifact keys failed the old `ProjectRepository` regex and could not be reopened/exported.
- Adds synthetic calibration tests, JVM lens tests, printable reference board and capture guide.
- **Still missing:** physical-device validation, real calibrated captures, global Ceres BA, dense surfaces and photo-textures.

## M05 — Real-source validation and reproducible device testing

- Added a non-synthetic **Middlebury Motorcycle** stereo benchmark using physically captured images and reference disparity. CI gates SIFT matches, native sparse points, disparity error, reference-scaled depth error and real-image pose stability; known stereo baseline is used **only for evaluation**. No datasets rehosted.
- Added voluntary capture ZIP export of original project photos, hashes, per-photo metadata, optional calibration, and machine-readable schema-1 `session.json`; added offline `tools/capture_audit.py` and negative-path tests.
- Reduced reciprocal SIFT ratio from .75 to .67 across native-host and Android image-matching adapters after measuring significantly better camera-pose stability on the independent real stereo pair.
- Enabled automated emulator JNI/OpenCV/Room and capture-export instrumentation on CI pushes; this is not arm64 physical camera validation.
- Deferred constrained global Ceres BA and CPU dense Multi-View Stereo until device/real multi-view acceptance is measured, per the original quality-first development plan.

## M06 — Coupled sparse optimization

- Added native C++20 Huber-weighted Schur-complement Levenberg–Marquardt with
  *simultaneous* camera/point increments, global objective acceptance, two fixed
  anchor cameras and bounded allocations. C ABI/JNI/Python bridges are shared.
- Android invokes this only on supported M03 sparse tracks; errors or
  quality-gate failures preserve the previous accepted geometry explicitly.
- Four-camera synthetic pose/point tests, real SIFT matches from rendered
  three-photo fixture and new API-35 Android JNI smoke test. M05 real two-view
  images remain a separate, existing regression suite.
- **Not done:** Ceres/intrinsics global refinement, tested physical-camera
  reconstructions, dense depths, mesh surfaces, textures, GPU acceleration.
- Source: [M06 validation](M06_VALIDATION.md). Do not interpret native sparse
  improvements as improvements to full object completeness or surface quality.

## M07 — CPU depth consistency and dense POINTS

- Genuine rectified CPU multi-view photometric plane sweep (ZNCC), bounded
  inverse-depth hypotheses and texture/uniqueness rejection; camera-z
  depth maps must agree across viewpoints with reverse reprojection checks.
- Kotlin downscales and Brown–Conrady rectifies originals to a uniform view grid,
  derives scene-relative depth bounds from sparse 3D points and calls shared
  native code through JNI. Failure preserves M06 sparse points and records why.
- Separate dense GLB POINTS/PLY exports. Still **no triangle faces**, UV atlas,
  metrically calibrated scale, watertight model or real-phone thermals.
- Host synthetic three-camera planar test and real Middlebury stereo depth-vs-
  independent-ground-truth benchmark; see [M07 validation](M07_VALIDATION.md).

## M08 — Confirmed-depth fusion and observed open triangle topology

- Native M07 depth keeps only reciprocal camera-z confirmed samples for M08;
  samples retain origin pixel, source view, confidence, and support count.
- Spatially close, independent observations are confidence-weighted and fused;
  topology derives from four verified neighboring pixel samples, with strict
  depth discontinuity, edge length, normal and degeneracy checks. No invented
  backfaces or automatic hole filling.
- Real `mode=TRIANGLES` glTF 2.0 GLB with vertex normals and COLOR_0, plus
  face-indexed PLY. Old point-only formats remain distinct.
- Android receives one native depth+mesh pass through JNI, displays interactive
  triangle/wireframe previews, publishes model exports atomically, and falls
  back explicitly when surface coverage is inadequate.
- New synthetic and real stereo benchmark gates, binary format checks and
  Android emulator JNI instrumentation. See [M08 validation](M08_VALIDATION.md).
- **Still not implemented/verified:** UV atlas, continuous high-resolution
  photographic textures, metric scale, full closed object reconstruction,
  physical phone capture tests, photogrammetry on shiny/transparent objects,
  TSDF/Poisson globally fused surface, and industrial topology cleanup.

## M09 increments

- Actual multi-photo UV atlas through the native C++ CPU pipeline with camera-ranked
  visibility; source camera must observe the face, alternative cameras also pass
  calibrated depth consistency checks. No arbitrary geometric hole filling.
- PNG camera atlas with gutters and indexed GLB TRIANGLES with embedded `image/png`
  + `TEXCOORD_0`, separate from the earlier M08 vertex-color GLB and POINTS.
- Android independent up-to-768px photo rectification, JNI, atomic texture files,
  visual export controls, and texture-only failure recovery.
- Synthetic/native, real Middlebury, Android instrumentation and Python contracts;
  [M09_VALIDATION.md](M09_VALIDATION.md). Physical device and true multi-side
  textured object benchmarks remain outstanding.
