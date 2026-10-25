# Source status — integrated engineering preview

## Current verified checkpoint — 25 October 2026

Current application version: **0.19.0-m19**, an engineering preview. Validation
is source-bound: a passing earlier artifact does not certify subsequent source.
Supported input remains **2–12 photographs**, with at most **eight registered
dense views**. Physical scan quality, complete installed workflows and production
release qualification remain open.

### Latest source-bound checkpoint

- **`c215632` [HOST journey — PASS](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40227532716):**
  all three cameras and verified publication/export/reopen remain successful;
  mesh counts are unchanged from `f6cdd12`: **1,618 vertices, 1,296 triangles,
  1,580 boundary edges, 558 reported intersections, zero nonmanifold edges**.
  Fresh diagnostics report **10 supported normals / 2,510 samples**; all
  **3,181 cross-camera candidate visits** reject at **missingNormal**. The later
  association gates were not exercised, so they are not qualified by this run.
  This is better failure attribution, not improved mesh quality. See the
  [bounded diagnostic/experiment record](development/DENSE_SURFACE_DIAGNOSTIC_STATUS.md).
- **`c215632` [CI](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40227532652):**
  Android **920 tests PASS**; both full sanitizer suites completed **4,542 cases:
  4,497 passed, 17 failed, 28 skipped**. Native/candidate/device gates **FAIL**.
  `fac55e9` sanitizer runs were superseded/cancelled, without completed full-suite
  counts. Its separate five-check software result remains valid only for that source.
- **`759ca3e` [CI](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40230409587):**
  the SDK process-cleanup test correction does not change reconstruction code.
  GCC and Clang each completed **4,544 cases: 4,500 passed, 16 failed, 28 skipped,
  zero errors**, with the same 16 failed identities. Only the SDK leader-cleanup
  failure resolved; no new failures appeared. All jobs are terminal; native and
  candidate gates **FAIL**. Android **920 tests**, both release profiles and
  benchmark compilation passed; the ordinary device KVM preflight failed.
  Reconstruction settings remain unchanged.

### Earlier source-bound runtime checkpoints

- **`f6cdd12` real three-photo [HOST journey — PASS](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40220547113):**
  all **3 cameras registered**, including the third camera with **45 tracks**;
  global typed bundle adjustment completed. Output contains **324 sparse points,
  2,469 dense points, 1,618 mesh vertices / 1,296 triangles**, with **1,932 UV
  vertices** and a **1548 × 1036 atlas**. Nine blobs, all three exports,
  publication/reopen, 196 source checksums and privacy checks were verified.
- **Bundle-adjustment evidence:** 693 observations; **12/12 steps accepted**.
  Cost fell from **8.53476e-6 to 8.26268e-7**; median normalized residual fell
  from **1.0779e-4 to 1.95455e-5**. These are internal fit diagnostics, not
  independent physical or metric accuracy measurements.
- **Quality remains open:** the mesh is visibly fragmented and non-watertight,
  with **1,580 boundary edges, 558 reported intersections and zero nonmanifold
  edges**. Dense coverage and reported **zero cross-view fusion** are the next
  output-quality priorities. This HOST fixture pass does not qualify full-object
  reconstruction, installed devices, ARM64 or physical capture quality.
- **`f6cdd12` CI:** Android **920 tests PASS**, both release profiles and benchmark
  compilation PASS. GCC and Clang sanitizer suites each completed **4,513 cases:
  4,468 passed, 17 failed, 28 skipped**. All jobs are terminal; native/candidate/
  device gates **FAIL**. The older `80925b8` four-check software ATD result below
  remains source-bound and does not transfer automatically to later revisions.
- **`fac55e9` five-check Android [smoke — PASS](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40225784945):**
  actual SDK build/install and **5/5 tests**, zero failures/skips, including the
  corrected numeric PnP JNI regression. API35 x86_64 unsupported software TCG
  only—not UI, SAF, Filament, ARM64, a real-photo journey or physical quality.
  Its [ordinary Android build](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40225784831)
  passed **920 tests**; sanitizer runs were subsequently superseded/cancelled,
  not completed full-suite passes.

### Earlier source-bound diagnostic checkpoints

- **`4b23941` [read-only replay](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40217424869):**
  a **14,320-byte** diagnostic retained 45 rows, contributing source UVs,
  projections and anchors. Exact failure agreement (best **one** inlier,
  **16** required), source/cache/schema binding and privacy checks passed.
  Classification: **READ_ONLY_REPLAY_NOT_ORIGINAL_CALL_NOT_QUALITY**.
  The original strict failure and verified partial roundtrip remain unchanged;
  this is not successful third-camera registration. See the source-bound
  [replay documentation](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/4b23941e8424b59ba2056f54c18a3b4bfff634bd/docs/development/REAL_THREE_PNP_REPLAY.md).
- `4b23941` release/sanitizer suites aborted on an oversized autogenerated test
  ID, not after a completed full suite. The reviewed correction shortens the
  ID only; payload and the **8,192-byte identity cap** remain unchanged.
  Later `f6cdd12` suites completed, but the 17 failures remain; this earlier
  diagnostic did not close them.

