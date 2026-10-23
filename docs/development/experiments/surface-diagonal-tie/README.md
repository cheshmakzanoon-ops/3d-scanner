# Exact diagonal-tie defect in observed surface meshing

**Synthetic host evidence; narrow correctness repair, not a general fusion or
physical scanning quality pass.** Baseline source is
`b838b3c69d1a7a03bc3be3eb2cb9f099b8bc4df2`. Production implementation and
regressions live in `reconstruction/src/surface.cpp` and
`reconstruction/tests/surface_test.cpp`; they are not duplicated here.

## Finding and correction

The mesher triangulates each reference image grid independently after vertex
clustering. Exact index-triple deduplication and exact oriented-coordinate-face
cleanup do not resolve differing tessellations of overlapping observed cells.
Perfect-depth synthetic fixtures reproduce nonmanifold edges and intersections
without any SfM or MVS error.

One separable defect occurs when both diagonals have exactly equal lengths:
choosing by camera-local pixel labels gives opposite tessellations when one
camera is rolled 90 degrees. The correction picks a canonical lexicographic
world-coordinate endpoint pair **only on exact length ties**. There is no
epsilon, threshold tuning, vertex displacement, changed confidence rule,
hole filling, global reference-view filtering, or changed strictly-shorter
selection. This is consistent across local orderings of a common world quad,
not invariant to arbitrary world-coordinate gauge rotations.

## Results

| Synthetic control | Faces before→after | Nonmanifold before→after | Intersections before→after | Accepted samples | Retained refs before→after |
|---|---:|---:|---:|---:|---:|
| Plane, 90-degree camera roll |1024→512|420→0|1024→0|578|578→514|
| Same, camera order reversed |1024→512|420→0|1024→0|578|578→514|
| Partial overlap |768→512|188→0|512→0|442|442→394|
| Measured depth step |960→480|347→0|960→0|578|578→484|
| Translated grid, 0.01 world / 0.2 pixel |632→632|56→56|212→212|578|578→578|
| Curved paraboloid |512→512|0→0|0→0|578|514→514|
| Strict non-tie slanted plane |512→512|0→0|0→0|578|514→514|

All control topology checks completed. Curved and non-tie control JSON was
byte-identical for the geometry-only diagnostic candidate, before the required
algorithm-version bump. Translated control geometry JSON changes despite
unchanged topology counts: generic overlapping-grid fusion remains unresolved.

The full-plane analytic union area is 2.56. Baseline summed area 5.12 counts it
twice; candidate area 2.56 represents the same support once. Partial overlap
sum 3.84→2.56; depth-step sum 5.9264→2.9632 with no triangle bridging the measured
step. Independent polygon clipping of every baseline triangle against the
candidate coplanar triangles finds summed uncovered area≤4.85e-16 and maximum
individual uncovered area≤6.94e-18, numerical residue rather than lost coverage.
See `results/coverage.json`.

**Accepted samples are not retained provenance.** Existing exact-face cleanup
and vertex compaction drop duplicate boundary observation refs. For changed
controls every dropped original position has a surviving vertex within
1.11022e-16 world (weighted-position roundoff), but the refs themselves are
lost. The correction neither fixes nor conceals this existing behavior.
`results/provenance.txt` records the distinction, including failure of some
bitwise-position equalities. No provenance-preservation claim follows.

## Frozen photograph replay: no observed improvement

For a separately retained frozen 384×288 first-three-view **synthetic photograph**
bundle, the matched current-library baseline and geometry-only candidate dense
and mesh JSON are exactly equal:

- 19,901verified dense observations; 19,891accepted;
- 10,760vertices; 26,079faces;
- 5,940nonmanifold edges; 3,518bounded intersections;
- `topologyIncomplete=true` (the intersection count is not exhaustive).

