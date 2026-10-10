# Next milestone: real-device validation and multi-camera expansion

The agreed final product is unchanged. Continue from these files; do not replace the engine
with canned geometry or a web/cloud service.

## Gate A — compile and run what exists

1. Resolve the pinned Android build dependencies on a network-enabled build machine.
2. Run debug APK/test APK compilation, JVM tests and lint; fix actual compiler/API issues.
3. Run native/JNI/OpenCV/Room instrumentation on x86_64 and a physical arm64 Android device.
4. Validate merged permissions and all native/ZIP 16 KB alignment, not only the first-party .so.
5. Exercise create/import/capture/cancel/process-death/export/delete in airplane mode with
   local documents. Check EXIF orientations 1–8, corrupt images and exhausted storage.
6. Record device model/OS, input hashes, actual outcomes and logs. Do not mark these verified
   based on the existing desktop tests.

## Gate B — real-photo sparse registration

Acquire authorized real photo sets of a textured rock and matte object, plus a calibration
pattern and reliable reference measurement where available. Preserve originals and provenance.
Establish a known-good desktop reference pipeline; the synthetic fixture is not its substitute.

Implement a versioned camera model with exact resolution/crop/orientation transforms,
normalized/undistorted observations and calibration provenance. Add turntable/background
masking before promising object-only results. Capture physical-lens/exposure/focus metadata
where supported; avoid silently treating different lenses as one camera.

Create observation tracks across verified image pairs with conflict detection. Add robust
PnP registration and new-point triangulation. Revisit loop closure and disconnected components.
Keep a reproducible global camera/point representation rather than a collection of unrelated
pair clouds. Inserting a third camera must be a measured reconstruction, not concatenated
points with incompatible coordinate systems.

## Gate C — production sparse optimization

Integrate and audit a Ceres/Eigen-based bundle-adjustment implementation. Fix the gauge and
manage scale explicitly. Optimize poses and supported camera parameters under robust losses;
validate analytic/autodiff derivatives and reject implausible calibration drift. Compare
pre/post reprojection distributions, ground-truth camera errors and surface distortion.
Persist the refined cameras and tracks as the inputs to dense stereo.

## Following milestone

Only after real multi-camera geometry passes its benchmark gate: implement a CPU dense MVS
reference, then measured Vulkan acceleration, confidence-aware fusion, surface meshing and
photographic UV/texture baking. Continue all 20 phases in PROJECT_PLAN.md. No present sparse
point count should be presented as progress in triangles, texture quality or complete models.

## Completed after M01

- GitHub CI now compiles, lints and validates the M01 debug APK.
- M02 adds a separately tested native PnP solver and Kotlin cycle-consistent extra-view registration.
- Synthetic three-photo pose test agrees with known calibration.

## Next implementation gate (M03)

1. Run Android emulator/physical-device instrumentation for M02, collect actual logs, fix runtime issues.
2. Capture a real textured-object three-plus-view dataset with verified calibration and masks.
3. Build a global observation-track graph rather than anchor-only cycles, introduce point triangulation from newly registered views with parallax/cheirality checks.
4. Integrate robust sparse bundle adjustment with gauge-fixed camera/point representation and genuine before/after error comparisons.
5. Persist per-camera registration states and checkpoint resumption, before dense MVS.

## After M03: next gate M04 — real captures and global calibration

M03 adds registered-camera triangulation, bounded point/non-anchor camera refinement and conflict-checked track construction. Its synthetic 3-view test does not substitute for real data. Next: collect licensed, calibrated physical-object photo sets; run actual arm64 and emulator instrumentation and thermal profiling; validate lens distortion and true calibration; integrate sparse global BA (Ceres/Eigen or equally validated sparse optimizer with gauge-fixing and reliable derivatives); then build CPU dense multi-view stereo baseline before Vulkan and meshing.

## After M04: next gate M05

M04 adds optional checkerboard-fitted Brown–Conrady intrinsics and corrected undistortion across the complete sparse feature graph, plus the artifact-lookup repair. Next: **physical-device calibration and real-object benchmarks**. Capture/label at least two rigid matte objects on an arm64 phone; print/capture the calibration board using a locked lens; compare with a known-good desktop SfM result; run Android instrumentation and airplane-mode recovery. Then integrate constrained global sparse bundle adjustment (including carefully bounded intrinsics refinement), followed by a CPU dense multi-view stereo baseline only after measured real data is stable. Calibration-fit RMS is not object-geometry error.

## After M05: real multiview and production BA (M06)

M05 introduces reproducible **real-image** two-view error measurement, local real capture ZIPs and emulator instrumentation. The next gate is a physical Android device with printed 9×6-inner-corner checkerboard and two *real* textured matte objects photographed from 12–40 viewpoints; capture calibration fit, merged APK permissions, CPU/GPU temperatures, RAM and Android logs. Export archives and compare independent COLMAP SfM poses to RealityForge. Only then optimize global camera/point bundle adjustment with fixed gauge and bounded intrinsics, and implement a dense CPU MVS baseline with measurable depth consistency. Do not call M05's real two-view motorcycle benchmark a fully validated 360-degree object scan.

## After M06: M07 — calibrated physical sequences and CPU dense stereo

M06 adds a shared C++/JNI Schur-complement solver for jointly optimizing
registered camera extrinsics and sparse points (fixed two-view gauge) with
Huber loss. This is not Ceres and does not optimize focal/distortion. Next
collect real 3+ view matte-object photographs on a physical arm64 phone with
measured calibration/ground truth and compare independent SfM outputs. Run
instrumentation and thermal tests on that hardware. Then implement a CPU
multi-view depth-map baseline with explicit visibility, depth confidence and
forward-backward geometry checks. Keep dense and sparse benchmarks separate;
do not begin textured mesh advertising before this succeeds.

## After M07 — M08: real capture gate and surface fusion

The app now computes bounded, low-resolution **dense point clouds** after
joint sparse BA, including reciprocal depth checks. This is not a dense
surface mesh. First run real arm64 captures of 3+ viewpoints on two authorized
matte objects using a verified checkerboard profile; compare point depths to
independent surface truth, measure thermal/battery/memory behavior, and evaluate
failure cases like shiny or textureless materials. Then implement confidence-
weighted spatial fusion and topologically safe meshing (not arbitrary hole-fill),
validate against ground truth and only afterward begin multi-photo UV/texture
baking. Do not claim high-quality textures or a watertight mesh in M07.

## After M08 — M09: photographed material projection and stronger mesh fusion

M08 turns independently verified CPU stereo depth samples into **actual indexed,
open triangles** with conservative neighborhood/depth/edge gating, confidence
weighted cross-view vertex/color fusion, GLB/PLY face export and an orbitable
mesh preview. Its real-photo accuracy data is a **rectified two-view stereo
benchmark**, not a measured physical 360-degree object scan.

**First:** capture 12–40 authorized photographs of at least two textured matte
objects on a physical Android phone; use the printed checkerboard, fix lens,
quantify registered-camera errors, use external calibrated reference geometry,
record memory, CPU/thermal measurements and actual object-completeness metrics.
Audit all mesh boundaries, disconnected components, self-intersections,
back-facing patches and duplicate overlapping faces. No indiscriminate hole
filling or waterproof/print-ready claims. **Then:** integrate carefully licensed
UV unwrapping and view-ranked high-resolution photographic texture baking,
mask/outlier/color/exposure correction, seam handling and real-file GLB UV
exports, all offline and with bounded memory. Benchmark geometry and appearance
separately. Upgrade to genuinely denser coarse-to-fine MVS/Vulkan only with
measured mobile workloads and quality regressions.
