# Observed-patch distance field — isolated HOST QA, OFF in production

This is a bounded feasibility experiment, **not** a production mesher, TSDF
backend, surface repair, physical quality result or Android qualification. No
root CMake target, production defaults, cache identities or exports are changed.
**Feasibility outcome: NOT a safe physical-surface disambiguator.** The synthetic
sub-bound distinct-layer control produces an unobserved midpoint; common-mode
bias passes unchanged. These are failed/ambiguous geometry controls, not hidden
behind passing implementation tests. No production promotion is warranted.
Original source geometry is not changed. Missing support is never filled, but
support plus a residual bound does not prove one physical surface.

## Input and deterministic selection

The borrowed inputs contain 2–8 calibrated cameras and 2–256 admitted source
triangles. Camera IDs and nonzero source IDs are immutable identities, not array
positions. Source IDs must resolve to the caller's immutable original observation
ledger. The kernel cannot establish whether a caller really supplied reciprocal
photogrammetric evidence: that is an adapter admission responsibility. Synthetic
sources are explicitly synthetic.

Sort cameras/source triangles by their stable IDs. Select the first cross-camera
pair whose world AABBs overlap after each is padded by `maxResidual`. Report
pair visits and misses. That pair is final even if later refused: **never search
until a prettier result appears**. The grid is the union of the pair's world
AABBs expanded by `2*maxResidual` on all axes. This is an axis-aligned, finite
sampling rule, not rotation-equivariant tessellation. It contains 32³ nodes by
default; smaller grids are allowed only as explicit controls.

`maxResidual` is a declared camera-z discrepancy bound in arbitrary scene units,
**not calibrated measurement uncertainty**. Photometric confidence is not inverse
variance. Both basis planes must explain the other triangle within this bound;
a scalar facing sanity gate rejects computed nonpositive facing agreement
(this is not an interval orientation certificate). Equal per-camera basis weights remain fixed across
the grid. Other cameras veto contradictions but do not change weights. Surfaces
separated by less than the bound can remain ambiguous and may average into an
unverified midpoint. Common-mode depth/pose bias is not observable here.

## Whole-triangle support and numeric admission

The per-camera field is ray/plane camera-z residual. Source plane construction,
projection bounds and residual bounds use outward-rounded interval operations
(`nextafter`, no fast-math or fused contraction). A source triangle's three
camera-ray halfspaces form a convex viewing cone. Each cell requires all eight
corners strictly inside one **same** source triangle in each basis camera, with
positive camera-z. Every emitted triangle's rounded vertices are recertified
against these cones and its whole-triangle residual interval.

For `r(X)=z*(d-n·q)/(n·q)`, affine factors attain extrema at simplex/box corners.
Interval products/division enclose the complete residual; a denominator interval
crossing zero refuses. This bounds floating arithmetic around the supplied
measurements, not their physical noise. The residual tolerance is slightly
consumed, never extended, by the numeric admission margin.

For **every** admitted camera/source, interval-projected AABB overlap with the
cell conservatively detects possible additional layers. A second overlapping
source in one camera refuses the cell, even if actually adjacent or coplanar;
a partially overlapping witness also refuses. This is intentionally more
conservative than exact projected-polygon overlap. A witness outside the entire
cell footprint is absent, not contradictory. Camera-plane-straddling cells are
unknown. All relevant source witnesses must be supplied; a patch adapter may
not truncate them to fit 256 sources.

A globally consistent six-tetrahedra cube decomposition extracts the fixed-pair
field. Vertices use sorted global node-pair keys and identical canonical edge
interpolation; faces retain per-camera source IDs and `derivedInterpolated=true`.
No field value is taken as evidence without cell admission. No sign is supplied
in unknown regions; no boundary is capped. Exact or numerically unresolved signs
refuse cells, never epsilon-jitter their geometry. Numeric/area failure while
emitting, output exhaustion, cancellation and work exhaustion refuse the **entire
patch**, exposing no partial face span. Attempt counters remain available.

## Bounds

- Fixed owned workspace: `workspaceBytes()` / compile-time `sizeof(Workspace)`,
  asserted <= **8 MiB**. One allocation owns all camera/source copies, 32³ field
  nodes and validity bytes, and **32,768 output faces** including provenance.
- Borrowed input memory, test/CLI transport, allocator bookkeeping and caller
  reports are outside this owned-buffer figure; it is not Android process RSS.
  Local automatic scratch has fixed small arrays and bounded call depth.
- At most 256 sources / eight cameras / 32³ nodes / 31³ cells / six tetrahedra
  per cell. The theoretical extractor can exceed the face cap; it must refuse,
  not return a successful prefix.
- At most **20,000,000 charged visits**. A visit covers one source/candidate/
  node/cell/tetrahedron/emission or provenance lookup with bounded constant work;
  interval/corner loops have fixed maxima. Cancellation is checked before bulk
  allocation, every 64 visits and before return. Work-cap reporting includes the
  first denied visit. No arbitrary callback latency is bounded.
- Allocation failure is injectable in the test harness. Callback exceptions
  propagate while RAII releases the workspace; no successful output escapes.

## Validation

Build separately (nothing is enabled by the application build):

```sh
cmake -S reconstruction/qa/observed_patch -B /tmp/observed-patch-build -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/observed-patch-build -j2
ctest --test-dir /tmp/observed-patch-build --output-on-failure
OBSERVED_PATCH_TEST_EXE=/tmp/observed-patch-build/observed_patch_tests \
  python -m pytest -q reconstruction/qa/observed_patch/evidence_qa.py
```

`PATCH_SANITIZE=ON` adds ASan/UBSan/float-cast-overflow for this isolated target.
Tests print JSONL: analytic generated-to-truth errors, normal error, actual output
area, full source area, 231 independent truth samples' nearest-output distance
and coverage within 0.01 scene units, boundary counts and all refusal counters.
Missing-output errors are null, never a zero-error quality result. These finite
truth samples are not a certified continuous missing-area calculation.

Controls cover exact/slanted planes, separated layers below/at/above the declared
bound, symmetric/common-mode bias, foreground witnesses, opposing surfaces,
partial/disjoint/touching footprints, missing-middle support, source/camera
permutations, translation/general rotation, unresolved signs, invalid geometry,
allocation failure, output/work/cancellation refusal. Canonical edge coordinates,
face uniqueness and edge incidence are checked separately from kernel logic.
A full source patch has intentionally missing boundary cells: **do not count
fewer triangles/intersections as improvement**. Any real replay requires a
separate explicit source-bound observation adapter and independent review first.

The conceptual range-image integration reference is
[Curless & Levoy (1996)](https://vis.stanford.edu/papers/volrange/paper_1_level/paper.html).
This implementation is written here, not copied from a reference implementation.
Their hole-filling stage is deliberately not part of this experiment.

Current bounded result and stopping decision: [RESULT.md](RESULT.md).
Read-only local provenance characterization (not a reconstruction run):

```sh
python reconstruction/qa/observed_patch/characterize_frozen_replay.py \
  /path/to/checksum-pinned-public-frozen-replay
```

This script never invokes the kernel. Its full-input inspection/JSON transport is
outside the native kernel's 8 MiB workspace claim. `evidence_qa.py` is explicitly
invoked; its name deliberately avoids default pytest discovery.
