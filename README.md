# RealityForge — offline Android photo-to-3D scanner

**Engineering preview based on M19, version 0.19.0-m19, with subsequent
cloud integration. Not a completed consumer product or a guarantee of
high-quality complete-object scanning.**

Current integration and commit-specific checks are tracked in the
[cloud validation ledger](docs/development/CLOUD_COMPLETION_STATUS.md).
The integrated source includes service-owned durable reconstruction jobs,
identity-bound checkpoints, library/curation/recovery routes and observed
OBJ ZIP/STL export. Source wiring is not proof that every workflow passes on a
device; complete-session, studio and physical-quality qualification remain open.

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
  all but the first three cameras. The unfinished source publication was recovered
  from its checksum-verified payload and the temporary transport files removed.
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
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 1
ctest --test-dir build/host --output-on-failure
python -m pytest tests -q
```

See [BUILDING](docs/BUILDING.md). CI also runs API-35 Android instrumentation,
native JNI, actual GLES pixel readback, a synthetic photograph-to-textured-GLB
session and real Middlebury stereo benchmarks. The new M16 visibility-chain test
uses synthetic analytic features with withheld camera poses; it does not replace
physical captures. Check the workflow for the exact commit before claiming its
Android build or device tests passed. Source contracts are not runtime evidence.

The integrated 081–100 host suite also needs the independent format validators
and security scanners. Use the pinned QA-only installer and activation described
in [the campaign QA runbook](docs/BUILDING.md#integrated-campaign-qa).
The baseline scientific requirements alone do not supply those tools. Ordinary
debug/release compatibility instrumentation leaves dedicated controller and
physical gates explicitly unrun.

## Evidence and campaign workflow

The immutable audited baseline is `fdd16fce4aa7b1b7d365c0f43e89964d3028bf42`;
[Actions run38488820626](https://github.com/cheshmakzanoon-ops/3d-scanner/actions/runs/38488820626)
records11 CTest targets,117 pytest checks and32 API35 emulator tests. These counts
are historical evidence for that SHA, not a result for later agent branches.
The campaign base is `ad3029193f304c70931b94ea7a0df90b69d84b07`. A branch receipt
under `docs/agents/status/` describes only its own verified source and gates.
Agent100 freezes integrated candidate source/artifact hashes before acceptance.
Missing physical complete-object, resource, signing and license gates stay open.
Do not treat a remote branch or successful build as integrated app behavior.

Follow [AGENTS](AGENTS.md), the exact numbered role and the shared mechanical
source-manifest protocol in the execution plan. Stage owned source/tests/receipt
first; regenerate the entire manifest from staged Git blobs, stage it and verify.
Never merge another role into a worker branch;100 handles integration and conflicts.

## Engineering records

[Product plan](PROJECT_PLAN.md) · [Status](docs/MILESTONE_STATUS.md) ·
[Next gates](docs/NEXT_MILESTONE.md) · [Privacy](docs/PRIVACY.md) ·
[M14](docs/M14_MULTIVIEW.md) · [M15](docs/M15_ADAPTIVE_ANDROID.md) ·
[M16](docs/M16_INCREMENTAL_REGISTRATION.md)

For numbered parallel assignments, follow the
[100-agent production execution plan](PRODUCTION_100_AGENT_EXECUTION_PLAN.md):
each agent uses its assigned branch and Agent 100 coordinates integration into `main`.
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
