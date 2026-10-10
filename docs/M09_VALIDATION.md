# RealityForge M09 — Photographic UV atlas and indexed textured GLB

**2026-10-10.** This milestone adds **real photo-derived UV textures** to the
previous M08 observed-open-triangle mesh. It does not reconstruct the missing
backside of an object, provide metric dimensions, guarantee continuous/watertight
topology, or claim commercially polished scan materials.

## Implemented and bounded

- OpenCV/Kotlin captures remain strictly local. For M09 the Android engine loads
  the **original** captured/imported photo a second time at up to **768 pixels
  on the longest edge**, independently of its 256px CPU stereo depth pass.
  Both images use the same calibrated, EXIF-upright, aspect-checked lens and
  rectification transforms; projective intrinsics and 3D gauge are shared.
- Native C++ `bakePhotographicAtlas` uses real rectified RGBA photo pixels,
  face normals and camera intrinsics to project indexed mesh triangles into
  2–4 source photographs. It rejects invalid/masked samples, back-facing
  surfaces, degenerate triangles, different camera/crop geometry and secondary
  photos with incompatible depth support. The original camera which provided
  the verified M08 mesh face already has four depth-consistent source corners.
- Chooses one visible camera per triangle, biased slightly toward its
  verified source face to reduce gratuitous seams. Duplicates only UV seam
  vertices; **does not fabricate geometric vertices or backfaces**.
- Builds a bounded camera-tiled PNG atlas with three-pixel guarded gutters
  and lossless RGBA8 serialization (dependency-free PNG/zlib stored blocks).
  This is an image atlas, **not an advanced packed, seam-harmonized per-chart UV
  unwrap**. A camera tile includes pixels outside the mesh; triangles reference
  only their supported photo area.
- Writes indexed, valid GLB 2.0 **TRIANGLES** with `TEXCOORD_0`, NORMAL and
  embedded `image/png` via bufferView, image and texture, and PBR material
  `baseColorTexture`. It does **not** multiply photo color by old vertex colors.
  A separate PNG atlas may be exported for inspection; old M08 `mesh.glb`,
  `mesh.ply` and M07/M06 POINTS are preserved unchanged.
- Android generates a new `sparse-m09-*` artifact key and writes only
  verified local `.partial` files, atomically publishing `textured.glb` and
  `texture.png` after successful validation. Any texture reconstruction,
  high-resolution decode, or publication failure is reported separately;
  approved M08 geometry and point clouds survive. No INTERNET permission.
- RAM/time bounds: 2–4 cameras, geometry <=640px, source photos <=1024px,
  <=40,000 existing mesh vertices, <=80,000 input triangles, <=240,000
  seam-indexed UV vertices, <=4096px atlas dimensions. On Android, <=3 cameras,
  <=256px depth edge, <=768px photographic texture edge.

## Independently verified native synthetic test

- All **six** native CTest executables pass, including a new seven-case M09
  texture test suite: photo-derived UV mesh, invalid-buffer rejection,
  incompatible camera/lens rejection, alpha-mask occlusion, determinism,
  correct GLB/PNG export headers and uniform-photo negative cases.
- A separate Python/Pillow check opens the PNG; an independent `trimesh`
  glTF reader recognizes the indexed, camera-textured mesh.

## Independently measured *real* stereo benchmark

`python tools/benchmark_texture_middlebury.py --gate` uses the published
**Middlebury 2014 Motorcycle** two-photo rectified calibrated stereo pair,
original source photographs resized to 608px width for appearance, and
geometry reconstructed at 228px width. **Ground-truth disparity is never
used to create the mesh, colors, UVs or camera assignments**. The GLB parser
verifies the embedded PNG, atlas photo pixels, UV accessors and material.

| Metric | Host measurement |
|---|---:|
| M08 candidate triangles | 3,530 |
| Photographically supported textured triangles | **1,079** |
| UV vertices (split only where camera charts require) | **1,648** |
| Triangle texture coverage | **30.57%** |
| Selected faces from camera 0 / camera 1 | 601 / 478 |
| Camera-chart transition edges (possible seams) | 211 |
| Photo atlas dimensions | **1228 × 416** |
| Independent ground-truth median disparity error | ~0.479px |

Texture visibility is intentionally conservative and leaves many validated
M08 triangles **untextured** rather than inventing visual evidence. This
benchmark is **not a physical-phone 360-degree scan**. It cannot establish
texture sharpness, hidden-surface completeness, photometric seam continuity,
exposure harmonization, mobile thermal performance, or suitability for 3D
printing. UV chart transitions remain visible and triangles assigned to
different cameras may have exposure seams. Geometry may overlap, have holes,
and contain duplicated coplanar patches from M08.

## Reproduce

```bash
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
cmake --build build/host --parallel 2
ctest --test-dir build/host --output-on-failure
python -m pip install -r tools/requirements-real-benchmark.txt
python -m pytest tests -q
python tools/benchmark_texture_middlebury.py --output reports/m09-middlebury-texture.json --gate
```

The Android build checks also compile Kotlin/NDK, lint and native export
metadata. Emulator instrumentation invokes M09 through the actual JNI
function, checks on-device PNG and GLB headers and tests malformed arrays.
Only a **physical Android camera** with known calibration and independent
reference geometry can establish end-to-end scanner accuracy.

## M10 engineering gate

1. Capture actual 12–40 photo objects on a physical ARM64 phone with a locked
   lens and locally fitted checkerboard. Verify input hash/calibration, pose
   errors, object completeness, loss of camera coverage, memory and thermals.
2. Improve M08 mesh topology (surface overlap, manifold checks and occlusion)
   and increase M09 UV coverage by better view selection/validated depth and
   chart packing, with exposure balancing and seam filtering.
3. Measure material appearance separately from reprojection/mesh metrics.
   Preserve holes when no photo supports a surface; do not ship fabricated
   watertight models or promise high-detail consumer results.
