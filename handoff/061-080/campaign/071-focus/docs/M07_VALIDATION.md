# M07: Bounded CPU stereo depth + independently measured real-photo baseline

**Date:** 2026-10-10 · **Status:** implementation and host regression verified; Android device CI must be evaluated on the M07 commit.

## Exactly what is implemented

1. Native C++20 CPU **inverse-depth plane sweep** on calibrated, rectified RGB(A) views.
   Each candidate hypothesizes a locally fronto-parallel patch and projects it
   between distinct registered camera poses. Zero-mean normalized cross correlation
   reduces brightness-offset effects without claiming photometric calibration.
2. Rejects featureless windows, ambiguous multiple depth minima, bad projection,
   behind-camera points, outside-image/invalid mask regions and insufficient stereo baseline.
3. Independently computes bounded depth maps for each reference camera. Depth
   estimates survive only when they agree under a **reciprocal camera-z depth**
   comparison (and reverse reprojection) in another photograph. Confidence is exposed.
4. A bounded voxel filter merges duplicates in the registered arbitrary-scale frame.
   Limits: 2–4 views, maximum image width/height 640, <=320k pixels per image,
   12–128 depth hypotheses, stride 2–8, <=50k output points. The Android
   preview deliberately uses **up to three 256-edge images**, 56 hypotheses,
   stride three, and 20k-point native hard cap.
5. Kotlin rectifies imported images using their selected project Brown-Conrady
   profile or visibly estimated pinhole fallback. The stage uses M06 camera
   poses and quantile-bounded sparse-depth priors; a failure is reported without
   replacing the already accepted sparse reconstruction.
6. Separate point-only GLB and PLY exports and an interactive dense-point
   visualization. **No triangles, mesh topology, UV atlas, textures or physical
   dimensions are reconstructed.** The colors are sampled from photographs
   at observed points, not a continuous baked surface material.
7. C ABI and Android JNI share the exact portable algorithm. The entire runtime
   works offline. Source inputs retain SHA/revision-based invalidation.

## Measured test evidence

### Synthetic rendering (three calibrated world cameras)

The native tests render an actual textured planar scene at known depth 5.0
and varied camera poses with distinct visibility. They recover **4,669
reciprocal-consistent points** and a **median depth 4.96855** in scene-relative
units. Low-texture images produce 0 dense points, and tests reject coincident
camera centers, invalid buffers and invalid depth settings. Occlusion handling
and determinism are covered. These images are synthetic, not physical captures.

### REAL Middlebury 2014 Motorcycle photographs

`tools/benchmark_dense_middlebury.py` fetches the downsampled *real*
rectified stereo pair through `skimage.data.stereo_motorcycle()`. The images
are reduced to 228×154 pixels for CPU resource safety. The benchmark supplies
published calibration and an arbitrary unit baseline; no ground truth is used
for hypothesis selection, geometry generation or filtering. The independent
reference disparity is inspected **after** native reconstruction.

| Measure | Host result |
|---|---:|
| Reciprocal-consistent dense points | 4,248 |
| Points overlapping valid independent GT | 3,899 |
| Median absolute disparity error | 0.501 px (original 741-pixel image coordinates) |
| 90th-percentile disparity error | 3.043 px |
| Median relative depth error | 0.741% |
| 90th-percentile relative depth error | 4.766% |

This test **does not validate** camera pose estimation in a handheld 360° capture.
Both images were calibrated and rectified in the benchmark beforehand.
The result does not imply a complete scanned object, millimetric accuracy,
mesh fidelity, or visual texture quality on a phone.

## Reproduce

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pip install -r tools/requirements-real-benchmark.txt
python -m pytest tests -q
python tools/benchmark_dense_middlebury.py --gate --output reports/m07-middlebury-dense.json
```

## Quality/failure boundaries

- Input scenes should be **static, matte, textured and overlapping**. Reflective,
  transparent, repetitive and flat-color materials can yield no dense results.
- Plane-sweep assumes **locally fronto-parallel patches** and uses low-resolution
  images. It cannot replace a full multiscale PatchMatch MVS/TSDF pipeline.
- If camera registration is wrong, depth prior too narrow or calibrated lens
  differs, dense reconstruction is rejected or inaccurate.
- Android has not been validated for real-image thermal/memory behavior on a
  physical phone. The JNI/emulator gate verifies integration but not camera optics.
- Dense points are **not watertight or suitable for 3D printing** without meshing,
  artifact removal, confidence fusion, and surface-quality validation.

## Next M08

Calibrated, authorized 3+ view physical-object datasets with reference
surfaces; robust multiresolution depth refinement; confidence-based spatial
fusion and surface meshing, followed by photographic UV projection. Advance
only after real-object accuracy and mobile thermal/resource gates pass.
