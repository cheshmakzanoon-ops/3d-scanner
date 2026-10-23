# Oracle-camera dense observer: proposed first execution contract

This is a plan, not execution approval. It is separate from the actual Kotlin image-registration benchmark. All eight committed new synthetic images remain fixed; no view dropping, rerender, truth mask, camera estimation, ICP, scale fitting or parameter retry is permitted.

## Input boundary and identity

Use source commit `173dc71d7d99e97a8e2d4257b52cf7033e37a74f` and fixture manifest `c1107dd0…`. Validate all original PNG hashes and decode the exact 160×120 RGBA pixels once using the existing pinned decoder, retaining its version/hash. Derive a small explicit input file containing only the eight declared render camera rows and fixed settings, with `oracleCameraExtrinsicsInput:true`, `imageDerivedRegistration:false`, and test search bounds 3–7 / 96 planes / stride 2. Camera gauge is supplied, not recovered. Do not copy the old known-camera wrapper's ambiguous `truthPassedToSolver:false` label.

The solver process reads only RGBA/camera/settings files. It never receives fixture geometry, ray witnesses or masks. A separate evaluator may read them after output/parity sealing. Both builds receive byte-identical input files. Freeze generator/PNG/camera/config/decoder/source/patch/compiler/header hashes before measured reconstruction. Source archive is the exact complete native tree, not old ABI headers.

## Logging-only patch and ordering

The harness calls actual `reconstructDense` once, with no refinement lease, followed by `fuseDepthSurfaces` using the same settings as the known-camera API boundary. Baseline code remains unchanged. Observer changes only private read-only hooks:

1. After the existing source-plan assignment, emit the actual ordered `result.sourceViews` and settings. Do not choose a different plan or infer links from counts.
2. Enter a dedicated consistency-stage scope around the original inline consistency loop. Start one thread-local record at the original directed `for(src: sourceViews[ref])` body. Record the native reference/source/camera-pixel identities and actual `q`/projection locals. Before each existing early `continue`, set its existing rejection reason and emit exactly one record. A guard emits the successful record on normal exit; it must not evaluate any predicate itself.
3. Inside `neighborDepth`, while a consistency record is active, copy the actual native `ix/iy` **after existing std::round and bounds operations**, and the actual returned depth. Do not recompute a different nearest pixel outside the branch. Instrument the existing `relativeDifference` return value only when that call actually executes; preserve the native float conversion and short-circuit semantics (no relative-error value invented for missing depths). Copy the actual `back`, `refCamera`, `px/py` and roundtrip used by the original gate. If the roundtrip expression is captured, evaluate it once at precisely the original comparison site and pass its unchanged result to that comparison.
4. After the actual verified-sample append, emit the actual generated index, source reference pixel, fields and count of confirmations. Records include attempted, missing-depth, relative-depth, invalid-reverse, roundtrip-rejected and confirmed states. Normal consistency and continuous sampling are false in this explicitly frozen branch; the observer refuses an unexpected enabled branch rather than mislabel its evidence.
5. Reuse the three reviewed pre-face hooks: existing PreparedSource evidence after native normal/bound calculation; actual accepted source membership/weight; all accumulators/materialized vertices before faces. No new normal/depth/NCC/visibility query occurs in observer execution.

This CPU reference loop is sequential. Tag every record with run/stage/reference/source and monotonic sequence. Capture the owner thread at stage entry and refuse cross-thread hooks, nested stages or out-of-stage records. Thread-local active records prevent unrelated helper calls from being mislabeled. Never sort away evidence of different execution order. Stage exit checks attempted-record count, source-plan membership and final generated-sample accounting.

A forward depth confirmation plus its original reverse projection is NOT automatically a strict pair of source observation identities with two accepted directed records. Build that distinction in postprocessing only: retain all directed records, exact positive-depth sampled IDs and actual generated sample IDs; classify absent reverse attempt, reverse failure and missing verified endpoint separately. No made-up reciprocal edge, supportingViews-as-identity substitution, or certified same-sheet claim.

## Bounds and fail-closed behavior

Grid positions are x=3..155 step2 (77), y=3..115 step2 (57), all8 views: 35,112 possible sites. At most3 selected sources yields 105,336 directed attempts. Fixed binary witness records are at most192 bytes (20,224,512 bytes total); no cost-volume dump. Prepared/membership/accumulator rows are bounded by generated samples, normal-support IDs by nine per sample. Shared sidecar cap128 MiB accommodates these finite expansions; aggregate diagnostic case output256 MiB is a scratch bound, not a Git size allowance. Native production vertex/face/point limits remain unchanged. All other explicit limits are in config.json.

Every overflow, cancellation, unexpected thread/stage or identity/parity mismatch aborts the entire arm; preserve refused evidence with no success receipt. Bounded process-group cleanup and actual native AS/CPU limits apply. No retries with raised bounds or relaxed thresholds to make output pass.

## Required parity before using evidence

Compare baseline and observer input bytes, full depth/confidence arrays, ordered generated samples and dense points, actual source plans and consistency counters, all serialized DenseResult diagnostics relevant to this branch, final SurfaceMesh fields/provenance/quality flags, JSON, PLY and GLB. Serialize fields explicitly with float/double bits, never raw C++ padding. Verify all original observations and all accepted/unaccepted/attached/unattached IDs. Re-run original native normal helper on retained maps independently of hooks and audit actual source-map/projection/normal-neighborhood identities and ordered pre-face arithmetic. No full support certificate is admitted solely because this logging parity passes.

Only after parity passes, use the unchanged committed cuboid evaluator on sealed output for all eight whole-visible projected coverage/CDF and reference-to-candidate/candidate-to-reference distance metrics in the oracle-camera gauge. Preserve native intersection budget-completion flags; no complete-intersection claim on an incomplete check. Truth geometry is evaluator-only; no geometry change is proposed by this experiment. Zero verified samples, failed mesh, or poor quality is an honest terminal outcome.
