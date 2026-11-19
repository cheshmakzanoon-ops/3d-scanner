# Originating dense and surface loss diagnostics

The current native producer emits **dense loss schema 2** and **surface loss
schema 4**. These are originating-call counters: they describe the evaluation
that produced the geometry. They do not change thresholds, depth sampling,
refinement profiles, normal estimation, fusion, triangle selection or export
geometry. Surface geometry schema 8, fusion version 13, C/JNI signatures and
checkpoint wires remain unchanged. C++ result structs grow and their consumers
must be rebuilt together.

| Record | Current schema | Meaning |
|---|---|---|
| `dense.lossDiagnostics` / `densePreview.lossDiagnostics` | 2 | Originating dense branch counts and photometric subreasons |
| `mesh.lossDiagnostics` / `meshPreview.lossDiagnostics` | 4 | Originating surface counts, retained fusion and topology pass attribution |
| `dense_loss_report` JSON receipt | 3 | Validated read-only interpretation of a supplied artifact |

The report reader accepts dense schemas 1 and 2 and surface schemas 3 and 4.
Missing older evidence remains unknown. See the
[19 November M20 preparation record](M20_PREPARATION_2026_11_19.md) for campaign
results and outstanding qualification gates; a schema change is not a device or
physical-quality result.

## Dense evidence

Fixed arrays hold at most eight reference views. Each view records:

- Fresh photometric coverage and first rejection: invalid reference patch,
  valid low variance, invalid/boundary best, NCC, uniqueness, invalid inverse depth.
  The legacy `photometricDiagnostics.lowVariance` remains the sum of invalid
  reference and valid-low-variance cases, exactly preserving its old semantics.
- Two **nonspatial** 16-bin histograms of existing valid patch mean luminance:
  all valid reference patches, and the valid-low-variance subset. Bins span
  `[0,16)`, `[16,32)`, ... `[240,256)`. No new image traversal is performed.
  Histograms distinguish dark/bright flat evidence, **not foreground/background**.
- Reciprocal sample outcomes and source-pair first rejection: invalid source
  projection, unavailable source depth, depth mismatch, invalid reverse
  projection, reverse reprojection, normal mismatch. Unavailable source depth
  is split into outside image, sampled-grid rejection, missing photometric
  sample, or unsupported strict interpolation. Confirmed-pair counts and
  confirmation histograms independently reconcile the sample/pair accounting.

Reused photometric maps/tiles have unknown photometric branch evidence; those
counts/histograms are `null` when no fresh work was measured. Reciprocal checks
run again and are separately marked complete. A decoded accepted-surface
checkpoint has no originating diagnostic payload: `available=false` and null
views. Do not turn missing old fields into measured zeros.

Kotlin-only complete-result reuse instead copies the prior immutable preview,
including any historical originating diagnostics, and marks `geometryReused=true`.
Those retained counts describe the original geometry evaluation, not work in the
current call. A prior preview without these fields remains unknown. Reopening
the same publication preserves its bytes; it is not another reconstruction.

The report reader validates strict integer counts, view identities and histogram
bins; booleans are not accepted as counts. `sampledSamples` must equal evaluated
plus unknown samples. `COMPLETE` requires positive evaluated samples and no
unknown samples; `PARTIAL` requires both positive evaluated and unknown samples.
`UNAVAILABLE` requires null measured counts and histograms, with all sampled
samples unknown. Each low-variance histogram bin must fit within the matching
valid-reference bin, and both histograms must reconcile with their populations.
An unavailable view does not hide a malformed sibling view. The aggregate sample
budget remains 100,000 across at most eight views.

## Surface evidence

Per-view normal validity, missing/jumping/zero-confidence neighborhood witnesses,
unsupported normal bounds, confidence/vertex-budget rejection, fusion candidate
first gates, cell corner counts, triangle rejection reasons and final observation
attachment counts are bounded aggregates. No pixels, source coordinates, photo
identities or spatial maps are emitted. No upload behavior is introduced.

