# Current source status — M19

> STOP archive: the pending86 candidate/evidence updates in this document are an
> unfinished historical draft. Later source fixes and old86 debug-notice
> invalidation are not reconciled here. No final replacement candidate is qualified.


Version0.19.0-m19; campaign base `ad3029193f304c70931b94ea7a0df90b69d84b07`.
The current code includes M17 operation cancellation, M18 per-reference depth
checkpoints and M19 persisted scan diagnostics. Sparse work remains12-photo and
depth8-view bounded; see [README](../README.md) and [architecture](ARCHITECTURE.md).
Physical complete-object geometry/texture/resource evidence, release signing and
installed candidate qualification remain open. Agent branches below this campaign
base are separate deliveries until100 integrates and verifies runtime dependencies.

The audited SHA `fdd16fce4aa7b1b7d365c0f43e89964d3028bf42` passed
[Actions38488820626](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626):
11 CTest targets,117 pytest cases and32 API35 emulator tests. These exact historical
counts are not physical handset or later-candidate evidence. Current receipts live
under `docs/agents/status/`; each must identify its own source/artifact and actual gates.

The 081–100 campaign adds executable native/fuzz/corpus/quality/device/export/
packaging/security/dependency/qualification tooling. The integrated host suite
requires095's pinned QA-only installation rather than the original scientific
requirements alone; see [BUILDING](BUILDING.md#integrated-campaign-qa). Tooling
delivery does not establish the unimplemented/unwired001–080 runtime paths or
physical evidence. The final integration applicability keeps all18 required gates
open until their own exact-candidate records pass. Current physical/reference/
held-out/signing/beta prerequisites remain unavailable, and known failed assets or
sanitizer checks must stay visible in their independent reports.

## Frozen081–100 combined measurement —20 October2026

The immutable tested candidate is `86df647bcdba4b110ad36dfb6ce572adb4261462`.
Its debug app APK SHA256 is
`3b747cd8d568e8c64fad1993033089a30b006998337c5225d8db587d613c2f58`;
test APK SHA256 is
`4224aa71f0e3d4b7507a54092ca18fd20537b99d57bb3e3f3416ddea2ae4c523`.
Later integration reports/receipts are metadata, not new binary provenance.

GCC and Clang Release each pass764 pytest cases and11 CTest tests. Both sanitized
configurations pass11 CTest tests but fail24 Python cases each, with740 passes;
strict LeakSanitizer failures remain enabled and unresolved. All30,000 native fuzz
iterations and three numerical comparisons pass separately. Debug app/test compile,
35 JVM tests, lint and actual package privacy/16KiB checks pass. Approved Python
host/QA/security graphs pass scoped scanners with zero active findings; reviewed
exceptions are retained. Legacy pytest8.4.1 entrypoints still fail their audit.

The exact candidate's independent texture gate fails23 UV-overlap pairs despite
the historical texture benchmark's PASS/exit0. Held-out and physical appearance
remain unqualified.064's external analytic PLY fixture also retains a supplied
normal failure; this is not proof that its serializer changed winding. R8/AAB and
85 packaged-APK checks pass on the separately identified004 release overlay, not
on this frozen86 debug artifact. The license owner's first-party distribution
choice and production signing remain BLOCKED_EXTERNAL.

Agent099's actual clean-source/APK-backed fixed18-gate decision is FAIL, with no
rejected input records. The native and legacy dependency failures are admitted;
physical scene/device gates remain BLOCKED_EXTERNAL. The scoped host scanner and
debug compilation passes do not qualify full runtime security, complete installed
instrumentation or full product wiring. No required gate is waived. Software
emulator compatibility and synthetic controller outcomes are supplemental and must
be read at their actual terminal status; physical ARM64 measurements remain absent.

In the integrated100 tree, read `docs/agents/integration/081-100-delivery-report.md`,
`validation.json`, `candidate-qualification.json` and the numbered status receipts.
The retained external099 canonical decision is
`/workspace/campaign/evidence/099/final-86/decision.json`, SHA256
`fe52d0fb187435b9d9bac8c0aac3417ef17fe345ca527780ce1d9e80707f2f6a`.
Its input directory contains exact candidate/record JSON and hash-named backing
blobs. Source-specific UV/provider/release observations remain separately labeled.

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

## M15 recovery and M16 incremental registration

M15's staged, checksum-verified eight-view Android adaptation was recovered and
published after an interrupted session. M16 then adds first-party incremental
registration over the current shared sparse frame, stable bounded track snapshots,
candidate-identity conflict rejection, native pose validation, explicit remaining
images and all-pairs final refinement. Eight JVM cases and a production-JNI
synthetic visibility-chain regression accompany the source. These need the
commit-specific Android CI result; no physical-device or full-object completion
claim is made. See M15_ADAPTIVE_ANDROID.md and M16_INCREMENTAL_REGISTRATION.md.


## M17 — CPU-native cancellation (operation scoped)

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
