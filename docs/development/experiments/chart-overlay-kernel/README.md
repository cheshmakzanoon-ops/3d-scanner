# Bounded single-chart surface overlay — architectural experiment

**QA-only feasibility result, not a production surface replacement.**
Frozen native baseline: `c30e123df1d64414417a84d19807918a06cf6390`.
The declared controls and limits were written before candidate evaluation in
`DECLARED_SCOPE.md`; its original hash is retained. No frozen photographic
reference geometry was evaluated or used to choose an algorithm threshold.

## Actionable result

A conforming arrangement can remove translated-grid overlap without discarding
views, filling unknown holes, or losing source observation records. But the
current prototype **cannot safely associate independently discretized curved
surfaces**, and its floating-point arrangement can produce degenerate slivers
for near-coincident grids. Those cases are explicitly refused, not repaired by
filtering or a fallback overlapping mesh.

| Control | Native baseline | Final chart outcome |
|---|---|---|
| Translated plane, 0.01 world/0.2 pixel |56 nonmanifold edges; 212 intersections |PASS: 0 edges/0 vertices nonmanifold; 0 intersections; 1 component |
| Same input, reordered cameras/triangles |same baseline |PASS:exactly identical output triangle-coordinate sets |
| Complementary plane, 0.5 world translation |24 nonmanifold edges; 67 intersections |REFUSED: 192 degenerate sliverfaces; 4 nonmanifold vertices; 199 components |
| Identical/rotated/partial planar grids |already no topology defects |PASS; exact support union preserved |
| Interior unobserved hole |already open |PASS; no added support; hole remains open |
| Curved patch with identical sampling |already no topology defects |PASS; coherent curved height-field chart |
| Curved patch with translated sampling |56 nonmanifold edges; 64 intersections |REFUSED:distinct ray depths cannot safely be classified as noise versus layers |
| Measured depth step |2 open components |PASS: 2 components remain; no bridging faces |
| Thin separated same-facing depths |native fusion combines 225 observations |REFUSED:distinct layers are not collapsed |

The thin-depth fixture is an adversarial direct-depth API test with conflicting
per-view depths, **not a physically validated capture**. Its supporting-view
flags are synthetic fixture inputs. The kernel also refuses coincident
opposite-orientation sheets rather than silently merging two-sided evidence.

### Translated-plane accounting

- Original inputs: 578 observations and 1024 source triangles.
- Native output: 353 vertices/632 faces; 56 nonmanifold edges/212 intersections.
- Chart output: 1090 vertices/2080 faces; 0 native degenerate/duplicate/winding/
  nonmanifold/intersection defects; 1 connected component.
- Source and candidate support union: 0.10304000000000002 in normalized chart
  area; lost support 0; added unsupported support 0.
- All 578 source IDs and immutable source records retained exactly.
- More triangles are **conforming subdivisions of existing measurements**, not
  extra evidence, higher detail, or a quality/completeness percentage.

Source support is deliberately separate from attribute contributions. The
ledger stores original XYZ, camera/pixel identity, confidence and supporting
view metadata unchanged, plus `supportRelation`. A face references its original
source triangles. These links do **not** assert that an observation contributed
to output color/confidence averaging; this kernel performs no such computation.
No per-vertex confidence or texture quality claim follows.

## Representation and algorithm

1. Use original observed source triangles before cross-view vertex averaging;
   choose one chart from existing calibration in deterministic calibration order.
   There is no fitted world plane, ground-truth alignment or global best-view
   selection. A common chart is a representation, not a discarded source view.
2. Project observed triangles; reject horizon, zero-area, opposite-orientation
   or same-view overlapping chart cases.
3. Node all projected source edges into a conforming planar arrangement and
   triangulate only cells covered by source triangles. Unknown cells remain
   holes; no convex hull or hole filling is used.
4. Every arrangement vertex gets **one shared chart-ray lift** from its incident
   source-triangle plane interpolants. Independently lifted owner faces would
   leave cracks. This first conservative kernel accepts agreement only within
   its floating-arithmetic guard, not a physical depth/noise tolerance.
5. Preserve a complete immutable source ledger separately from output vertices.
   Validate resulting geometry with the existing native topology analyzer
   **before reporting a successful candidate**.

A single-valued shared lift prevents within-chart crossings, but it does not
solve full-scene chart selection or chart-to-chart seams. The arithmetic guard
is not calibrated uncertainty and is not a proof for all numeric inputs. The
native validator caught a real failure that projected area alone missed.