- All nine validated 3×3 depth samples remain in the confidence-weighted normal
  covariance. The corner samples are not an unused admission guard.
- Neighborhood witnesses prioritize any missing depth, then depth jump, then
  zero confidence. These are descriptive witnesses, not an exact trace of the
  first normal-estimator rejection; they never alter the estimator.
- Fusion candidate counts are visits, not unique points. Each gets one first
  gate outcome. Multiple increasingly closer candidates may be accepted before
  one is selected, so accepted candidates may exceed fused observations.
- Cell counts cover visited cells; `triangleLimitReached=true` warns that the
  loop may have stopped early. Triangle rejection counts apply to actual face
  attempts, not the earlier shared-diagonal support prepass. Successful attempts
  minus exact-cleanup removals equal retained triangles.
- All four three-corner cell configurations already use the generic observed
  corner loop; this instrumentation does not add or invent any missing corner.

These counts locate evidence loss, not whole-object completeness, physical
accuracy, watertightness, or production readiness. The refined/coarse/sparse-prior
path remains opt-in and unchanged. Missing depth is never filled by diagnostics.

### Population and retained-fusion accounting

Every reported surface view must satisfy:

```text
inputSamples = acceptedSamples + confidenceRejected + vertexLimitRejected
```

The sum of per-view inputs and the sum of per-view accepted samples must equal
the corresponding enclosing mesh counts. Counts are bounded nonnegative
integers, and total input cannot exceed 100,000. Standalone surface diagnostics
can establish their view populations and gate funnel, but omit the enclosing
mesh's `fusedObservations` and aggregate sample counts. Their retention is
therefore reported as unknown rather than inferred from final vertex counts.

For a complete mesh artifact, the reader reconciles proposed weld groups,
rolled-back groups, restored source vertices and retained fusion. A weld group
has at least two sources and at most one source per view. Consequently, each
rolled-back group restores between two and the view count's worth of source
vertices; each retained group contributes between one and `viewCount - 1`
fused observations. These sources cannot exceed accepted samples. The selected
merges before topology admission are
`fusedObservations + restoredSourceVertices - rolledBackWeldGroups`, bounded by
the proposed group count and accepted candidate visits. A zero-pass admission
record is valid only for an explicitly measured empty input/result.

`fusedObservations` describes fusion surviving topology admission, before unused
vertex cleanup and topology aliases. Final mesh vertex counts cannot recover
this number. Accepted candidate visits also cannot substitute for retained
fusion: several admissible visits can compete for the same selected merge, and
topology admission can later restore its sources.

### Schema 4 topology pass attribution

`topologyAdmission` adds `locallyRolledBackWeldGroups`,
`fallbackRolledBackWeldGroups`, `retainedWeldGroups` and up to three entries in
`passDetails`. The aggregate retained count equals proposed minus rolled-back
groups; local and fallback rollback totals sum to all rolled-back groups.

| Pass behavior | Recorded action |
|---|---|
| First triangulation | Inspect retained welds and roll back the invalid groups locally |
| Second triangulation, if needed | Reinspect remaining welds; if an invalid weld remains, conservatively roll back every remaining weld group |
| Final triangulation after fallback | Triangulate without retained welds; the weld inspector does not run |

Processing stops as soon as an inspected pass has no invalid welds. A
triangulation that starts without welds also skips the inspector. Such a final
entry has `inspected=false` and null measurement fields, not measured zeros.
An empty input returns before triangulation with zero passes and an empty
`passDetails` array.

Each inspected entry records its one-based `pass`, `retainedWeldGroups` at the
start of that pass, `invalidWeldGroups`, `rolledBackWeldGroups`,
`restoredSourceVertices` and `invalidLinkReasons`. The reason keys are
`branchingLink`, `directionConflict`, `disconnectedLink` and
`invalidEndpointCount`. **Reasons overlap:** one invalid weld can have several
flags. Each reason is bounded by the invalid-group count, while their sum can
exceed it and must never be presented as a count of distinct weld groups.

