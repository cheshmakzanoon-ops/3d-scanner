# RealityForge M02 — Test evidence and boundaries

**2026-10-09.** M02 advances M01's independent stereo prototype to same-frame
registration of additional cameras. It does not reconstruct triangles, textured meshes,
or metric geometry.

## Actual host tests

- 20 portable C++ tests passed with CTest (GCC, Clang -Werror, and GCC ASan/UBSan) (includes M02 PnP, C ABI and degenerate inputs).
- 57 Python tests passed (includes 16 new M02 checks and existing M01 checks).
- Generated 3-photo synthetic relief benchmark: 589 reciprocal anchor matches, 579
  triangulated anchor points, 283 cycle-consistent 3D–2D candidates for photo three,
  282 PnP inliers, median normalized reprojection error ~0.000250, camera rotation error
  ~0.023 degrees and translation error ~0.005 in anchor unit-baseline coordinates.
- Inputs are the bundled *synthetically rendered* photos, not a physical scanner test.
- M02 Kotlin/NDK compilation, unit tests, lint, APK/permission/16KB validation passed
  in GitHub Actions run 37968127815; native host job in that run failed strict GCC
  warning checks for 3 loop-formatting statements (corrected in follow-up).
  JNI/device runtime execution remains unverified.

## Known limitations

Additional views are registered only when they can observe established anchor points
and are mutually matched to both anchors. This misses valid observations when one
anchor view occludes the point. Poses do not undergo global bundle adjustment. Camera
calibration is pinhole/estimated from EXIF where available and lacks lens-distortion
calibration. A new point cloud is **not** triangulated from the additional registered
views. A dense photogrammetric mesh and photographic texturing remain unimplemented.

Android CI compiled an M02 debug APK, but GUI, camera and native JNI execution still
require emulator and physical-device validation.
