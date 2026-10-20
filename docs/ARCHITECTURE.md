# Current M19 architecture

This section describes the campaign-base source, version0.19.0-m19. Historical
M01–M07 descriptions below are retained as dated design records and do not define
current capability. Future agent modules are capabilities only after Agent100
integrates their dependencies and runtime call sites and verifies the candidate.

Picker/CameraX original bytes → private immutable originals/Room revision →
1600px upright analysis → OpenCV SIFT/reciprocal matching → incremental registered
world-to-camera poses and multiview tracks → fixed-intrinsic Schur bundle
adjustment → optional calibrated CPU depth → reciprocal consistency/fusion →
observed open indexed triangles → photographic atlas → GLES3 inspection/export.
Sparse registration is bounded to12 photos, depth to8 registered views and100,000
samples; compact/standard depth edge256/384. Atlas source edges512/640/768 depend
on profile/view count and total atlas pixels are bounded to4,500,000. See actual
`DenseWorkload.kt`; these are admission bounds, not phone performance evidence.

Representations stay distinct: sparse_points, dense_points, observed open triangle
surface and photographic triangle surface. GLB uses right-handed y-up `(x,-y,-z)`
from internal x-right/y-down/z-forward coordinates; PLY baseline keeps internal
axes. Scale is arbitrary unless a validated measurement supplies provenance.
Hole-free, manifold, seamless, metric or complete-object properties are not implied.
Current native GLB/PLY/PNG/JSON/original-photo outputs exist; proposed OBJ/STL and
canonical exporters need their actual integrated owner call paths and receipts.

M17 operation handles share isolated atomic cancellation flags across CPU
depth/mesh/photographic JNI calls. Sparse work still checks at Kotlin stage
boundaries. M18 checksum/CRC/input-bound per-reference depth checkpoints reuse only
accepted complete maps; mesh and texture publication is recomputed/validated.
This is not complete-session or every-stage durable background resumption.
Activity onStop still pauses the foreground coroutine in this baseline. M19
revision-bound diagnostics report actual registration, sampled/consistent depth,
triangle and photographic support counts without a whole-object quality score.

Originals, feature caches, depth checkpoints, result files and Room publication
have separate identities/lifetimes. Result export/reopen checks project revision;
partial files must never become current. DB/filesystem crash-boundary hardening,
durable job/service execution, large-scene scheduling and physical performance
remain owner integration/qualification work. Baseline JNI allocation/exception
containment needs081/082/003 verification; older statements of universal exception
safety below are historical interface intent, not proof of every current entry.

Current command and evidence authority: [BUILDING](BUILDING.md),
[status](MILESTONE_STATUS.md), [next gates](NEXT_MILESTONE.md) and
[execution plan](../PRODUCTION_100_AGENT_EXECUTION_PLAN.md).

---

## Historical M01–M07 architecture record

### M02 architecture and representations

### Historical M02 execution path

Document picker or CameraX JPEG → private byte-preserving photo import → Room revision →
upright analysis bitmap → native OpenCV SIFT → reciprocal L2 ratio matching → JNI →
C++ robust essential matrix → world-to-camera pose → triangulated sparse points →
JSON diagnostics + GLB POINTS/PLY → current-result pointer in Room.

OpenCV's Android bindings dispatch feature extraction to its bundled native implementation;
this is not a Kotlin SIFT implementation. The portable C++ kernel contains the two-view
geometry, statistics and point exports. Python calls its C ABI for tests; Android calls
its JNI bridge. The host tests therefore exercise the actual geometry code, not a separate
Python replacement.

## Native initializer

Coordinates must be normalized by an appropriate camera calibration. Lens distortion is
not corrected by the M01 Android adapter, so its outputs are diagnostic. Hartley-normalized
eight-point fitting, a small symmetric Jacobi eigensolver, constrained essential matrices,
seeded RANSAC, consensus refitting and pose decomposition recover a two-camera model.
Triangulation rejects points behind either camera, near-infinite geometry, poor reprojection
and insufficient parallax. Degenerate samples are discarded; no claim of exhaustive
real-world degeneracy detection is made.

The simple fixed-size numeric kernel is a tested initializer, not a substitute for the
planned Ceres/Eigen multi-camera optimization stack. Bundle adjustment, PnP registration,
track building and loop closure have not been implemented.

## Coordinate conventions

Internal and PLY coordinates: x right, y down, z forward; the first camera is [I|0]. The
second pose is a row-major world-to-camera rotation plus unit-length translation. Baseline
one is arbitrary, not one meter. glTF output uses (x,-y,-z) to express the geometry in a
right-handed y-up frame. GLB declares primitive mode 0 (POINTS) and has no triangle indices.
Point colors are individual source-image samples, not a UV atlas or photographic texture.

## Native interfaces

`rf_reconstruct_json`: C ABI with N×7 double rows `(x1,y1,x2,y2,r,g,b)` and an explicit
normalized threshold. Returns allocated UTF-8 JSON; free it exactly once with `rf_free`.
Failures become an `error` field at this boundary. No exception crosses the C boundary.
JNI validates array shape/count and copies data into owned native storage.

