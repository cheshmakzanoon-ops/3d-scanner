# M10 — texture correctness, measured exposure and seam-aware labels

## Defects fixed

M09's texture-local cross product returned only X and Y. Z defaulted to zero,
causing its visibility test to reject front-facing geometry. Correcting the
vector restored coverage without changing mesh coordinates, normal thresholds,
depth tolerances or masks. The older tests used bounds/low-count thresholds that
missed this. New analytical fronto-parallel tests require all visible triangles.

M09 also flipped glTF V. glTF's (0,0) refers to the upper-left image corner,
so the extra flip mirrored the photographic image. Tests now independently
project emitted XYZ back to the source photograph and compare top-down UVs.
Primary specification: https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
section 3.8.2, texture origin. PNG data is not vertically flipped.

## Appearance implementation

Visible, depth-consistent mesh points supply bilinearly sampled linear-sRGB
color pairs. Bounded per-channel log-exposure fits use robust medians, a small
connected-camera least-squares graph, fixed camera-zero exposure and gains
limited to [0.5,2]. Saturated/near-black pairs, high color dispersion, inadequate
sample count and narrow spatial coverage do not calibrate the camera. Disconnected
cameras stay identity. A measured-error gate rolls back non-improving changes.
Original photo buffers/files are not modified. Atlas pixels are explainable
measured transforms, not generated appearance.

A four-pass deterministic label optimization uses only previously valid source
cameras. Neighbor penalties apply on two-face, similarly oriented shared edges;
nonmanifold adjacencies are not silently merged. A seam-count rollback prevents
increased total reported switches. This is NOT UV repacking, multiband blending,
complete seam elimination or topology repair.

## Measured real-photograph result

Same Middlebury Motorcycle stereo photos, cameras, mesh settings and geometry:

| Metric | M09 / before | M10 / after |
|---|---:|---:|
| Existing mesh faces | 3,530 | 3,530 |
| Textured faces | 1,079 | 3,530 |
| Coverage of existing triangles | 30.5666% | 100% |
| Geometry median disparity error | 0.47948 px | 0.47948 px |
| Median overlap absolute log-color difference | 0.06746 | 0.05345 |
| Camera-label edges, corrected normal baseline | 791 | 787 |

982 shared-surface samples supported the exposure comparison. Color discrepancy
fell about 20.76%. Seam improvement is modest and reported separately from the
normal/UV fix. `reports/m10-comparison.json` and `m10-middlebury-texture.json`
contain actual host measurements. Ground truth is consulted only for evaluation.

## Validation boundaries

The source now has seven native CTest targets, including eight new analytical
photometric/UV cases: robust outliers, bounded gains, disconnected/unsupported
cameras, unchanged originals, full planar coverage, correct glTF UV origin,
label coherence and mask/backface rejection. Real-photo Python tests verify
emitted UVs, embedded PNG and measured per-pixel color transformations.
Android JNI tests now require high planar coverage and correct UV-origin metadata.
Android compilation/runtime status must be taken from the new CI run.

No physical handset camera, complete 360-degree object, sustained thermal,
large-memory workload, clean manifold topology or public release readiness has
been demonstrated. Image geometry still uses a small low-resolution depth set.
