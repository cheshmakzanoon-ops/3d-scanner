# RealityForge — offline Android photo-to-3D scanner

**Engineering preview based on M19, version 0.19.0-m19, with subsequent
correctness and integration work. Not a completed consumer product or a guarantee of
high-quality complete-object scanning.**

## Current validation — 25 October 2026

**A synthetic photo-to-model controller journey passes; reliable installed-device
scanning and high-quality complete-object reconstruction are not yet qualified.**
Results below belong to the named source revisions, not automatically to later commits.

| Area | Verified result / remaining limit |
| --- | --- |
| Three-photo full-controller HOST test (`5beb2d3`) | **Passed:** three fixed synthetic relief photographs → actual reconstruction controller → dense points, mesh and photographic texture → nine published artifacts → three validated export formats and Room database reopen. All three cameras registered; 904 sparse points, 22,259 textured triangles and a 1548 × 1164 atlas. |
| Test scope | `SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY`: modeled HOST resources, not physical capture, installed GLES/SAF interaction or release qualification. The eight-photo low-resolution fixture remains unsuccessful. |
| Real licensed stereo full-controller HOST test (`68ef9b7`) | **Failed at `MATCHES`; no model produced.** Read-only replay of persisted matches returned native `WEAK_BASELINE`; this is not a diagnosis of the physical camera baseline. |
| Android build (`68ef9b7`) | Debug app/test APK builds, lint, 864 unit tests and 18 HOST JVM tests passed (882 tests total). These do not establish a passing installed photo-to-model journey. |
| Installed emulator tests (`7ff6395`) | The synthetic UI journey failed in DocumentsUI selection **before import/reconstruction**. The separate optional Filament rendering/lifecycle test passed. The selector repair in `c3f26f3` still needs a completed installed rerun. |
| Remaining CI gates (`68ef9b7`) | **Failed:** GCC and Clang sanitizer profiles exceeded their 600-second pytest limit; ordinary device KVM preflight also failed. Full-candidate CI is not green. Passed release builds and selected tests do not replace these gates. |
| Exact planar ownership QA (`813f312`) | Dedicated ASan/UBSan/LSan run: 1 native CTest and 83 differential cases passed; 137 release cases passed. **OFF by default**, native QA only—not production integration or mobile qualification. |
| Mesh quality (`5beb2d3`) | The synthetic result still has 113 components, 9,542 boundary edges and **653 nonmanifold edges**. Intersection analysis is budget-incomplete. The supported-diagonal fix does not establish watertight, overlap-free or complete-object quality. |

Source `e493872` adds [persisted camera-failure guidance](docs/reconstruction/ANCHOR_FAILURE_GUIDANCE.md)
for uniformly allowlisted weak/ambiguous pair rejections and resource-backed
recapture advice, without weakening acceptance gates. Its
[Android build and checks](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40179213109)
passed with **888 tests** (870 unit + 18 HOST JVM; no failures, errors or skips);
release profiles and benchmark compilation also passed. Device checks failed and
sanitizer jobs remain running at this checkpoint: full CI is not green, and
installed guidance is not qualified. The latest shared APK is still the `707a10e`
engineering preview; it does not contain this new runtime guidance.

Recent source fixes correct inverse-depth interpolation on projective planes,
choose a consistent supported diagonal for identical observed full quads, and
stop idle redraws in the optional Filament backend. The default GLES viewer was
already demand-driven. These are scoped correctness improvements, not proof of
physical scan accuracy, general overlap removal or measured battery savings.
A [conservative EXIF focal prior](docs/reconstruction/EXIF_FOCAL_PLANE_PRIOR.md)
now handles valid isotropic focal-plane metadata when a direct 35mm tag is absent;
it remains derived, not measured lens calibration, and declines inconsistent inputs.

Evidence: [full-controller HOST run](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/40162711185),
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
