# M04 — Fully offline camera calibration (engineering milestone)

The scanner now supports a **per-project** Brown–Conrady calibrated camera model. A calibration is obtained locally from **10–40 photographs of a printed checkerboard with 9 columns × 6 rows of *interior corners*** (a pattern of 10 × 7 squares).

## Capture protocol

1. Print a rigid, FLAT checkerboard (10 by 7 squares). Avoid warped paper. Square size must be uniform; its exact physical size is not needed to estimate intrinsics.
2. Lock to the exact camera/lens and zoom to use when scanning. Keep image aspect, sensor crop and EXIF orientation consistent. Disable beauty/warp filters and digital stabilization. Capture 15–25 sharp views.
3. Show every interior corner in every selected photo. Move the board near the edges, vary tilt, distance and perspective. Do not just take many copies of a front-on board.
4. Create/open a RealityForge project. Tap **Calibrate this lens**, and select 10–40 on-device checkerboard photos. App detects corners, checks viewpoint diversity, solves five Brown–Conrady distortion parameters, and saves a local `camera-calibration-v1.json` profile.
5. Capture or import object photos using the **same camera, zoom, crop and orientation**. A different aspect ratio or inconsistent available EXIF focal length **rejects** the profile rather than silently treating it as compatible. If no EXIF lens data is present, you must verify manually that the optics match.
6. Reconstruct normally. Feature coordinates use per-image camera intrinsics and iterative distortion inversion for two-view matching, PnP, epipolar filtering and multi-view triangulation.

All computations take place on-device using the bundled OpenCV Java bindings; **the app does not require `INTERNET` permission**. Imported calibration originals are read through Android's document provider, used temporarily and not copied into the scan dataset. The app retains only fitted parameters and residual statistics. If a document provider is cloud-backed, Android's provider itself may need connectivity to supply the photo; choose local photos to remain completely offline.

## Quality & scope limits

- The profile is stored in EXIF-upright analysis pixel coordinates; a uniform downscale with identical image aspect is supported.
- The profile fits `fx, fy, cx, cy, k1, k2, p1, p2, k3`. Profile schema and hash are versioned to invalidate old sparse results. It does NOT estimate absolute model scale.
- A low reprojection RMS is necessary but NOT sufficient to prove correct calibration. Very blurry boards, rolling shutter, autofocus changes, changed lens, cropped photos, or autofocus breathing can still corrupt geometry.
- The current M04 changes **do not** add global Ceres bundle adjustment, real-scene benchmark ground truth, dense MVS, a triangle mesh, or phototexturing. M03 remains the underlying sparse 3D reconstruction engine.
- Calibration failure is explicit; the previous pinhole estimate is retained only when the project has no calibration file, not when an incompatible calibration is encountered.

## Validation

- A pure JVM Brown–Conrady round-trip suite checks analytic distortion inversion and resolution scaling.
- Synthetic checkerboard tests exercise OpenCV pattern recognition and calibration; they are not evidence of real handset accuracy.
- Android CI compiles the Java/Kotlin/OpenCV integration, runs JVM tests/lint and validates APK permission structure. Actual camera calibration must still be executed on physical hardware with a real board.

## Next engineering gate

Collect real paired checkerboard/scanning sequences from multiple supported Android phones; benchmark measured reprojection and reconstructed geometry, validate DNG/EXIF orientation and lens changes, then add production global sparse bundle adjustment with a strictly controlled gauge before beginning dense stereo reconstruction.