## Honest stopping boundary

The complementary grid initially appeared successful under projected coverage
and edge incidence. Independent native analysis found 192 degenerate sliverfaces,
4 nonmanifold vertices and 199 components. That initial rejected artifact is
summarized in `results/initial-complementary-failure.json`. Final execution
refuses it before publication. No snapping tolerance, tiny-face deletion or
hard-region filtering was introduced to make it pass.

Translated curved patches and thin separated layers cannot be distinguished
safely using the existing uncalibrated reliability alone. The kernel therefore
refuses both rather than widening agreement thresholds. Production work needs
robust conforming arrangement/seam handling, justified sheet association or
uncertainty, and global chart joins. This experiment stops there. It is **not
promoted into the app** and makes no improvement claim on the frozen photographs.

## Bounds, cancellation and tests

Predeclared caps: 4096 source triangles; 100000 AABB candidate pairs;
32768 arrangement cells; 40000 output vertices; 80000 output faces;
2000000 source-face contributor links. Candidate/fragment/output budget failure
refuses the entire result, not a truncated mesh. Tests also cover nonfinite
samples, duplicate source IDs, same-view overlap and opposite sheet orientation.

The QA worker has a 30 second wall-time cap. Cancellation terminates its POSIX
process group, including any native diagnostic child, without publishing a
candidate. Diagnostic meshes live in parent-owned temporary directories inside
the external run; parent cleanup removes them even if SIGTERM bypasses worker
cleanup. A cooperative precheck is tested too. **GEOS calls are not internally
cooperatively cancellable**, and intermediate native allocations occur before
some post-operation size checks. These are bounded-input QA safeguards, not a
verified native memory/runtime implementation suitable for Android. No peak
RSS, handset power/thermal, physical-capture, full CTest/pytest or Android
qualification is established by this experiment.

The runner enforces the declared success/refusal/guard matrix and exits nonzero
on any unexpected outcome. The independent audit fails closed on topology,
support, immutable-ledger, duplicate-ID or permutation failures; these are known
synthetic reproduction expectations, not held-out quality thresholds. Negative
tests corrupt actual candidate ledger/support/permutation data and inject an
unexpected control status to verify nonzero exits (including optimized Python).

Final results are in `results/summary.json`; independent native topology,
permutation and immutable-ledger checks are in `results/independent-audit.json`.
The initial Shapely 2 API iteration error and subsequent native-audit failure
are recorded in `results/development-chronology.json`, not counted as passes.

## Reproduce

Prerequisites: Linux/POSIX, local git history containing the baseline commit,
CMake, C++20, zlib development files, Python 3.12 with venv/pip, and access to
PyPI for the isolated QA dependencies. Nothing is installed into the application
or fetched at reconstruction runtime.

From the repository root, after placing this directory at the suggested path:

```sh
EVIDENCE=docs/development/experiments/chart-overlay-kernel
RUN=$(mktemp -d)
sh "$EVIDENCE/run-experiment.sh" "$PWD" "$RUN"
```

The runner rejects nonempty output and paths inside the repository. It extracts
the frozen native baseline, builds the small original native adapters, creates
an isolated QA venv, and puts generated inputs/meshes/binaries exclusively in
`$RUN`. The compact Python scripts and native adapters were executed using the
recorded dependency/library hashes. The portable wrapper's **full build/install
flow is NOT_RUN**; shell syntax and its safe-rejection paths are checked. The
wrapper is a reproduction recipe, not an unearned integration receipt.

No production `surface.cpp`, full production tests, generated meshes, photographs
or third-party binaries are duplicated here. `results/identities.json` records
source/library/input hashes. Dependency licenses and primary references are in
`DEPENDENCIES.md` and `licenses/`; no GPL/AGPL implementation is copied.

The two small C++ adapters trust the files generated by this experiment; they
are not general-purpose or untrusted/malformed-input parsers. The malformed
source/identity tests exercise the Python chart kernel, not those adapters.

A post-review rerun initially failed with native exit 139 before any candidate
was produced: an old adapter still resolved a concurrently rebuilt shared
library with a changed C++ SurfaceMesh ABI. This was a test-setup ABI failure,
not an algorithm refusal. The final controls, audits and corruption tests were
rerun against an isolated exact baseline library plus archived baseline headers.
`results/frozen-native-rerun.json` records resolved-library, adapter and header
hashes. Do not relink old C++ adapters to a mutable shared build directory.
