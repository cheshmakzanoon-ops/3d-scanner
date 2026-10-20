# RealityForge M06 — Coupled sparse bundle adjustment (Schur/LM)

**Scope:** production C++20 code, C ABI, Android JNI/Kotlin integration, native
host tests, real-image regression infrastructure retained from M05. This is
**sparse geometry only**. It does not recover dense depth, triangle meshes,
texture maps, metric scale, or independently measure a physical 360-degree scan.

## What changed

The M03 optimizer alternated one point or camera block at a time. M06 adds a
**joint camera–point objective**: compute Huber-weighted reprojection Jacobians
for all retained multi-view observations, eliminate 3-D point increments via
per-track 3×3 Schur complements, and solve the coupled reduced camera normal
system (up to 60 variables for at most 12 cameras). Then back-substitute
point increments from the same step and accept the pair **only if the full
robust objective decreases**. The first two anchor camera poses remain
**bit-identical** to preserve the arbitrary gauge and scale.

- Validates camera orthogonality, finite coefficients, noncoincident anchors,
  camera/track IDs, point counts and duplicate camera observations.
- Rejects observations initially inconsistent with accepted M03 sparse geometry;
  does not promote outliers into new 3-D points.
- Uses damped normal equations, bounded increments, Huber loss and failure
  rollback. It does **not** optimize focal length, distortion or calibration.
- In Android, tries M06 only with ≥3 registered cameras and ≥24 M03 sparse
  points, then checks point retention, robust cost and reprojection error.
  On failure it keeps the M03 result and reports the reason in JSON.
- Existing GLB/PLY exports remain POINTS primitives. M06 changes the cache
  artifact key to prevent accidental reuse of M05 geometry.

## Executed host benchmarks (October 10, 2026)

The native **four-camera** synthetic scene contains 250 points, camera pose
perturbations and image noise; the scene explicitly checks improvement against
known ground truth without supplying it to the optimizer. Example result:

| Metric | Before M06 joint step | After M06 joint step |
|---|---:|---:|
| Median normalized reprojection error | 0.0079147 | 0.000178617 |
| Huber objective (sum, normalized units) | 0.0488893 | 0.0000198827 |

The first two camera matrices must remain unchanged. Further tests exercise
outlier rejection, perfect-noise-free scenes, deterministic output, singular
camera gauge, duplicate observations, NaNs and ABI input validation.

The bundled **three synthetically rendered photo** fixture also goes through
actual SIFT extraction, reciprocal feature matching, native two-view SfM,
third-view PnP and the new joint optimizer. Its measured host output:

| Metric | Value |
|---|---:|
| Input anchor points | 524 |
| Three-view track candidates | 244 |
| Sparse points preserved | 524 |
| Median normalized reprojection error | 0.000195342 → 0.000185395 |
| Huber cost | 0.00006203996 → 0.00004204440 |
| Accepted joint solver steps | 6 |

This is **not a real physical-object benchmark**. M05's independent real
Middlebury two-view test remains the real-photograph acceptance gate, but it
has only two cameras and cannot validate global multi-camera BA.

## Validation boundaries

Host GCC and Clang `-Werror`, native CTest, pytest, ASan/UBSan and the
calibrated three-photo harness were exercised during development. The GitHub
workflow additionally gates Android Gradle build/lint, APK permission/ELF
validation and API-35 x86_64 emulator JNI tests; record CI outcome separately.

This Schur implementation has bounded complexity but has **not** been profiled
for thermal safety on a physical phone. It is not Ceres Solver, and no claim
of dense surfaces, global intrinsics refinement or real-scene metric accuracy
is justified by these synthetic measurements. A production-grade global
optimizer can later replace the interface without changing the privacy model.

## Next gate: M07

Acquire a real multiview capture from an arm64 phone with lens calibration,
validate camera/feature geometries and compare with an independent SfM system.
Build the first CPU-only dense Multi-View Stereo reference (plane sweep or
coarse-to-fine PatchMatch) with confidence and geometric consistency before
claiming any complete meshes, GPU acceleration or texture quality.
