# RealityForge — offline Android photo-to-3D scanner

## Latest source-bound context — 25 October 2026

- `0df4a2df5e` real-three HOST journey **PASS**, with unchanged fragmented model;
  v12 diagnostics report **80 source links and zero proposed welds**. This is
  attribution/coverage evidence, not improved mesh or complete-object quality.
- Both `0df4a2df5e` sanitizer suites: **4,500 passed, 16 failed, 28 skipped**.
  Full native qualification remains failing; passing HOST does not waive it.
- `8c42d3a285` [CI is terminal](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40236046514):
  GCC and Clang each completed **4,571 cases: 4,522 passed, 21 failed, 28 skipped,
  zero errors**, with the same 21 failed identities. The SDK unexpected-arguments
  test resolved; six new parser-child exit failures and 15 remaining baseline
  failures persist, including runbook help. Native/candidate gates **FAIL**.
  Android **920 tests**, both release profiles and benchmark compilation passed;
  ordinary device KVM preflight failed. The staged parser/import-order test
  correction targets those six identities but has **no new CI result** yet.
- The separately pinned [eight-image wider-orbit sparse diagnostic](docs/development/REAL_EIGHT_ORBIT_SPARSE.md)
  [failed on `f2d4e3ac88`](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40238971460):
  the selected invocation failed, then a script-context import error prevented
  evidence sealing and lost the original output. Registration and bundle-adjustment
  outcome are **unknown**; the original invocation cause is unresolved. The narrow
  import correction is locally tested, but its source-bound rerun is **NOT_RUN**
  and unqualified. The fixed eight inputs, sparse-only stopping rule and existing
  three-photo fixture remain unchanged; no dense/full-object/device-quality claim.
- Ordinary [`f2d4e3ac88` CI is terminal](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40238971406):
  both sanitizer suites completed **4,716 cases: 4,674 passed, 14 failed, 28 skipped,
  zero errors**, with identical failed identities. All six parser cases and the
  release-missing-records test now pass; no new failures versus the 21 on `8c42d3a285`.
  Runbook help still fails. Android **920 tests**, both release profiles and benchmark
  compilation passed; device/native/candidate gates remain **FAIL**. These results
  do not qualify the unrun sparse-evidence correction.


**Engineering preview based on M19, version 0.19.0-m19, with subsequent
correctness and integration work. Not a completed consumer product or a guarantee of
high-quality complete-object scanning.**

## Current validation — 25 October 2026

**The fixed licensed three-photo HOST journey now passes with all three cameras
registered; installed-device scanning and high-quality complete-object
reconstruction are still not qualified.**
Results below belong to the named source revisions, not automatically to later commits.

| Area | Verified result / remaining limit |
| --- | --- |
| Three-photo full-controller HOST test (`5beb2d3`) | **Passed:** three fixed synthetic relief photographs → actual reconstruction controller → dense points, mesh and photographic texture → nine published artifacts → three validated export formats and Room database reopen. All three cameras registered; 904 sparse points, 22,259 textured triangles and a 1548 × 1164 atlas. |
| Test scope | `SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY`: modeled HOST resources, not physical capture, installed GLES/SAF interaction or release qualification. The eight-photo low-resolution fixture remains unsuccessful. |
| Real licensed stereo full-controller HOST test (`68ef9b7`) | **Failed at `MATCHES`; no model produced.** Read-only replay of persisted matches returned native `WEAK_BASELINE`; this is not a diagnosis of the physical camera baseline. |
| Android build (`c215632`) | Android checks passed with **920 tests**. These are not installed photo-to-model qualification. |
| Fixed real three-photo HOST profile (`c215632`) | **PASS:** all 3 cameras registered, third camera supported by 45 tracks, global typed bundle adjustment completed. 324 sparse / 2,469 dense points, 1,618 mesh vertices and 1,296 triangles; nine blobs, three exports, publication and reopen verified. Still fragmented: 1,580 boundary edges, 558 reported intersections, 0 nonmanifold edges—not watertight or device-qualified. |
| Software-only Android native/cache/PnP smoke (`fac55e9`) | **5/5 passed**, zero failures/skips, after actual SDK build/install on API35 x86_64 unsupported software TCG. Includes corrected numeric PnP JNI; not UI, SAF, Filament, a real-photo journey, ARM64 or physical-quality qualification. |
| Installed emulator tests (`7ff6395`) | The synthetic UI journey failed in DocumentsUI selection **before import/reconstruction**. The separate optional Filament rendering/lifecycle test passed. The selector repair in `c3f26f3` still needs a completed installed rerun. |
| Remaining CI gates (`c215632`) | **Failed:** GCC and Clang each completed 4,542 cases: 4,497 passed, 17 failed, 28 skipped. Native/candidate/device gates failed. `fac55e9` sanitizer runs were superseded/cancelled, not completed full-suite results. On `759ca3e`, GCC and Clang each completed 4,544 cases: 4,500 passed, 16 failed, 28 skipped, zero errors; only the SDK leader-cleanup failure resolved. All jobs are terminal; native/candidate/device gates still fail. |
| Exact planar ownership QA (`813f312`) | Dedicated ASan/UBSan/LSan run: 1 native CTest and 83 differential cases passed; 137 release cases passed. **OFF by default**, native QA only—not production integration or mobile qualification. |
| Mesh quality (`5beb2d3`) | The synthetic result still has 113 components, 9,542 boundary edges and **653 nonmanifold edges**. Intersection analysis is budget-incomplete. The supported-diagonal fix does not establish watertight, overlap-free or complete-object quality. |

