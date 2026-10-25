# Source status — integrated engineering preview

## Current verified checkpoint — 25 October 2026

Current application version: **0.19.0-m19**, an engineering preview. Validation
is source-bound: a passing earlier artifact does not certify subsequent source.
Supported input remains **2–12 photographs**, with at most **eight registered
dense views**. Physical scan quality, complete installed workflows and production
release qualification remain open.

### Completed evidence

- **Synthetic full-controller journey — PASS on `5beb2d3`:** three fixed relief
  photographs passed through the actual reconstruction controller, dense stage,
  observed mesh and photographic texture generation. All three cameras registered:
  **904 sparse points, 22,259 textured triangles, 1548 × 1164 atlas**. Nine
  artifacts were published and hash-checked; sparse PLY, mesh PLY and textured GLB
  exports were structurally validated. Publication identities and exported bytes
  survived Room database reopen; original photographs were preserved.
- **Scope:** `SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY`.
  HOST resources are modeled. This is not installed GLES/SAF interaction, physical
  capture, handset performance or store qualification. Nine artifacts are not
  nine independently validated formats. The low-resolution eight-photo fixture
  remains unsuccessful.
- **Android build — PASS on `68ef9b7`:** debug app/test APK builds, lint,
  **864 unit tests + 18 HOST JVM tests = 882 tests**, with no failures, errors or
  skips in those reports. GCC/Clang release profiles and benchmark compilation
  also passed; benchmark compilation is not a measured phone-performance result.
- **Exact planar ownership native QA — PASS on `813f312`:** the dedicated
  ASan/UBSan/LSan run passed one native CTest and 83 differential cases; the
  release run passed 137 cases. This bounded [QA module](../reconstruction/qa/exact_planar_owner/README.md)
  is **OFF by default**, not production surface integration, a repaired scanner
  mesh, or mobile qualification.
- **Selected installed graphics test — PASS on `7ff6395`:**
  `PhotographicViewerTest#filamentRestoresCameraPausesAndRejectsOversizedResize`
  passed on an API-35 emulator. It checks optional Filament idle-frame stability,
  control/resize wakeups, pause/resume and lifecycle behavior. The default
  application viewer remains GLES; no handset power-saving claim follows.

### Open failures and limits

- **Real licensed stereo full-controller HOST journey — FAIL on `68ef9b7`:**
  run `40176715540` failed at `MATCHES`; no model was produced. Read-only native
  replay of the persisted 276 reciprocal matches returned `WEAK_BASELINE`.
  The two 1600 × 1067 photographs yielded 1,010 and 1,280 SIFT features; the
  estimated 35mm-equivalent focal length was 88mm. These aggregate diagnostics
  neither establish the physical camera baseline nor turn the failed controller
  journey into a pass.

- **Installed synthetic complete journey — FAIL on `7ff6395`:** the UI driver
  rejected a correctly identified DocumentsUI thumbnail row because the row was
  exposed as non-clickable. Import and reconstruction had not begun. This is not
  evidence of an engine failure or a passing complete session. Source `c3f26f3`
  repairs selection through real touch inside the uniquely identified item,
  preserving navigation/import assertions; its installed rerun remains unrun.
- **Topology remains unacceptable for watertight/printing claims.** The `5beb2d3`
  synthetic result reports 113 components, 9,542 boundary edges and 653 nonmanifold
  edges. With the same three original photographs, the preceding `aa9e693` result
  had 656 nonmanifold edges and the earlier `1b4e91c` result had 646. These paired
  runs are not byte-identical raw-depth replays and do not establish a broad
  quality improvement. Intersection analyses hit their work budget, so their
  counters cannot prove the absence or reduction of intersections.
- **Full-candidate CI — FAIL on `68ef9b7`.** In run `40176715542`, GCC and
  Clang sanitizer pytest executions exceeded their 600-second limit. Ordinary
  device KVM preflight and aggregate checks also failed. Passing release shards,
  benchmark compilation and 882 Android tests do not replace these gates.
  Historical `707a10e` sanitizer runs timed out after 600.170s and 600.120s
  (exit −9); both passed 48 CTests but retained control, numerical and native
  benchmark failures. That evidence is not resolved by a later build pass. The selected emulator result does not
  replace ordinary device or complete installed-session gates.
  Cancelled or superseded runs are not passes. Physical reference geometry,
  complete-object texture quality, ARM64 resource/thermal measurements,
  production signing and store acceptance remain unqualified.

### Subsequent source changes, not blanket qualification

`aa9e693` corrects continuous-cell inverse-depth interpolation and versions affected
cache identities. `5beb2d3` chooses a shared supported diagonal across identical
observed full quads, rather than discarding a valid half-face or choosing conflicting
splits across views. It does not invent vertices, lower geometry gates or solve
arbitrary nearby overlapping surfaces. The synthetic full-controller journey
passes, but the topology defects above remain.
The source retains original photographs and refuses stale derived caches.

`707a10e` adds a [conservative EXIF focal prior](reconstruction/EXIF_FOCAL_PLANE_PRIOR.md)
for valid isotropic focal-plane metadata when the direct 35mm tag is absent.
Explicit calibration/direct-tag precedence remains intact; derived evidence is not
a measured sensor or distortion model. Anisotropic, stale or malformed metadata
remains unknown. Its 25 Kotlin and 110 affected Python tests passed; the new
actual-JPEG instrumentation cases compiled in the `707a10e` Android build but
remain unrun. Host tests and successful compilation do not qualify those device cases.

`e493872` adds [no-anchor failure guidance](reconstruction/ANCHOR_FAILURE_GUIDANCE.md).
Only uniformly allowlisted `WEAK_BASELINE`, `PLANAR_AMBIGUITY` or `ROTATION_ONLY`
pair rejections select the persisted `CAMERA_REGISTRATION_FAILED` outcome;
any low-match, missing, unknown or non-allowlisted rejection retains the
insufficient-overlap fallback, even alongside an allowlisted rejection.
Android string resources provide qualified weak/ambiguous-geometry recapture
advice. Geometry acceptance is unchanged; this is not recovery or evidence that
real captures now reconstruct. Its
[Android build, lint and tests](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40179213109)
passed: **870 unit tests + 18 HOST JVM tests = 888**, with zero failures, errors
or skips. Both release profiles and benchmark compilation also passed. Device
checks failed; sanitizer jobs remain running at this checkpoint, not passes.
Full-candidate CI is not green, and installed guidance remains unqualified.
The latest shared APK is still the `707a10e` engineering preview, without this
new runtime guidance.

Public evidence: [real licensed stereo HOST failure](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40176715540),
[`68ef9b7` Android build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40176715542),
[dedicated exact planar ownership QA](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40168651012),
[supported-diagonal full-controller HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162711185),
[depth-corrected full-controller HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162345022),
[earlier `1b4e91c` HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40147098954),
[`707a10e` Android build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40164183394),
[selected installed tests](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162907075).
See [next gates](NEXT_MILESTONE.md). Records below are historical, not current
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
