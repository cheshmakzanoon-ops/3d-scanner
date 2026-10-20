# M03 — Multi-view point triangulation and bounded sparse refinement

**Scope:** an extension to the M02 anchor-pair SfM pipeline. The initial two registered cameras
remain fixed (rotation and translation) to preserve coordinate gauge and the arbitrary baseline
scale. Additional registered cameras may move during robust alternating optimization. The
reconstruction remains a **sparse point cloud**, not a dense surface, triangle mesh, or texture.

## New implementation

- Conflict-aware multi-camera feature-track graph on Android. It uses reciprocal SIFT pairs
  filtered by each pair's registered-camera epipolar geometry. Any component with competing
  keypoints from the same view is rejected rather than silently merged.
- Native C++ `reconstructMultiView`: pairs of previously registered cameras triangulate new
  observations even if one or both anchor views cannot see them. Rejects poor parallax, points
  behind cameras, excessive residuals, non-orthonormal camera transforms and invalid indices.
- Bounded, Huber-weighted damped least-squares refinement alternates point updates with
  camera pose updates. The anchor pair is fixed; calibration intrinsics are *not* refined.
  This is a local sparse optimizer, **not Ceres global bundle adjustment**.
- JNI bridge with explicit buffer sizes and schema-3 JSON containing points, refined poses,
  rejected track count, and before/after reprojection diagnostics.
- Android integrates refined sparse output into the existing point viewer and GLB POINTS / PLY
  diagnostics. It falls back explicitly to the M02 anchor cloud on failure or poor quality.
- Bumped engine/artifact fingerprint to M03 so cached M02 results are never silently reused.

## Actual verified host tests (do not mistake for real-scene evidence)

The M03 C++ native suite includes controlled three-camera synthetic tracks:
320 reconstructed point tracks, including 160 that are not in the anchor A-B intersection,
with two fixed anchor cameras and a noisy initial third pose. After 20 bounded alternating
iterations, median normalized reprojection error fell from about 0.00218 to about 0.00020,
and the third-camera translation error improved. The suite also covers geometric outliers,
zero-baseline, duplicate camera observations and invalid C API arguments.

**Limits:** real-photo benchmarks, mixed-lens intrinsics, image masks, global BA including
intrinsics, loop closure, device hardware measurements and dense MVS remain separate gates.
Do not translate synthetic normalized residuals into physical millimeters.

## Acceptance criteria for M03 CI

- GCC/Clang `-Werror` host C++ compile, native tests and existing pytest suite.
- Android SDK/NDK compilation, JVM tests, lint and APK structure/permission verification.
- Actual device instrumentation is optional and remains unverified unless run.

## Next gate (M04)

Acquire authorized, calibrated physical-object photo sequences; test actual M03 Android
capture and photo ingestion in airplane mode; profile thermal/RAM; then implement global
sparse bundle adjustment with carefully constrained focal/distortion parameters and a
reproducible independent geometric baseline before beginning dense MVS.
