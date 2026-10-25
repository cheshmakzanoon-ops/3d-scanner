# Originating dense and surface loss diagnostics

`dense.lossDiagnostics` and `mesh.lossDiagnostics` are additive schema-1,
originating-call counters. They do not change thresholds, depth sampling,
refinement profiles, normal estimation, fusion, triangle selection or export
geometry. Existing JSON fields, C/JNI signatures and checkpoint wires remain
unchanged. C++ result structs grow and their consumers must be rebuilt together.

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

## Resource and verification contract

Storage is fixed independent of image dimensions. On the current 64-bit host,
`DenseLossDiagnostics` is 4,176 bytes and `SurfaceLossDiagnostics` is 2,720 bytes.
Static assertions bound each and conservative simultaneous copies below half the
existing 64 KiB fixed scratch reserve. No admission/default budget is raised.
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

## Controller and HOST retention

`NativeResultJson.decode` retains the complete native object. `DensePreview`
forwards its dense/mesh objects, and `SparsePipeline` writes them as
`densePreview`/`meshPreview` in the saved diagnostic JSON. Downstream checkpoint
manifests refer to that exact file; no diagnostic whitelist discards these keys.
`PreviewUiSummary` intentionally omits diagnostics only in its display projection,
not in saved or published evidence.

The fixed licensed three-photo HOST fixture starts a new UUID project/root and
retains the immutable published preview in `before-reopen-preview.json` and
`after-reopen-preview.json`, plus the hash-bound artifact bytes. Its console
measurement summary is intentionally smaller; inspect the retained preview for
`densePreview.lossDiagnostics` and `meshPreview.lossDiagnostics`. Before treating
counts as a fresh full evaluation, check `geometryReused=false`, zero resumed
depth maps, diagnostic availability and each per-view coverage field. No new
upload hook, artifact path or private-photo export is needed.
