# Exact-coplanar support-union experiment (QA only)

## Decision and scope

A narrow geometry-only candidate for exactly coplanar binary64 input. It is **not
an app change, general mesher, noisy-surface association rule, or production
promotion**. No source observations or original adversarial controls are removed.
The original curved-translated and thin-sheet refusals remain. Runtime/offline
contracts and native tolerances are unchanged. No photographic, Android, physical
handset, attribute-quality, or full-app test claim follows.

## Diagnosis

`diagnosis.json` and `original-complementary.mesh` reproduce the original failure:
472 vertices / 864 faces, 192 degenerate faces, 4 nonmanifold vertices, 199 native
components. The minimum XYZ cross-product magnitude is 6.162975822039155e-33.
Example source-grid intersections produce distinct chart coordinates −0.14 and
−0.13999999999999999, plus −0.16 and −0.15999999999999998. Constraining both
nearly coincident *interior* source grids creates microscopic slivers. Nominal
support area does not detect this. Native c30 uses cross magnitude <=
`bbox_diagonal² * 1e-10`; it skips those faces when building edge adjacency but
still counts each face in component roots. Thus 199 is the native diagnostic
count, not evidence of 199 independently measured sheets. No threshold changed.

The correction does not snap intersections or delete bad triangles after meshing.
When all observed source vertices are **exactly** coplanar in both world input and
computed chart coordinates, interior source-grid edges are not geometric
constraints. Union the observed support, remove only exactly collinear boundary
vertices with zero-distance simplify, and constrained-triangulate that support
including all holes/disconnected components, provided emitted geometry passes
the exact post-lift checks below. Preserve original triangles and
observations in separate immutable provenance. For nonplanar inputs, retain the
original arrangement path. A one-ULP nonplanar quad explicitly disables union.

## Provenance and attributes

`faceSourceRelation=positive_area_overlap` means each referenced original triangle
has a positive-area intersection with the emitted face in exact world-plane
coordinates (drop the dominant normal axis without rounding). It does **not** mean it
covers that entire face, survives as a vertex, or contributes an interpolated
color/confidence/texture value. The source ledger's `supportRelation` continues to
separate supported from unmeshed observations; original record values survive.
This candidate computes no output color/confidence field. Reducing an exactly
planar rectangle to four vertices/two faces discards the source vertex layout and
would not preserve arbitrary per-vertex attribute variation. A production
attribute-transfer design is absent and required separately.

An additional disconnected-planar control exposed 13 missing positive-area
provenance links after the emitted-world/chart roundtrip, despite exact support
and clean topology. `initial-disjoint-provenance-failure.log` retains that failure.
Final mapping uses a rational separating-axis predicate on emitted world-plane coordinates,
not GEOS floating area classification. Its independent audit instead uses
rational Sutherland–Hodgman clipping and polygon subtraction. No clipping
implementation is shared with the kernel's overlap predicate. The rational
checker is now also a pre-publication gate; the external audit reruns that same
checker on serialized output. These are not two independently implemented
clipping checkers.

Independent review then found two exact z=3 planar inputs whose floating lift
moved emitted vertices off the observed plane, including one whose rounded chart
support still matched exactly. `review-counterexamples.json` retains their exact
world/chart residual evidence and `regressions/` retains both input fixtures.
The kernel now refuses any nonzero exact world-plane residual, any exact world
support loss/addition, any chart support loss/addition, or provenance mismatch
before publishing. Under this stricter check, the earlier large translated
**disjoint** control also refuses: exact lost world-plane area is
`3602879701896397/5070602400912917605986812821504`, despite zero chart-area
loss/addition. `disjoint-world-review.json` retains that result; the original
fixture is still executed and must refuse. A separate dyadic disconnected
positive control proves preservation of two components when exact representable
output exists. No old bad case was removed or made to pass by a new tolerance.
A third deterministic on-plane/in-plane-roundtrip regression
exercises the support-specific refusal separately. These are honest numerical
refusals, not repaired or force-snapped successes.

