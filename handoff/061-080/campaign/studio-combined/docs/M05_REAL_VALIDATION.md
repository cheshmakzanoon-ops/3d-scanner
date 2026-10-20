# M05 — Real-image reconstruction benchmarks and Android offline validation

## What is now testable

1. **Measured physical scene, real images:** `tools/benchmark_middlebury.py` loads the **Middlebury 2014 Motorcycle** rectified stereo pair through `scikit-image` (without committing or rehosting the original images). It extracts real SIFT matches, runs the **same portable C++ two-view reconstruction library** used by Android, and checks against independently acquired ground-truth disparity and the published camera intrinsics/baseline.
2. **Repeatable user capture audit:** The Android app explicitly exports a ZIP with untouched local originals, hashes, metadata, optional per-project checkerboard profile, and `session.json`. `tools/capture_audit.py` checks byte identity, image decoding, exposure/texture indicators, adjacent-view overlap, aspect and focal identity. It does **not** upload photos and must not be confused with 3D geometry validation.
3. **Android instrumentation CI:** Repository CI now boots an API-35 x86_64 emulator on push and executes JNI, Room, OpenCV and offline capture ZIP tests. This is **emulator validation, not a physical camera test**. Physical arm64 scanning/calibration and thermal/memory profiling are still required.
4. **Better feature matching:** Reciprocal-SIFT ratio reduced from .75 to .67 in both Android and host geometry harnesses. Tested on the synthetic three-view set *and* a separate real stereo pair; using stricter correspondences can avoid unstable pose estimation. It does not guarantee every scene improves.

## Real benchmark interpretation

The benchmark pair depicts a **real physical scene**, not a simulated image, but it is only **two rectified cameras** with known calibration. It does not demonstrate a handheld 360-degree object scan, full multi-camera global bundle adjustment, high-resolution surfaces, or texture atlases.

The input data are provided by the scikit-image package from the original Middlebury benchmark; **do not commit the image bytes**. The reference has focal length 994.978 px, left principal x 311.193 px, principal-point difference 31.086 px, and baseline 193.001 mm. Per-scene scaling from the known baseline is used *only in the benchmark*; the Android scanner still has an undetermined metric scale.

After stricter matching, the locally executed run on the authoring host observed approximately:

| Metric | Observation | Notes |
|---|---:|---|
| Reciprocal matched feature pairs | 805 | Actual photographs, 3500 SIFT feature cap per image |
| Sparse points accepted by native code | 795 | Not triangles or dense points |
| Ground-truth valid points | 811 | Depends on invalid/occluded disparity pixels |
| Median matching disparity absolute error | ~0.98 px | Independent reference, sampled at the *right* image's keypoints |
| 90th-percentile disparity absolute error | ~13.47 px | Significant remaining outliers |
| Median relative reference-scaled depth error | ~1.8% | Known external baseline; not mobile scale accuracy |

**These are not claims of production scanner accuracy.** The benchmark's quality gates are intentionally conservative and report the error distribution, not only one flattering number. `reports/m05-middlebury-real.json` records exact numbers, input SHA-256 hashes and runtime for the executed host environment. CI reruns the benchmark from the independent image source and fails if it substantially regresses.

References: [Middlebury datasets](https://vision.middlebury.edu/stereo/data/scenes2014/), [scikit-image stereo_motorcycle](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.stereo_motorcycle), [Middlebury dataset reuse/citation](https://vision.middlebury.edu/stereo/data/).

## How to capture a real object and evaluate it

1. Photograph a small, rigid, **matte textured object** from 12–40 viewpoints using one lens and stable lighting, walking around it and keeping at least 70% overlap. Do not rotate the object against a static background without background masking.
2. Optionally calibrate the lens in the app using the printed checkerboard and the same lens, zoom, aspect and crop.
3. Use **Export original photos + calibration for benchmark** to create a ZIP at a chosen local destination. The ZIP includes private photographs and must be treated as personal data.
4. On a machine with the repository and native/host dependencies installed, run `python tools/capture_audit.py realityforge-capture.zip --output reports/local-capture-audit.json`. Check exact hashes, optical consistency, blur/texture warnings and overlap results.
5. Compare a selected real-object sequence against a COLMAP sparse reference **with measured camera/scale**, recording registered cameras, median reprojection, completeness and tracking failures. This further comparison requires real photos and is not asserted to have been performed here.

The project deliberately refuses to claim an actual 3D object surface or photorealistic texture at M05. The next production milestone is qualified, constrained global bundle adjustment and reliable real multiview verification before starting dense Multi-View Stereo.