- **`80925b8` [verification](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40214617937):**
  Android **919 tests PASS**, also observed on `df49877`; both release profiles
  and benchmark compilation pass. GCC and Clang sanitizer pytest each completed
  **4,457 cases: 4,412 passed, 17 failed, 28 skipped, zero errors**, with the
  same 17 failed identities. All `80925b8` jobs are terminal; native/candidate
  aggregate gates **FAIL**. Ordinary installed-device preflight is KVM-blocked.
  Completed release builds do not waive these failures.
- **`80925b8` software-only Android [smoke run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40214618133)
  — 4/4 PASS:** a non-KVM software ATD exercised only native/cache checks.
  It does **not** qualify UI, SAF, Filament, real-photo reconstruction, ARM64 or
  physical capture quality. It does not replace the blocked ordinary device gate.
- **`df49877` real three-photo [HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40211662023)
  — strict FAIL, partial roundtrip verified:** raw SIFT recovered the earlier
  `a573` partial result: **2/3 cameras, 220 sparse / 1,623 dense points,
  884 triangles, 982 boundary edges and 411 reported intersections**.
  This remains fragmented, not watertight or complete-object quality.
  Third-camera PnP had **45 distinct tracks**, required **16 inliers**, and
  retained only **one best inlier across 450 valid hypotheses**; the camera
  remained unregistered on that revision. The later `f6cdd12` HOST result above
  resolves third-camera registration for this fixture.
- The `af6c3fb` RootSIFT trial failed at `MATCHES` with **no model**. It was
  rolled back by `df49877`, not promoted or relabeled as an improvement.
- The published [independent COLMAP reference](development/experiments/skull-colmap-reference.md)
  and [reproduction tool](../tools/qa/skull_reference.py) registered three cameras
  with **250 tracks / 235 unique XY locations per image** on the licensed
  fixture. This is not scanner success, physical ground truth or a mesh-quality
  measurement; detector/matcher/track-building differences remain explicit.

### Historical source-bound runtime checkpoints

- **`f506d2f` [verification](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141070):**
  Android checks passed with **902 tests**; both release profiles and benchmark
  compilation passed. Full GCC and Clang sanitizer suites each completed
  **4,261 cases: 4,211 passed, 22 failed, 28 skipped, zero errors**.
  Native/candidate aggregate gates **FAIL**; full CI is not green.
- **Earlier real three-photo [HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141048)
  on `f506d2f` — strict FAIL:** **2/3 cameras registered**, no global bundle
  adjustment. Bounded partial-model export/reopen is **VERIFIED_NOT_FULL_THREE**:
  **220 sparse points, 1,623 dense points, 1,032 mesh vertices / 884 triangles**;
  the textured representation has **1,126 UV vertices / 884 triangles**, a
  **1548 × 518 atlas** and a published **1,418,476-byte GLB**. Independent checks
  verified 190 checksums, nine blobs, all three exports and before/after-reopen
  records. The mesh is visibly fragmented, with **982 boundary edges, 411
  reported intersections and zero nonmanifold edges**. This does not establish
  watertightness, full-object/high-quality reconstruction or device qualification.
- **App/native source `0055f87`:** [Android checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40192904648)
  passed with **902 tests**. Release profiles, benchmark compilation and planar
  QA passed. The full GCC and Clang sanitizer suites each completed with
  **4,125 passed, 39 failed and 27 skipped**: both gates **FAIL**, not timeouts
  or green full-candidate CI. Earlier results below remain source-bound history.
- **Fixed real three-photo HOST profile on `0055f87` — strict FAIL:** partial
  output registered **2/3 cameras**, with **41 sparse points, 965 dense points,
  299 mesh vertices and 185 textured triangles**. It reports **297 boundary
  edges, 77 recorded intersections and zero nonmanifold edges**. Zero nonmanifold
  edges does not make this open, intersecting partial mesh watertight, complete
  or high quality. Export/reopen assertions were **not reached** in this run.
- `f506d2f` adds test/diagnostic changes to the `0055f87` app/native source.
  Its verified partial roundtrip does not change the strict three-view failure.
  Its separate
  [C-ABI diagnostic](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141080)
  is **PASS_SUPPLEMENTARY_DIAGNOSTIC_ONLY**, after repair of the exact
  **194 raw / 162 distinct / 159 support** case. It is not a release gate.
- [Exact distinct-pair support policy](CALIBRATED_INITIALIZER.md) in `0055f87`
  collapses exact duplicate coordinate pairs for model support and versions sparse
  policy/caches to v2. Numerical, spatial, baseline and pose gates remain;
  this does not establish that all pose-conditioning problems are fixed.

**Engineering APK update warning:** these builds use ephemeral test certificates;
compatible in-place updates are not verified. Export important original captures
and models before considering uninstalling. Do not assume a backup exists or that
uninstalling preserves app-private data. These are not production-signed releases.

### Earlier completed evidence

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
or skips. Both release profiles and benchmark compilation also passed. These are
historical `e493872` results, not qualification of current installed guidance.
See the latest `c215632` checkpoint above for current evidence.

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
