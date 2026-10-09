# Next milestone: verified Android execution, then multi-camera sparse geometry

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
