# Source status — integrated engineering preview

## Current verified checkpoint — 24 October 2026

Current application version: **0.19.0-m19**. The source has progressed beyond the
historical M19 implementation, but the product remains an engineering preview.
The full-controller result is bound to `660b94480160e0d52ecbf5879008040d9518f79c`.
Current source `1d01072779dc1d0b0d407b6299ad24edb6bd30b6` changes a legacy test
fixture; its production implementation is unchanged from that checkpoint.

- **Synthetic full-controller journey — PASS:** three fixed relief photographs
  passed through the actual reconstruction controller, dense reconstruction,
  mesh and photographic texture generation. All three cameras registered,
  producing **904 sparse points**, **17,522 textured triangles** and a
  **1548 × 1164 texture atlas**. Nine artifacts were published and hash-checked;
  sparse PLY, mesh PLY and textured GLB exports were structurally validated.
  Publication identity and export bytes remained consistent after Room database
  reopen, and the original photographs were preserved.
- **Scope:** `SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY`.
  This is a synthetic HOST test with modeled resources, not installed GLES/SAF
  interaction, physical capture, handset performance or store qualification.
  Nine hash-checked artifacts do not mean nine independently validated formats.
  The eight-photo low-resolution fixture remains unsuccessful.
- **Build baseline:** earlier `37fdaf02` validation passed 850 Android unit tests
  + 18 HOST JVM tests (868 total), debug app/test APK builds, GCC/Clang release
  profiles and benchmark builds. These are source-bound results, not a blanket
  pass for every later commit.
- **Remaining CI/device gates:** the full candidate CI is not green. Sanitizer
  and device gates remain open; earlier sanitizer profiles timed out at 600
  seconds, and KVM preflight blocked device tests. Physical capture, installed-app
  visual behavior and ARM64 performance/quality remain unvalidated.
- **Topology:** the separate synthetic native audit reports 509 nonmanifold
  indexed edges. UI warnings disclose that defect; the new HOST result does not
  establish watertightness, printing suitability or complete-object quality.