## Validation artifacts

- `results/summary.json`: all original success/refusal, malformed, budget, and
  cancellation controls. Complementary is the only original refusal changed to
  a required success; all other expectations stay unchanged.
- `results/independent-audit.json`: frozen native output audit and immutable ledger.
- `results/fail-closed-tests.json`: corrupted ledger/support/permutation/native
  topology, unexpected statuses, optimized-Python rejection, cancellation cleanup.
- `exact-support-audit.json`: rational support and exact link verification for all
  seven planar controls, recomputed from serialized emitted world coordinates.
- `extended-checks.json`: reverse camera/observation/triangle order checks compare
  **unordered** triangle-coordinate sets and support links (not winding); holes, measured
  step, curved-identical, retained translated-disjoint refusal, distinct dyadic
  disconnected positive control, one-ULP nonplanarity.
- `additional-exact-checks.json`: rational reverse complementary/hole/dyadic-disconnected
  checks plus deliberately corrupted geometry and provenance detections.
- `oriented-permutation-frozen.json` and
  `source-build-results/qa/oriented-permutation*.json`: standalone winding-aware
  verification of all five permutation pairs, run on historical frozen and new
  source-build outputs. Cyclic rotations compare equal; reversing one face is
  detected. Both frozen and source-build checks pass normally and under optimized Python. This is
  separate supplemental QA, not a change to the meshing algorithm or old receipts.
- `emitted-regressions.json`: off-plane and on-plane/support-only refusal
  regressions plus the single-triangle LineString API control.
- `final-run.log`: full final executable run; successful exit is required, not
  merely the existence of partial receipt files.

The rational auditor is deliberately bounded to <=4096 input source faces,
<=256 output faces, <=32768 subtraction fragments, <=2000000 clipping calls and
30 seconds per case. The same checker is a planar pre-publication gate, so its
256-face cap also constrains planar candidate acceptance. Those are QA resource
limits, not quality tolerances;
an exceeded limit is an error, not an acceptance. It audits exact binary64 chart
projection coordinates AND direct exact-rational world-plane coordinates, with
exact plane-membership checks. It is not an ideal real-arithmetic camera model. GEOS and
NumPy remain QA dependencies only. Native acceptance does not prove correctness
for every possible numeric input; physical/noisy data remains outside this
exact-plane simplification.

The kernel keeps the original source/cell/output/link budgets and 30-second worker
cap. Exact coplanarity checks cancellation every 64 source triangles; final
provenance checks every 64 candidate pairs and bounds attempted pairs using the
existing 2-million-link resource cap. The clipping checker and GEOS internal work are not cooperatively
cancellable; the checker has wall/operation/fragment bounds and the enclosing
worker remains externally cancellable. Peak RSS, mobile runtime cost and native port qualification NOT_RUN.

### Reproduce the supplemental oriented check

The main runners retain their original unordered permutation checks. After
running either mode, explicitly run the standalone winding-aware checker:

```sh
python3 /path/to/package/check_oriented_permutation.py \
  /path/to/replay/kernel /path/to/new-oriented-receipt.json
```

For fresh-source mode the kernel is `$OUT/replay/kernel`; for frozen mode it is
`$OUT/kernel`. It refuses to overwrite an existing receipt. Each receipt hashes
its checker and both actual candidate files for every pair. This checker compares
exact emitted world coordinates, canonicalizing only cyclic face rotations, and
executes positive cyclic/negative reversed-face controls. Exact boolean types
are rejected as vertex coordinates and face indices, with true/false negative
controls. Receipts use exclusive creation (`x` mode), not an existence-check
followed by overwriting write; the checker verifies an existing receipt remains
byte-identical. `oriented-permutation-overwrite-test.json` additionally records
the CLI-level existing-output rejection. Native winding checks
remain independent per-output checks; they do not replace cross-run comparison.

## Fresh-source reproduction (new evidence, no archived binary required)