The reader validates the pass sequence, local/fallback policy, nullability,
remaining-group transitions and aggregate totals. Schema 3 artifacts retain
their aggregate retention accounting; their absent pass attribution remains
unknown. The implementation is in
[`surface_pass_report.py`](../../tools/qa/surface_pass_report.py).

## Resource and verification contract

Storage is fixed independent of image dimensions. The campaign's 64-bit host
size probe measured `SurfaceLossDiagnostics` at **3,128 bytes** and each
`SurfaceTopologyAdmissionPass` at **72 bytes**. The conservative allowance for
three simultaneous dense-plus-surface diagnostic copies is **22,488 bytes**.
Static assertions bound surface diagnostics to 4,096 bytes and that three-copy
allowance to 32 KiB, within the existing 64 KiB fixed scratch reserve. These are
host structure sizes and compile-time bounds, not measured ARM64 process RSS.
No admission/default budget is raised.
Mutable state belongs to the call/result; there is no process-global collector.
The only extra neighborhood scan reads at most nine entries after an unsupported
normal result. Existing cancellation and resource errors remain errors.

Native tests reconcile old/new counters, masks, intensity endpoints, normal
witnesses, fusion/triangle/attachment accounting, bounds, cancellation, resource
failure and fresh/partial/full checkpoint coverage. Python tests parse the actual
C-ABI JSON and check finite bounded nonspatial counters. Before promotion, compare
unchanged legacy fields, geometry arrays and GLB/PLY bytes against the exact
baseline in separate processes on frozen inputs. HOST, Android/device quality and
real-object loss attribution require their own source-bound runs.

The parity runner uses separate ordinary-exit processes for each library on
three labelled synthetic fixtures: textured three-view, masked three-view and
textured two-view. It compares every native result field except
`lossDiagnostics`, plus the actual GLB/PLY bytes. A child timeout, nonzero exit,
missing export or source/library change prevents a passing receipt. Sanitizer
environment settings are inherited unchanged.

From the repository root, with separately built baseline and candidate
libraries, use a **new directory outside the repository**:

```sh
python -m tools.qa.surface_diagnostics_parity \
  --baseline-library /absolute/path/to/baseline/librealityforge.so \
  --candidate-library "$PWD/build/host/librealityforge.so" \
  --output ../surface-parity-001
```

The output directory must not exist or traverse symlinks. An external location
keeps generated evidence from changing the checkout's dirty state. The receipt
identifies tested library bytes, helper sources, fixture buffers and child
artifacts; a library hash does not establish which source built that library.
Parity establishes only same-input equivalence on these synthetic fixtures.

## Controller and HOST retention

`NativeResultJson.decode` retains the complete native object. `DensePreview`
forwards its dense/mesh objects, and `SparsePipeline` writes them as
`densePreview`/`meshPreview` in the saved diagnostic JSON. Downstream checkpoint
manifests refer to that exact file; no diagnostic whitelist discards these keys.
`PreviewUiSummary` intentionally omits diagnostics only in its display projection,
not in saved or published evidence.

The report reader accepts both native `dense`/`mesh` sections and saved Android
`densePreview`/`meshPreview` sections, as well as direct result objects and
standalone diagnostic objects. Conflicting native/preview aliases or ambiguous
direct/enclosed records fail validation. A missing historical diagnostic object
is unavailable. An explicit `available=false` surface object must not retain
measured view, cleanup, triangle-limit or topology-admission fields.

The fixed licensed three-photo HOST fixture starts a new UUID project/root and
retains the immutable published preview in `before-reopen-preview.json` and
`after-reopen-preview.json`, plus the hash-bound artifact bytes. Its console
measurement summary is intentionally smaller; inspect the retained preview for
`densePreview.lossDiagnostics` and `meshPreview.lossDiagnostics`. Before treating
counts as a fresh full evaluation, check `geometryReused=false`, zero resumed
depth maps, diagnostic availability and each per-view coverage field. No new
upload hook, artifact path or private-photo export is needed.