Public evidence: [full-controller HOST run 40142555215](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40142555215),
[earlier build baseline](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40120401316),
[topology counterexample](development/SURFACE_TOPOLOGY_QUALIFICATION.md#separate-native-counterexample).

The integrated source includes service-owned durable jobs, revision-bound
publication/checkpoints and library/curation/recovery/export routes. Supported
input remains **2–12 photographs**, with at most **eight registered dense views**.
Installed-app complete-session and model-studio qualification, physical reference comparisons,
resource measurements and production signing remain open. See
[next gates](NEXT_MILESTONE.md). The records below are historical, not current
acceptance results.

## Historical M19 baseline

Version 0.19.0-m19 includes M17 operation cancellation, M18 per-reference depth
checkpoints and M19 persisted scan diagnostics. Sparse work remains 12-photo and
depth 8-view bounded; see [README](../README.md) and [architecture](ARCHITECTURE.md).
Physical complete-object geometry/texture/resource evidence, production signing
and installed-app qualification remain open. Host validation requires the pinned
[QA dependencies](BUILDING.md#independent-host-qa-dependencies); tooling alone does
not establish runtime behavior or physical quality.

## Historical M12 snapshot

| Area | Actual state |
|---|---|
| App/capture | Kotlin/Compose/CameraX, local imports, calibration, private project storage |
| Sparse geometry | Shared C++ SfM/PnP, multi-view tracks and joint Schur-complement bundle adjustment; fixed intrinsics |
| Depth/surface | Bounded CPU stereo, reciprocal checks and open indexed triangle patches; not complete manifold reconstruction |
| Photographic UV | Full XYZ cross product and glTF top-left origin repaired; all 3,530 existing triangles textured on one real stereo benchmark |
| Appearance | Bounded, measured exposure gains and deterministic camera-label smoothing; not seamless blending |
| Native validation | Seven CTest targets and 105 Python checks passed locally; M10 photometric GCC/Clang/sanitizer evidence retained |
| Android validation | M11 full CI and 20 emulator tests passed; M12 new session tests require CI; no physical camera proof |
| Preview | Bounded local photographic GLES3 viewer plus vertex-color diagnostics; new shader/file/lifecycle tests |
| Resource limits | At most 12 sparse photos, three Android depth views at 256px edge, texture sources up to 768px |
| Release status | Engineering prototype only; not consumer/store ready |

The new fixes improve measured material projection without altering mesh geometry
or relaxing visibility gates. 100% benchmark triangle coverage is not whole-object
completeness. See M10_VALIDATION.md for actual numbers and test boundaries.

Historical implementation/validation records are retained in M02–M09 documents.
The full product contract remains PROJECT_PLAN.md; phases requiring physical
handsets, complete-object ground truth and sustained resource measurements remain
open. No synthetic or two-camera stereo test closes those gates.

See M11_VIEWER.md for supported GLB subset, memory budgets and exact tests. No reconstruction quality ceiling or physical-device gate is changed by the viewer.

M12 adds bounded revision-checked reopening, operation reservation around exports/deletion,
cancellable copying and capture-revision checks. The full synthetic photograph-to-model
session is exercised with the actual Android ViewModel/pipeline, not only native fixtures.
See M12_SESSION_RELIABILITY.md; unchanged geometry limits and physical gates remain open.


## M14 / M15 increments

- M14 native/JNI: 2–8 calibrated views, up to three selected matching sources per
  reference, explicit 100k sample budget, active luminance caching, eight-camera
  exposure solve and bounded 3x3 photographic atlas. Known-camera synthetic plane
  coverage increased 1.71x against the same fixed eligible surface grid; the
  existing real stereo output is unchanged. Not a full object/phone benchmark.
- M15 Android: activates up to eight views, adaptive 384/256 depth edge and stride,
  compact/standard texture limits, one-image-at-a-time decode, cancellation checks
  during rectification and visible sparse depth priors. New settings are versioned.
- Kotlin and device assertions are commit-specific CI gates. Physical handset,
  large-scene resumption, arbitrary 360-degree registration and final-quality
  topology/textures remain outstanding. Neither milestone establishes perfection.

## M15 adaptation and M16 incremental registration

M15 adds bounded eight-view Android adaptation. M16 adds first-party incremental
registration over the current shared sparse frame, stable bounded track snapshots,
candidate-identity conflict rejection, native pose validation, explicit remaining
images and all-pairs final refinement. Eight JVM cases and a production-JNI
synthetic visibility-chain regression accompany the source. These need the
commit-specific Android CI result; no physical-device or full-object completion
claim is made. See M15_ADAPTIVE_ANDROID.md and M16_INCREMENTAL_REGISTRATION.md.


## Historical M17 — CPU-native cancellation (operation scoped)

- Added independent, nonrecycled JNI operation handles backed by atomics and
  shared lifetime guards. The caller can signal an active native depth/mesh/
  photographic-texture job from the Android main thread. Token registration is
  limited to 16 concurrent operations to prevent unbounded resources.
- Every native execution installs a thread-local scope; no process-global stop
  flag and no cross-project interruption. Nested calls restore the earlier flag.
- CPU loops periodically check for cancellation, including long image/atlas rows.
  Cancellation propagates as a top-level error, never silently as an acceptable
  mesh or UV-quality warning.
- `ScannerViewModel.pause()` signals the native token *before* cancelling the
  coroutine; activity/view-model teardown signals too. Kotlin checks the job
  after JNI and deletes `.partial` exports in `finally`.
- Added five native cancellation regressions and an Android instrumentation
  case verifying token isolation, stale-handle rejection, and no output files
  from pre-cancelled native runs.
- This is **cooperative** cancellation: the maximum pause delay is bounded by
  the next native checkpoint; sparse SfM operations still cancel at Kotlin
  boundaries rather than mid-C++ iteration. No per-reference resume yet.

## M18 — Native per-reference depth resumption

- Calibrated rectified camera depth maps can be atomically cached and reused
  when their SHA-256 input identity and binary CRC validate; invalid and
  incomplete files are recomputed without discarding valid references.
- M17 cancellation remains scoped, and the resulting point/surface/photographic
  exports are freshly validated, not read directly from checkpoint files.
- Host/JVM/Android regression gates are in `docs/M18_DEPTH_CHECKPOINTS.md`.
- No physical-device 360-degree accuracy, watertightness, or power-loss
  durability claim.

## M19 — Persisted objective scan diagnostics

- Counts registered cameras against the imported photo total. Reports optional
  sparse parallax and normalized reprojection residuals without inventing
  ground-truth measurements.
- Distinguishes sampled depth pixels from reciprocal-consistent pixels;
  reports observed mesh vertices/triangles and measured photographic face
  support, never extrapolates to an unseen complete object.
- Deterministic, testable recapture guidance in JSON and a dedicated Android
  quality card. User exports the revision-bound JSON through the system picker.
- Pure Kotlin heuristics have JVM tests, JSON adapter has Android instrumentation.
- No change to native reconstruction acceptance gates, save revisions, or
  capture privacy. Real physical object coverage/accuracy remains unmeasured.