`run-from-source.sh` is a separate mode. It needs the local repository with the
pinned c30 commit, local CMake >=3.22, C++20 compiler, zlib development files,
and the existing QA Python dependencies. It performs no network fetch/install.
It archives that exact commit into a new output, builds only `realityforge` with
Release/campaign providers enabled and Vulkan/sanitizers disabled, and copies the
new library/headers into an output-local diagnostic directory. It then invokes
the unchanged acceptance runner through an output-local package whose identity
manifest explicitly describes this **new build**.

```sh
OUT=$(mktemp -d)
PYTHON=/absolute/path/to/qa/python CMAKE=/absolute/path/to/cmake \
  sh /absolute/path/to/package/run-from-source.sh \
  /absolute/path/to/repository "$OUT"
```

All build/archive/binary/mesh files remain under `$OUT`, never in the source
package. Existing/nonempty output is refused; previous evidence is not removed.
`new-build-identities.json` records compiler/toolchain, archive, headers, new
library, configure/build logs and QA source identities. `source-build-result.json`
is created only after the full QA replay passes; detailed logs/results live in
`$OUT/replay`. Every invocation produces **new evidence**: no equality with
historical library bytes is assumed, required, or promised (even if the same
compiler happens to produce identical bytes). Frozen-artifact mode and its
historical receipts below remain separate and unchanged. The final local build/replay completed with exit 0; compact NEW receipts are
under `source-build-results/`, separate from historical frozen receipts. Full
repository CTest/pytest and Android qualification are NOT_RUN by this narrow
experiment.

## Frozen-artifact reproduction (historical identity mode)

The compact package intentionally contains no binary or generated mesh. Its
`final-run.log` and JSON receipts are historical evidence; rerunning generates
fresh meshes, inputs and full logs under the selected output directory. The
original rejected mesh referenced above is regenerated by `diagnose.py`.

Required external inputs (not bundled):

1. A local repository containing git commit
   `c30e123df1d64414417a84d19807918a06cf6390`.
2. The preserved frozen native artifact directory containing
   `librealityforge.so` with SHA-256
   `2c8e015241795183d8c2bf94b18c5b62a90653dd2a8dee77f51b3cb654e98c07`
   and `reconstruction/include/` headers. All 41 headers are checked against
   both `identities.json` and the named commit. In the original workspace this
   directory is `/workspace/scratch/6b683a119bde/chart-overlay-prototype/frozen-native`.
   This is an explicit external artifact prerequisite: the package cannot
   reproduce without those exact library bytes. It does NOT claim a new build
   would be byte-identical. Missing/mismatched artifacts stop before evaluation.
   Headers can be restored from the named git commit without restoring binaries.
3. Linux/POSIX, C++20 compiler, Python with NumPy 2.2.6 and Shapely 2.1.2
   (`requirements-qa.txt`). Existing installations may be selected with PYTHON
   and an absolute PYTHONPATH; or install from official PyPI into a QA-only venv.

```sh
OUT=$(mktemp -d)
PYTHON=/absolute/path/to/qa/python \
  sh /absolute/path/to/package/run-final.sh \
  /absolute/path/to/repository /absolute/path/to/frozen-native "$OUT"
```

The runner locates its own source package, requires an existing empty output
outside all input directories, rebuilds the two small adapters against verified
headers/library, clears dynamic-library override environment variables, verifies
actual loader resolution, and writes **only** to that output. It never moves, deletes, or
overwrites prior evidence. New outputs live under `$OUT/kernel/`; the complete
log is `$OUT/final-run.log`, verified native inputs `$OUT/verified-inputs.json`.
The original compact receipts are not overwritten. The source kernel and all
synthetic fixture generators are bundled. No app runtime dependency is added.

The earlier prototype adapter outside `frozen-native/` resolves a mutable host
library and must NOT be reused. Existing license notices are preserved in
`DEPENDENCIES.md` and `licenses/`.
