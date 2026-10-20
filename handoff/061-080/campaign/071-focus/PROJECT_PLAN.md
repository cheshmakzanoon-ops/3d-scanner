# RealityForge — approved direction and implementation gates

Version: 0.1 / 9 October 2026. The design below carries forward the agreed fully offline,
high-fidelity Android photogrammetry plan. The current deliverable is an initial engineering
milestone; a design target is not a completed or validated capability.

## Product contract

The finished app will capture or import multiple photographs of a rigid object, reconstruct
its cameras, recover dense observed surfaces, build and texture a mesh, allow local
inspection, and export useful 3D assets. Runtime capture, reconstruction, viewing and export
must not depend on a server, account or network connection. A user may deliberately choose
an external sharing destination. The app must not send scan data elsewhere on its own.

The target is actual multi-view reconstruction, not a single-image generative model that
invents missing geometry. Unknown or repaired regions must remain identifiable. Scale is
arbitrary unless supported by a known measurement, calibration marker or validated metric
sensor observations. Transparent, reflective, moving and unobserved surfaces cannot be
advertised as reliably reconstructed from ordinary photos.

The intended quality profiles accommodate approximately 30–250+ photos, with adaptive
geometry resolution, normally 2K–4K texture atlases and optional larger outputs after device
validation. Hundreds of thousands to millions of triangles are possible output targets,
not evidence of quality in themselves. These limits remain benchmark-driven.

## Architecture preserved

- Native Android app: Kotlin, Jetpack Compose, CameraX / Camera2, Room and private files.
- Portable C++20 reconstruction engine via NDK/JNI, with host and Android test entry points.
- Native image features and geometric verification; Ceres/Eigen integration for production
  sparse optimization after the initializer and calibration model have passed their gates.
- Dense multi-view stereo using coarse-to-fine depth estimates, confidence, visibility and
  geometric consistency. A CPU reference precedes Vulkan acceleration.
- Confidence-weighted fusion, tested meshing, conservative cleanup, xatlas-style UV
  unwrapping, source-view selection, color correction, seam control and photographic baking.
- Filament for the production textured-model studio; the M01 point viewer is diagnostic only.
- GLB, OBJ, PLY and STL as appropriate to the representation. Sparse POINTS are never
  mislabeled as a triangle surface or an animation-ready asset.
- Optional ARCore tracking/depth assistance must not become a prerequisite for photos-only
  reconstruction. Installation/update requirements must be disclosed separately.

## Full pipeline

1. Copy and preserve originals; normalize metadata and construct analysis pyramids.
2. Evaluate sharpness, exposure, overlap and capture diversity; avoid fabricated coverage.
3. Detect descriptors, choose plausible image neighbors and verify geometric matches.
4. Build consistent feature tracks; initialize a camera pair; register further cameras.
5. Refine camera poses, suitable intrinsics and sparse points using robust bundle adjustment.
6. Estimate per-view dense depth and normals, carrying uncertainty and visibility.
7. Fuse mutually consistent measurements into a point/surface representation.
8. Build and refine a mesh without concealing unobserved areas or closing real openings.
9. Unwrap, select photographs, normalize color, reduce seams and bake textures.
10. Produce LODs and exports, preserving the high-fidelity source model.

## Twenty implementation phases

