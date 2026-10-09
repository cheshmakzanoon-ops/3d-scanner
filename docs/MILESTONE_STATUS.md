# Status — M03 (2026-10-09)

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
| Dense stereo/fusion/meshing | Preserved plan | Not implemented |
| UVs/photographic texturing | Preserved plan | Not implemented |
| Export | Sparse GLB POINTS and PLY | Host output validated; no mesh/OBJ/STL export yet |
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
