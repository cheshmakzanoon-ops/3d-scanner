# New synthetic visible-surface kernel baseline

**Measurement, not a quality pass or replacement algorithm.** This is a new,
reproducible fixture, not a recovery/replay of the unavailable private snapshot or
an extension of the rejected projective-field-v2 algorithm.

## Result on this fixture

| Measurement | Result |
|---|---:|
| Projected observable-pixel coverage | 75.0822% |
| Projected coverage with absolute depth residual ≤1% bbox diagonal | 70.7089% |
| Area-sampled observable surface within 1% bbox diagonal | **33.8779%** |
| Observable reference samples / all uniform-area samples | 3,734 / 6,000 |
| Reference→candidate distance p50 / p95 | 0.128287 / 0.650482 |
| Candidate→observable-reference distance p50 / p95 | 0.004184 / 0.019048 |
| Vertices / triangles | 4,549 / 13,212 |
| Native nonmanifold edges | 3,432 |
| Native reported intersections | 15,401; `topologyIncomplete=true` |
| Native CPU wall time, first final run | 3.1753 s |

The discrepancy between retained-patch accuracy and whole-observable-surface
completeness is substantive. Even projected coverage masks missing/poorly
reconstructed surface area; no quality acceptance follows. The maximum per-view
covered-pixel depth residual reaches 1.52151 units. Native topology counters are
bounded diagnostics, not an exhaustive external intersection audit. Two final
runs have identical raw native JSON and evaluation metrics; the small arithmetic
safety fix also leaves this supported-input native output unchanged.

## Boundary and fixture

Eight deterministic Blender 4.3.2 CPU renders (160×120, 32 samples, seed 78424) of a
rigid cuboid, dimensions 2×1.5×1.3 arbitrary units, independently seeded per-face
texture. Emission albedo intentionally isolates geometry from lighting/specularity.
Cameras cover a front arc −35°..35°, radius 5, height 1.8. This is not full 360°,
a physical object, or realistic material/capture qualification. The current eight
dense-view and twelve-photo caps are unchanged.

The **known-camera kernel arm** passes only RGBA images, known camera poses and
intrinsics, and fixed near=3/far=7/96 depth planes/stride=2 to the existing
`NativeEngine.surface → rf_surface_json → reconstructDense → fuseDepthSurfaces`.
Default native settings are retained. Reference geometry is not supplied to the
solver, used to create/repair candidate triangles, or used to fit candidate scale.
The image-only/estimated-camera arm is **NOT_RUN**: the legacy Python three-view
SIFT harness is not the production Kotlin frontend. Android controller,
coarse-to-fine/prior-assisted path, checkpoint/recovery journey and physical-device
parity are **NOT_RUN**. An isolated fresh Release library was compiled; no shipping
binary, production setting, cache version or algorithm changed for this benchmark.

## What the measurements mean

- Truth-only z-buffering at pixel centers evaluates surfaces actually observable
  in at least two cameras, positive depth and a six-pixel processing margin.
  Nearest candidate triangle wins. Projected coverage is not accurate coverage;
  per-view residuals and accurate-coverage CDFs are retained separately.
- 6,000 seeded area-uniform reference samples measure reference→candidate closest
  triangle distance, after excluding unobservable reference samples. This denominator
  does not shrink with candidate output. No nearest-vertex surrogate or ICP is used.
- All 6,000 area-uniform candidate samples, including geometry outside observable
  truth, measure candidate→observable-reference triangle distance. Duplicated or
  overlapping candidate faces can bias this area-weighted statistic; native topology
  diagnostics are retained, and it is not a topology certificate.
- Distance CDF locations 0.5%, 1%, 2%, 5% of reference bounding-box diagonal are
  descriptive reporting coordinates, **not invented pass thresholds**. Distances
  otherwise use arbitrary synthetic world units. Registration is identity in the
  supplied camera frame: no mesh alignment or fitted scale.
- The evaluator deliberately accepts only the exact generated convex cuboid.
  Every reference vertex must lie inside the margin in every camera. For this
  convex, planar-faced object, front-face visibility is constant over each triangle,
  proving whole-face eligibility used by the candidate-distance metric. Partial-FOV
  and alternate/nonconvex reference fixtures are rejected rather than approximated.
- Near-plane-crossing candidate triangles are not clipped by this small rasterizer;
  skipped nonpositive-z triangle counts are explicit. They are zero in this run.
  Malformed candidates fail; empty candidates remain explicit zero completeness.

## Bundled evaluator-only truth

`tools/qa/visible_surface_baseline/fixture/fixture.json` contains the exact compact
reference cuboid, known cameras and 240 Blender raycast witnesses, with SHA-256
matching `measurement.json`. It contains **no reconstructed candidate and no PNG
images**. The thirteen evaluator/wrapper guards can run directly from the harness
directory without Blender. This is test/evaluation truth only, never solver input
geometry. Actual reconstruction still requires generating all eight image inputs
with Blender as below; generation reproduces the same manifest exactly.

```sh
cd tools/qa/visible_surface_baseline
python -m unittest -v test_evaluate test_run
```

## Reproduce outside the source checkout

Use the already installed pinned host Python dependencies (NumPy, OpenCV, trimesh),
CMake/Ninja/compiler and official Blender 4.3.2. No installation or upgrade is part
of the harness. Replace the two absolute paths below with your checkout/scratch.

```sh
REPO=/absolute/path/to/3d-scanner
SCRATCH=/absolute/path/to/new-scratch
mkdir -p "$SCRATCH"
cp "$REPO"/tools/qa/visible_surface_baseline/*.py "$SCRATCH"/
cp -R "$REPO"/tools/qa/visible_surface_baseline/fixture "$SCRATCH"/
cd "$SCRATCH"
cmake -S "$REPO" -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build --target realityforge -j2
blender -b -t 2 --python generate.py -- fixture
python -m unittest -v test_evaluate test_run
python run.py --repo "$REPO" --library build/librealityforge.so \
  --fixture fixture --output run-a
python run.py --repo "$REPO" --library build/librealityforge.so \
  --fixture fixture --output run-b
```

Use fresh output directories; existing ones are refused. `FAILED` exits nonzero;
`ASSESSMENT_COMPLETED_NOT_QUALITY_PASS` only means evaluation completed. A separate
`nativeStatus=MESH_FAILED` retains an honest failed reconstruction. Thirteen guards
cover identity/empty/displaced meshes, hidden faces, independent Blender raycast
witnesses, nearest z-buffer, malformed reference/cameras/candidates, partial FOV,
nonpositive-z triangle disclosure, failure exits and stale-output protection.

The generator removes only PNG textual ancillary metadata (including Blender's
wall-clock/render timings), CRC-checking chunks and preserving image/color data.
Two final canonical renders are byte-identical; decoded pixels match the first
noncanonical render. Camera convention is independently checked against 240
Blender scene-raycast witnesses. No bulk renders, meshes or binaries are committed.

`measurement.json` retains the compact final result plus exact input/library/source/
script hashes and actual process mappings. `reproducibility.json` binds pre/post
clean-build source digests, tool/build-log hashes, deterministic replay and retained
failed exploratory observations. This is not a hermetic operating-system/compiler
closure. Source commit names are historical provenance; hashes identify actual
compiled/input content, including the concurrent numerical-safety fix.