| Phase | Scope | Evidence required to complete |
|---|---|---|
| 1 | Product specification | Versioned scope, privacy contract, supported cases, metrics and exclusions |
| 2 | Reference datasets | Licensed real photo sets, ground-truth geometry, difficult cases and manifests |
| 3 | Desktop reference | Known-good SfM/MVS comparison outputs and reproducible measurements |
| 4 | Native core and Android bridge | Host tests, actual NDK builds, JNI runtime tests on arm64 and x86_64 |
| 5 | Camera and import | Real-device JPEG/import validation, EXIF/rotation, private storage and interruption tests |
| 6 | Feature extraction | Repeatable multiscale features, caching, retention and device resource measurements |
| 7 | Matching and verification | Correct overlap graph, outlier rejection and degenerate-scene diagnostics |
| 8 | Sparse SfM | Multi-camera registration, triangulation, track consistency and loop closure |
| 9 | Bundle adjustment | Calibrated/refined intrinsics, stable gauge, robust optimization and convergence reports |
| 10 | Dense MVS | Real depth maps with quality/performance comparisons to the CPU reference |
| 11 | Dense fusion | Confidence-weighted point clouds/surface observations; thin-surface and occlusion tests |
| 12 | Meshing | Correct winding, topology checks, preserved boundaries and conservative repair |
| 13 | Photographic texturing | UVs, source scoring, visibility, color adjustment, seam tests and original-photo baking |
| 14 | Optimization | Detail-preserving decimation, LODs, rebaking and measured error budgets |
| 15 | Export | GLB/OBJ/PLY/STL imports in target tools with correct units and material references |
| 16 | 3D studio | Filament lifecycle/resource tests, responsive model inspection, crop/orient/scale |
| 17 | Guided capture | Useful blur/overlap/coverage feedback shown to improve real capture success |
| 18 | Resource engineering | Peak RAM/storage, Vulkan compatibility, sustained thermals and safe scheduling |
| 19 | Reliability | Airplane-mode end-to-end tests, process death, storage exhaustion, corrupt input and recovery |
| 20 | Release | Verified APK/AAB, current distribution requirements, privacy documentation and external testing |

## Initial delivery strategy

The first executable slice proves image correspondences → native camera geometry → sparse
3D output. It deliberately does not claim a completed dense scanner. Source work on the app
shell and photo pipeline may proceed alongside the host engine; Android-related gates remain
open until their code is actually compiled and executed. A synthetic fixture is useful for
numerical regression but does not complete the real-dataset or high-quality-output gates.

## Quality validation

Evaluate geometric accuracy, completeness, camera registration, texture alignment, visible
seams, export correctness and operational reliability independently. Preserve per-scene
failures instead of hiding them in a favorable average. The future controlled-dataset
aspirations include roughly 95% camera registration, strong observable-surface completeness
and scale-aligned surface errors under approximately 1% of object bounding-box diagonal.
These targets must be adjusted or qualified by measured scene/device performance.

The benchmark corpus must include textured stone, shoes, figurines, wood, flat objects,
thin components and deliberately adverse glossy/transparent/low-texture subjects. Difficult
cases should fail honestly or request corrective capture rather than output deceptive
plausible shapes. No fake model, canned success status or arbitrary percentage is acceptable.

## Resource and persistence rules

Never decode an entire photo collection at full resolution at once. Stream originals,
store multiresolution caches, limit active neighbors, tile dense processing and spatially
partition large fusion/meshing tasks. Adapt concurrency to measured device resources and
thermal behavior. Preserve checkpoints at meaningful algorithm boundaries.

Inputs, settings and engine/cache versions identify an output. Mutating an input invalidates
downstream artifacts. Publish a completed result only after its associated artifacts are
safely written and it still corresponds to the current project revision. Cancelling or
killing a process must not publish a partial scan as complete. A change to export density
should not rerun camera alignment.

## UI and product rules

Library → capture/import → photo review → measured processing stages → 3D studio → export.
Retain originals and allow useful reruns. Show unsupported conditions and calibration
assumptions. No mandatory account, forced photo upload, fake quality bar or hidden server
fallback. A privacy-preserving default must include explicit consideration of OS backup
and user-selected document-provider destinations.

## Licensing and dependencies

Audit direct/transitive libraries, native redistributables and model licenses before release.
No OpenMVS/AGPL source is included in M01. Planned libraries are not automatically approved
for distribution. Preserve notices and document versions. First-party source licensing is
for the project owner to select; this package does not publish or relicense the project.