`NativeCore.quality` accepts a tightly packed grayscale buffer and returns mean intensity,
standard deviation, Laplacian variance, dark fraction and bright fraction. These are raw
measurements, not calibrated universal quality scores.

`NativeCore.exportPoints` accepts N×6 position/color rows. It validates bounded, finite
geometry before writing GLB or PLY. The Android caller writes a temporary file, flushes it,
and publishes it by same-directory rename.

## Persistent artifacts

`files/projects/<UUID>/originals/` contains immutable imported copies. External display
names are not used as paths. A SHA-256 index detects byte-identical duplicates. Room
transactions couple photo insertion to revision invalidation.

Feature files are keyed by original hash and extractor/cache version. Cache decoding checks
magic, dimensions, count, length and finite values. Analysis is bounded to 1600 pixels on
the long side and 3500 SIFT features. At most two descriptor frames are loaded for matching.

Result artifacts use `sparse-r<revision>-<settings-fingerprint-prefix>`. Their settings hash
includes photo hashes, extractor/engine version and fallback focal length. Room publishes
the artifact key only after all exports are written and the photo revision still matches.
Beginning a rerun clears the current-result pointer; failed reruns cannot leave a stale
result falsely marked current. Old artifact files may remain as recoverable local history.
A future storage manager must account for these old versions rather than accumulating them
without limit.

## Deliberate M01 limits

Up to the first 12 photos, at most 24 local-neighbor comparisons, and one selected pair.
No whole-object completeness, global scene graph, geometric scale, dense depth or texture
reconstruction is implied by the sparse preview. The point viewer uses Compose Canvas for
diagnostics; Filament is reserved for the later textured-model studio.

## Threading and interruption

Imports run off the UI thread. Matching/reconstruction run in a coroutine with cancellation
checks between stages/pairs; the bounded native pair solve is not interruptible internally.
Leaving the activity cancels the current job. Feature caches survive and the project reports
interruption. This is not mid-RANSAC resume or an unrestricted Android background worker.
After process death, the next app session marks any still-PROCESSING project INTERRUPTED.
Production long-running processing requires a separate, platform-validated lifecycle design.

## M02 global coordinate-frame camera registration

The anchor camera pair remains the only source of triangulated sparse points. Each retained
`matchIndex` refers to a known reciprocal SIFT feature pair and its two original keypoint
indices. New photos are compared independently to both anchor frames; only observations
whose keypoint IDs form a consistent A→C and B→C cycle are used. C++ calibrated PnP
uses normalized, undistorted *in principle* camera coordinates supplied by the adapter,
robust DLT-RANSAC and 6-DOF camera-frame pose refinement. It rejects insufficient
consensus and degenerate geometry and provides per-camera inlier indices and reprojection
error. These camera poses use the same world frame/scale as the anchor pair; independent
pair point clouds are never simply concatenated.

**Current limit:** Android's estimated pinhole focal length and missing lens-distortion
correction remain constraints. Extra poses do not increase point density, and there is no
global BA, newly triangulated three-view points, loop closure or dense surface.


## Historical M06 architectural addendum

Older descriptions above are historical M01/M02 stages. Current M06 pipeline
adds the M03 conflict-aware multiview feature graph, M04 optional
checkerboard-derived Brown–Conrady undistorted rays, M05 real stereo benchmark
and capture archive, then invokes joint sparse BA after successful M03
point/camera registration. The first two anchor poses remain fixed to retain
coordinate gauge and unit baseline. `rf_bundle_adjust_json` receives 12-column
world-to-camera pose matrices, 7-column track/XYZ/RGB seeds and 7-column
track/camera/normalized-ray/RGB observations. The solver assembles shared
point/camera normal equations, eliminates points with 3×3 Schur complements,
solves a small coupled camera system, back-substitutes point increments and
accepts a step only when the complete robust cost improves. Failure preserves
M03 geometry. Intrinsics are not optimized. The output schema 6 still denotes
sparse point primitives, not a mesh. Cache fingerprints use `m06-joint-ba-1`.

## M07 bounded CPU stereo reference

`reconstruction/{include/realityforge/dense.hpp,src/dense.cpp}` consumes 2–4
calibrated **rectified** RGBA views, each with camera-to-world gauge shared
with sparse registration (stored extrinsics are world-to-camera), intrinsics,
and a validity mask. For each inverse-depth hypothesis, a reference patch is
projected to another camera with fronto-parallel local geometry; ZNCC and
non-adjacent-peak uniqueness reject textureless and ambiguous matches. After
individual depth maps are built, depth is retained only when another camera
reports compatible camera-z and reverse reprojection within bounded tolerances.
This produces confidence-scored `dense_points`, not a watertight mesh.

`DensePreview.kt` intentionally limits Android input images to 256-edge and
three views; it rectifies with stored Brown–Conrady calibration, derives depth
bounds from sparse points, uses the native C ABI through JNI and rejects weak
results without replacing the sparse stage. Separate dense GLB/PLY outputs
retain source-observed point colors. All computations and outputs stay local.