Fresh `c215632` diagnostics retain the same mesh and locate the immediate fusion
blocker: **all 3,181 cross-camera candidate visits reject at `missingNormal`**;
only **10/2,510 normals** are supported. Later association gates were not exercised,
not proved satisfactory. See [dense/surface findings and rejected experiments](docs/development/DENSE_SURFACE_DIAGNOSTIC_STATUS.md).
Current priorities remain measured depth/normal support and coverage, not the
resolved third-camera pose failure. No settings or quality gates are relaxed;
a HOST pass does not establish full-object or metric quality.

Historically, the `af6c3fb` RootSIFT trial regressed to `MATCHES` with no model and was
rolled back, not promoted. `df49877` restored raw SIFT and recovered the earlier
partial result; that revision did not solve third-camera registration. Its third-camera PnP
had 45 distinct tracks, required 16 inliers, and found only one best inlier across
450 valid hypotheses. The [independent COLMAP reference](docs/development/experiments/skull-colmap-reference.md)
registered three cameras with 250 tracks / 235 unique XY locations per image;
that is a capability comparison, not a scanner pass or physical ground truth.

The `4b23941` [read-only PnP replay](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40217424869)
retained a bounded 14,320-byte diagnostic with 45 rows, source UV/projection/anchor
evidence and exact failure agreement (best one inlier; 16 required). Source,
cache, schema and privacy checks passed. This is
`READ_ONLY_REPLAY_NOT_ORIGINAL_CALL_NOT_QUALITY`; the original strict failure
and partial roundtrip are unchanged. See the [replay record](https://github.com/cheshmakzanoon-ops/3d-scanner/blob/4b23941e8424b59ba2056f54c18a3b4bfff634bd/docs/development/REAL_THREE_PNP_REPLAY.md).
Its release/sanitizer suites aborted on an oversized autogenerated test ID;
the reviewed fix shortens only that ID, preserving the payload and 8,192-byte
cap. Later `f6cdd12` full suites completed, but the 17 failures above remain.

Source `e493872` adds [persisted camera-failure guidance](docs/reconstruction/ANCHOR_FAILURE_GUIDANCE.md)
for uniformly allowlisted weak/ambiguous pair rejections and resource-backed
recapture advice, without weakening acceptance gates. Installed guidance remains
unqualified. `0055f87` adds [exact distinct-pair support and sparse policy v2](docs/CALIBRATED_INITIALIZER.md),
not a fix for all pose-conditioning problems. `f506d2f` verifies a bounded partial
model roundtrip, not a full three-view reconstruction. Its supplementary C-ABI diagnostic passed after
repairing the exact 194-raw / 162-distinct / 159-support case; this is
**diagnostic-only**, not a release gate.

**Engineering APK update warning:** these builds use ephemeral test certificates;
compatible in-place updates are not verified. Export important original captures
and models before considering uninstalling. Do not assume a backup exists or that
uninstalling preserves app-private data. These are not production-signed releases.

Recent source fixes correct inverse-depth interpolation on projective planes,
choose a consistent supported diagonal for identical observed full quads, and
stop idle redraws in the optional Filament backend. The default GLES viewer was
already demand-driven. These are scoped correctness improvements, not proof of
physical scan accuracy, general overlap removal or measured battery savings.
A [conservative EXIF focal prior](docs/reconstruction/EXIF_FOCAL_PLANE_PRIOR.md)
now handles valid isotropic focal-plane metadata when a direct 35mm tag is absent;
it remains derived, not measured lens calibration, and declines inconsistent inputs.

Evidence: [`c215632` fresh HOST diagnostics](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40227532716),
[`c215632` build and full-suite checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40227532652),
[`fac55e9` five-check Android smoke](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40225784945),
[`fac55e9` build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40225784831),
[`f6cdd12` real three-photo HOST pass](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40220547113),
[`80925b8` build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40214617937),
[software-only native/cache smoke](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40214618133),
[`df49877` recovered partial HOST result](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40211662023),
[`f506d2f` Android build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141070),
[real three-photo HOST profile and partial roundtrip](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141048),
[`f506d2f` supplementary C-ABI diagnostic](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40198141080),
[full-controller HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162711185),
[real licensed stereo HOST failure](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40176715540),
[`68ef9b7` Android build and required checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40176715542),
[dedicated exact planar ownership QA](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40168651012),
[installed UI and Filament test](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162907075).
See [current status](docs/MILESTONE_STATUS.md) and [next validation gates](docs/NEXT_MILESTONE.md).

The integrated source includes durable reconstruction jobs, identity-bound
checkpoints, library/curation/recovery routes and observed OBJ ZIP/STL export.
Source wiring is not proof that every workflow passes on a device. Production
signing, installed-app complete sessions, model-studio qualification and physical scan quality
remain open; there is no production-ready or commercial-scanner-equivalent claim.

Capture/import photographs, estimate calibrated camera positions, reconstruct
observed surfaces, export photograph-textured GLB models, and inspect them in
the local GPU viewer. No account, analytics, cloud reconstruction, or runtime
internet permission is included. Originals stay in app-private storage until
the project is deleted; export important captures before uninstalling.

## Historical M13–M17 increments

- **M13:** optimized plane-sweep projection kernels with numerical regressions.
- **M14:** bounded eight-camera native depth, fusion and photographic atlas;
  per-reference source selection holds at most four luminance images in memory.
- **M15:** activates that backend in Android, sequentially decoding originals.
  Standard jobs use a maximum 384px depth edge; compact jobs use 256px. Sampling
  automatically respects the 100,000-grid-sample limit without silently dropping
  all but the first three cameras.
- **M16:** incremental camera registration from newly triangulated landmarks.
- **M17:** each reconstruction now owns an opaque native cancellation token. Pause
  signals a shared atomic flag, checked during CPU depth, mesh fusion and UV
  baking, even while Kotlin awaits a JNI call. Distinct workers are isolated;
  interrupted `.partial` results are deleted, not published. M16 input/cache
  fingerprint intentionally stays unchanged because the geometry algorithm is
  identical. See [M17 validation](docs/M17_CANCELLATION.md).
  A later view need not see BOTH original anchor photographs. Conflicting feature
  identities are rejected, unsupported images remain explicitly unregistered,
  and failed candidates are retried only when more cameras/landmarks become
  available. See [M16 design and validation](docs/M16_INCREMENTAL_REGISTRATION.md).

## Current capabilities and limits

The source contains native SfM/PnP, joint sparse bundle adjustment, checkerboard
lens calibration, CPU multi-view stereo, conservative open triangle meshes,
photographic UV atlases, bounded exposure correction, local GLES3 viewing,
revision-safe reopen/export and original-photo capture bundles.

Supported reconstruction input is **two to twelve photographs (2–12)**; dense processing uses at
most **eight registered views**. Standard depth images are at most **384px**
on their longest edge. Photo-atlas sources are capped at 640px for five to eight
standard views, 768px for smaller standard jobs, or 512px on compact jobs.
The atlas budget is 4.5 million pixels. These are enforced limits, not claimed
physical-phone RSS, battery, speed, accuracy or object-completeness measurements.

Geometry is unscaled, may have holes, disconnected/overlapping patches and visible
seams, and is not guaranteed manifold or watertight. Integrated sparse and
depth/mesh/texture JNI paths use operation-scoped cooperative cancellation.
Persisted jobs and validated checkpoints support bounded restart/reuse; they do
not establish interruption recovery at every possible pipeline boundary.
There is no validated full-resolution GPU reconstruction, production-qualified
Filament studio, production release signing, store-ready AAB or complete-object
performance on physical ARM64 phones.

## Build and tests

Open this root directory in Android Studio. First-time build dependencies need
internet; scanning in the app does not. Gradle 8.13, API 36, NDK 28.2.13676358,
CMake and Java 17 are pinned in the build setup.

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 1
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q
```

See [BUILDING](docs/BUILDING.md). The validation workflows include API-35 Android
instrumentation, native JNI, actual GLES pixel readback, a synthetic
photograph-to-textured-GLB session and real Middlebury stereo benchmarks. These
checks are not all passing on the current candidate. A selected emulator run reached
installed tests, but the full photo-to-model UI journey remains unqualified. The M16 visibility-chain test
uses synthetic analytic features with withheld camera poses; it does not replace
physical captures. Check the workflow for the exact commit before claiming its
Android build or device tests passed. Source contracts are not runtime evidence.

The host test suite also needs the independent format validators
and security scanners. Use the pinned QA-only installer and activation described
in [the host QA setup](docs/BUILDING.md#independent-host-qa-dependencies).
The baseline scientific requirements alone do not supply those tools. Ordinary
debug/release compatibility instrumentation leaves dedicated controller and
physical gates explicitly unrun.

## Engineering records

[Product plan](PROJECT_PLAN.md) · [Status](docs/MILESTONE_STATUS.md) ·
[Next gates](docs/NEXT_MILESTONE.md) · [Privacy](docs/PRIVACY.md) ·
[M14](docs/M14_MULTIVIEW.md) · [M15](docs/M15_ADAPTIVE_ANDROID.md) ·
[M16](docs/M16_INCREMENTAL_REGISTRATION.md)

Native algorithms are in `reconstruction/`, Android in
`app/`, reproducible host harnesses in `tools/`, and measured outputs in `reports/`.
Historical milestone documents remain available. Green builds and isolated
benchmarks are not evidence of flawless performance or commercial readiness.

## Historical M18 — Offline depth checkpoint recovery

Completed per-camera depth maps now survive app cancellation or process
termination in private, checksum-verified, exact-input-identity-bound files.
Restarting the unchanged scan skips accepted depth passes; invalid/stale
checkpoints are recomputed. The final mesh and photo texture are always
regenerated and checked, and no partial output becomes a finished model.
See [M18 checkpoints](docs/M18_DEPTH_CHECKPOINTS.md). The app remains an
engineering prototype, not a fully validated real-world object scanner.

## Historical M19 — Scan evidence and honest quality guidance

The local app now persists a revision-bound `scanDiagnostics` object alongside
its actual sparse/dense/mesh/UV artifact. A distinct UI card reports how many
photographs registered, the fraction of sampled depth pixels confirmed by a
second view, observed triangle counts and the share of triangles supported by
actual photographs. Recapture guidance is derived from specific indicators:
low overlap, low parallax, reprojection disagreement, insufficient reciprocal
depth, missing triangles or low photographic face coverage.

**These are diagnostics, not a scan-quality score or whole-object percentage.**
No automatic metric can establish unseen rear surfaces, ground-truth physical
accuracy or watertightness from the current photographs. A user-directed JSON
export includes the diagnostic report and the complete revision-checked scan
metadata; it does not upload data or require an account. See
[docs/M19_SCAN_EVIDENCE.md](docs/M19_SCAN_EVIDENCE.md).
