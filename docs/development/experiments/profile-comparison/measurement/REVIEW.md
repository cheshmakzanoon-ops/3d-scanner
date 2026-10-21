# Separate geometry/texture-only profile measurement adapter — NOT EXECUTED

Owner authorization and independently reviewed terminal restored PASS required. No read of running or failed capture has been performed. This package only parses AST during preparation; `prepare.py` extracts the previously reviewed projection algorithm without touching captures. No Gradle, native execution, production edits, or geometry changes.

## Inputs and execution
Use the future successful capture root (currently planned `/tmp/android-relief-profile-pair-evidence`, but failed roots must not be reused), exact clean full commit, terminal receipt SHA and comparison manifest SHA from owner-reviewed terminal evidence. Do not infer completion from file existence. Runner rejects non-PASS/restoration-incomplete and pins every captured artifact before/after. Output directory must be new.

```
/path/to/reviewed/python -B /tmp/rf-profile-pair-geometry-measure-draft/run.py \
 --repo /workspace/scratch/6b683a119bde/3d-scanner \
 --evidence /path/to/actual-successful-evidence \
 --python /workspace/scratch/6b683a119bde/qaenv/bin/python \
 --commit FULL_CLEAN_COMMIT \
 --receipt-sha256 TERMINAL_RECEIPT_SHA \
 --comparison-sha256 COMPARISON_MANIFEST_SHA \
 --output /tmp/NEW-measurement-evidence
```

60s wall / 60s CPU / 1GiB AS / single BLAS thread; timeout means FAILED, no budget escalation. Parent records full pre/post files including Python binary, venv config and NumPy/OpenCV/Pillow Python/native libraries; no installation/download. Parent runner itself is stdlib only. Parent and child explicitly reject optimized Python before assert-based integrity checks. Child uses a fresh output-local pycache prefix (-X and environment) and -B, so it cannot read stale unpinned bytecode from existing source caches. No subprocess except git read checks and bounded Python measurement. Sources pinned in source-pins.json, and complete external package inventory in SHA256SUMS.

## Actual mapping, not fabricated core evidence
`alignment.py` verifies RFAC magic/version/length/payload SHA and parses captured cameras according to pinned Kotlin BinaryCodec/StageManifestCodec/AlignmentCheckpointCodec. Checks full payload consumption and finite doubles. This is not a reimplementation of every production semantic acceptance check: successful host receipt remains mandatory. Camera photo IDs map to fixture filename only via captured original input SHA. Alignment SHA must agree with selector proof. Exact actual camera records included in report.

No synthetic `result.json`, sparse.ply, dense.ply, or pretend strict-evaluator capture is created. `measure.py` is explicitly a NEW adapter for direct-result.json + actual alignment.rfac. It imports existing strict evaluator's immutable truth pins but DOES NOT invoke its main or claim its PASS.

## Methods and retained differences
- Fixed gauge unchanged: accepted view01 world-to-camera pose, scale = known view01–02 baseline / actual accepted camera-center baseline. Never fit mesh to truth.
- Exact unmodified native mesh PLY positions/indices used by existing load/topology/surface_samples; 4096 area-weighted seed84001 samples, in-domain and extrapolated vertical error separately.
- `projection.py` copies raster/truth loop from `/tmp/rf-relief-standard-visual-v2/coverage-bound.py`; only function inputs, separate caller provenance, and defined null result for empty covered pixels differ. Uses 800×600 integer pixel centers, perspective-correct zbuffer, prescribed K, 45 ray-intersection steps and exact finite-domain analytic mask. No outlier threshold, image/color mask, dilation, smoothing or clipping substitution. Near-plane crossings reject.
- Every native direct-result JSON field retained, including topologyIncomplete and stage counters; complete recursive JSON differences retained without excluding IDs/settings/runtime fields. No normalization that hides source selection or geometry differences. Both exact artifact hash inventories retained; reference selections and executed source adjacency checked equal. PLY header counts remain raw counts.
- Existing topology default 5000-face intersection budget unchanged. `NOT_RUN_BUDGET`/null remains unknown; watertight_topology alone does not imply self-intersection clean. Never quality PASS.
- Coverage is projected image footprint, not 3D completeness. Covered wrong-depth triangles still count in coverage; independent depth residual quantiles reported. Native sample counters have different denominators and must not be subtracted as image populations.
- Synthetic-only; no physical scale/held-out validation. Comparison is entire execution-profile bundle, not single-factor attribution or shipping/controller qualification.

## Review limits
Static AST and source review only, no measured outcomes yet. Independent reviewer should inspect decoder offsets, extracted loop, manifest binding, runtime pins and receipt checks BEFORE authorizing execution. Capture host harness source must be independently pinned/approved via terminal receipt; this package does not certify the host run itself. Changes to rerun harness may require review but do not justify relaxing measurement safeguards.

## Separate schema-2 contract (not a relaxed schema-1 pass)
Only comparison.schema=2 and exact qualification GEOMETRY_TEXTURE_CAPTURE_ONLY_NOT_REFINED_CACHE_QUALIFICATION are eligible, alongside new terminal PASS/EXIT_0/restored receipt. Original full-cache experiment failures remain FAILED; this report does not reinterpret those artifacts. Refined cache is NOT qualified here.

Compatibility requires surfaceCheckpointStatus CAPTURED and all original six artifacts, with actual dense.surfaceCheckpointAvailable=true. VerifiedRefinedCompact requires status NOT_AVAILABLE_BY_REFINED_POLICY and exactly five artifacts (no surface checkpoint file or symlink), actual surfaceCheckpointAvailable=false, surfaceCheckpointWritten=false, depthCheckpointWritten=0. Both profiles retain actual mesh+texture payloads and full native fields. Status is GEOMETRY_TEXTURE_MEASURE_ONLY_NOT_CACHE_OR_QUALITY_PASS.

The matching owner contract is /tmp/rf-relief-profile-geometry-draft (independent source review required). Existing strict measurement package stays untouched. geometry-scope-delta.diff is the exact code delta from its independently reviewed d458 inventory. All mathematical algorithms, RFAC parsing, projection, resource bounds and guard fixes remain unchanged.
