# M08 — Verified-depth fusion and open triangle-mesh reconstruction

**Scope:** an incremental CPU reference built on M07's calibrated/rectified
inverse-depth stereo. The output is **a real indexed triangle mesh**, not a
point cloud mislabeled as a mesh. It only represents observed, stereo-supported
surface regions, remains in the original arbitrary SfM unit-baseline scale,
uses **photograph-sampled vertex colors** (not UV textures), and is **not**
necessarily watertight, manifold, scan-complete or print-ready.

## What changed

- M07 still rejects textureless pixels, ambiguous plane-sweep minima, bad
  camera projections, and depths lacking reciprocal camera-z verification.
  M08 retains each **verified pixel's** source image coordinate, world XYZ,
  confidence and supporting-camera count in a bounded transient buffer.
- M08 fuses independently observed 3D samples between registered views by
  distance-limited, **confidence-weighted** voxel-neighbor averaging of XYZ and
  colors; samples from the **same source image cannot collapse into one vertex**.
  The maximum fusion distance scales with the median image pixel footprint.
- Triangles are formed **only from complete 2×2 neighboring verified depth
  samples**. A pair of triangles per cell is selected using its geometric
  diagonal. Faces are rejected when corners are missing, the depth jump exceeds
  the configured fraction, edges are too long for the estimated scene depth,
  indices would be repeated, the area is degenerate or the indexed triangle is
  a duplicate. This never fills missing backfaces or large holes.
- Area-weighted vertex normals are computed from accepted faces. Unreferenced
  vertices are removed and triangle indices compacted.
- Validated, indexed GLB **mode=4 TRIANGLES** and PLY with face indices are
  exported independently of the existing sparse/dense POINTS GLBs. glTF also
  contains NORMAL and COLOR_0 vertex attributes. No TEXCOORD_0 or image textures
  are claimed; GLB uses explicit double-sided material for visible open meshes.
- Native C ABI/JNI produces both dense depth and the mesh from **one shared
  plane-sweep pass**. Android publishes mesh GLB/PLY from temporary app-private
  files only after quality checks. On failure the M07 dense point cloud and the
  accepted sparse scan remain available with a separate mesh failure reason.
- The UI offers an orbitable, depth-sorted shaded triangle preview with optional
  wireframe and explicit mesh GLB/PLY export controls. Its Canvas preview caps
  rendered faces for responsiveness; the on-disk file contains all accepted faces.
- Bounds: 2–4 calibrated views, <=640px edge, <=320,000 pixels/image,
  12–128 plane candidates, <=50,000 checked depth samples,
  <=40,000 mesh vertices and <=80,000 indexed triangles. M08 Android is more
  conservative: max 3 views, 256px edge, 56 inverse-depth candidates, stride 3.

## Verified native synthetic three-camera scene

**Actual C++ measured test:** 4,676 reciprocal-confirmed source depth samples
produce **2,570 fused vertices**, **7,125 triangles**, and **2,101** cross-view
fusion events. A GLB import using independent `trimesh` confirmed an indexed
triangle surface with valid normals and an open (non-watertight) boundary.
Other native tests verify that uniform photographs create no fictitious faces,
an occluded camera cannot erase well-supported surfaces, large depth steps
cannot be bridged, malformed C API and unverified observations are rejected,
and the resulting face topology is deterministic.

## REAL Middlebury 2014 Motorcycle stereo (separate benchmark)

`python tools/benchmark_surface_middlebury.py --gate` uses two *real*,
published, rectified photographs at 228×154 preview resolution and published
calibration with **arbitrary 1-unit stereo baseline**. Independent ground-truth
disparity is consulted **only after** optimization, for error measurement. This
does NOT validate simultaneous SfM registration or a physical Android capture.

| Measure | Host result |
|---|---:|
| Reciprocal-verified depth samples | 4,271 |
| Fused mesh vertices | 2,454 |
| Indexed triangle faces | **3,530** |
| Cross-view fusion events | 1,111 |
| Mesh vertices overlapping independent GT | 2,274 |
| Median absolute disparity error (source pixels) | 0.479 px |
| 90th-percentile disparity error | 2.315 px |
| Median relative depth error | 0.717% |
| 90th-percentile relative depth error | 3.328% |
| Largest face relative depth jump | 3.083% |

These measurements are for two visible stereo surfaces at low resolution; they
cannot be extrapolated to arbitrary full-object scanning, material fidelity,
physical millimeters, holes, thin structures, or UV atlas quality.

## Reproduce

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pip install -r tools/requirements-real-benchmark.txt
python -m pytest tests -q
python tools/benchmark_surface_middlebury.py --gate --output reports/m08-middlebury-surface.json
```

The host ABI also writes a real index-bearing mesh GLB/PLY. `tests/test_surface.py`
parses GLB 2.0 chunks/accessors (including TRIANGLES mode, uint32 indices,
vertex normals and colors) and validates PLY face metadata. Android CI compiles
JNI and runs a real in-emulator instrumentation test on rendered multi-view
photographs. Actual physical-camera/thermal testing is still outstanding.

## Known limitations and next stage

The mesh is composed of conservative camera-local patches merged at shared
vertices; in multi-view overlap, **coplanar surfaces can still overlap** and
boundaries may remain disconnected. There is no screened Poisson reconstruction,
volumetric TSDF, mesh self-intersection repair, complete-object guarantees,
photographic UV projection, seam removal or metric scale. Higher quality will
require calibrated physical 360° datasets, coverage/occlusion analysis, improved
normal-aware fusion and topology checks, then verified high-resolution UV atlas
and photographic texture baking.