No geometry improvement is established on this reconstruction. The older replay
used a different library and produced 19,916verified observations,
5,936nonmanifold edges and3,555intersections; those numbers cannot attribute any
change to this correction. Historical whole-visible-area 44.4558% within 1% diagonal
was not rerun for the identical current candidate/baseline meshes. Exact artifact
and library hashes are in `results/frozen-replay-summary.json`.

Recovered image-only cameras and sparse-derived near/far settings are retained
in `frozen-mesh-input.json`. Ground-truth scene geometry, poses and scale, ICP,
cropping, or evaluation-dependent thresholds were not supplied to this replay.
The direct-depth analytic control fixtures are unit-test inputs, not evidence
that an image-only solver recovered those surfaces.

## Reproduce the small controls

Prerequisites: a C++20 compiler, CMake, zlib development files, Python3 and NumPy;
use the repository's documented host toolchain. From the repository root,
choose a fresh, existing empty scratch directory outside the repository:
The runner rejects nonempty or repository-descendant outputs without deleting
anything; both builds require the campaign031–060 native providers.

```sh
EVIDENCE=docs/development/experiments/surface-diagonal-tie
RUN=$(mktemp -d)
sh "$EVIDENCE/run-controls.sh" "$PWD" "$RUN"
```

The portable script whole flow is **NOT_RUN**; its syntax and safe rejection
paths were checked, and its individual control/coverage computations reproduced
the recorded results against the diagnostic libraries.

The script extracts the baseline commit with local `git archive`, builds its
native library and the current checkout independently, then compiles the same
small original `controls.cpp` against each. Generated meshes and binaries stay
in `$RUN`, not this evidence directory. `coverage.py` checks baseline triangle
coverage; `compare-controls.py` separates geometry differences from the intended
`fusionVersion` change. For integrated regressions, run the repository's normal
CMake/CTest and pytest commands; the recorded standalone log is not a substitute.

`results/integrated-surface-tests-before.log` records the new regression failing
against baseline. `results/integrated-surface-tests.log` records 11 surface cases
passing against the geometry candidate, including NaN rejection, known
provenance-compaction counts and the translated-grid negative control. These
logs do not establish Android compilation, instrumentation, device performance
or later integrated-candidate qualification.

## Reproduce the frozen replay only with the retained inputs

Photographs and large mesh outputs are deliberately absent. Full image-only
replay cannot be reproduced from this compact directory alone. Obtain the
original synthetic `inputs` bundle and verify `frozen-input-hashes.json`; do not
substitute a regenerated or physical capture while calling it an exact replay.
The Python environment also needs OpenCV and the repository's native wrapper.

```sh
python "$EVIDENCE/replay-frozen.py" "$PWD" "$INPUTS" \
  "$RUN/baseline-build/librealityforge.so" "$RUN/baseline-replay.json"
python "$EVIDENCE/replay-frozen.py" "$PWD" "$INPUTS" \
  "$RUN/canonical-build/librealityforge.so" "$RUN/candidate-replay.json"
```

The recorded diagnostic used the unchanged baseline fusionVersion 6 metadata.
Integrated version 7 is expected to differ in identity fields even when geometry
is identical. Preserve the originals and compare dense/geometry data separately
from identity. Surface/texture descendant cache invalidation and a distinct
immutable native checkpoint filename are required; a native digest bump alone
can leave an old filename blocking new publication. Dense caches need not be
invalidated. Consult committed cache tests rather than inferring cache safety
from the geometry experiment.

## Remaining limitation

Arbitrary translated/curved overlapping charts need a coherent, bounded surface
triangulation or measured-support seam ownership/stitching strategy. Keeping one
reference globally, deleting every nonmanifold face or loosening fusion gates
would discard support or hide difficult regions, and is not proposed here.
Broader work must preserve observations, measure completeness, handle arbitrary
curvature/discontinuities, and retain runtime budgets, cancellation and cache
identity. No generic repair or acceptance threshold was tuned against these
measurements.
