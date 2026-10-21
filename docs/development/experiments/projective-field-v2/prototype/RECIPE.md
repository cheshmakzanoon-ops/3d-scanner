# Canonical projective camera-z field v2 — SOURCE PREPARATION ONLY

NO compile/tests/replay have been executed. Exact freeze needs independent source/bounds/runner review followed by root execution authorization. Old oriented-plane v4, its rejected quality result, failure-v3 evidence and combined archive are unchanged. No production, cache, JNI, Gradle or default edits.

## Estimator

Explicit `experimental_canonical_projective_camera_z_field_v1` representation, not verified original vertices. Original source-cell confidence/four-corner/float-map depthjump/originalXYZ edge/area gates and shorter original diagonal unchanged. Canonical camera lookup uses admitted original integer-pixel triangles and authoritative original float camera-z. Shared boundary chooses smallest admitted patch ID. Perspective-correct interpolation is reciprocal barycentric depth, NOT linear-z or Euclidean range. Projected arithmetic order is explicitly fx*(cameraX/cameraZ)+cx. Computed nonfinite arithmetic fails; finite camera-z<=.01 is UNKNOWN.

Same origin/pitch median, mu=2pitch, eight-valid-corner/six-tetra extraction, shared edge cache, scalar-gradient winding, exact-zero whole-run failure, single-original-patch wholeface ray-cone+±.25pitch slab witness, scratch/table/output caps. No patch union, no fills, no adaptive coarsening.

At each candidate lattice point all cameras queried once: unknown=>none; r<=-mu=>none (occluded); -mu<r<=mu=>r/mu; r>mu=>+1. At least one actual near-band camera required. Front-only nodes remain absent. Votes quantized to existing fixed float per-camera storage then summed as doubles in fixed camera order; equal camera weights, no supportingViews multiplication. No triangle-normal, mixed-sign, ray-angle, reciprocal-UNKNOWN or inherited residual-spread field veto. Triangle normals remain only original source-patch/witness geometry. Unknown-camera data never become a negative vote.

A single field cannot guarantee thin-sheet separation, resolve contradictory depths, recover unseen sides or remain unbiased. Front votes can suppress an observed surface; noisy mixed signs can average. No quality acceptance is implied. True-support coverage/common-support residuals plus vertex-link, winding and complete/budget-explicit crossing diagnostics are required if execution eventually succeeds. v4 was overwhelmingly rejected, and provides no evidence this new estimator is good.

## Conservative allocation / arithmetic proof obligation

Implementation uses an intentionally loose **source pixel rectangle × camera-z depth interval** frustum rather than the proposal's tighter Minkowski bound. The rectangle contains the canonical triangle, and the positive harmonic depth lies between its source depth extrema up to a predeclared conservative gamma64 enclosure for fixed binary64 arithmetic. It is allocation-only; final lookup/field/witness thresholds do not grow.

`bandBounds` implements outward-rounded interval actual inverse of stored R (not approximate transpose), expanded depth/residual rounding preimages, inverse floating projection steps, and a transform-error envelope using checked lattice magnitude <=(1e9+2)*pitch and gamma64. Source pixel bounds include a gamma64 allocation envelope for the constant-size cell/triangle classification arithmetic. Gamma64=64u/(1-64u), u=2^-53; it is not adjusted from observed metrics. Interval endpoint operations each expand by nextafter; nonfinite values or determinant enclosure including zero fail. Binary64 IEC559 and FE_TONEAREST are required. Compiler fixes -fno-fast-math -ffp-contract=off. Reviewer must check these conservative bounds against exact operation order; any missing enclosure blocks execution, never gets concealed by successful tests.

This overbound may enumerate more cells and hit unchanged caps: 40M operations,60s algorithm deadline,65,536nodes/32,768cells/65,536edge entries,100kpatches,40kvertices/80kfaces; static actual-size Workspace+64KiB <=32MiB. Inputs/decoder and separate Python verification excluded from algorithm scratch, not from honest process-RSS reporting. No fallback pitch, dropped evidence or retry on budget failure.

## Counters — new algorithm only

Separate `counters.json`; **not v4 causal attribution**. Original v4 tracing proposal remains NOT_RUN. Allocation visits partition into existing-node visits, no-near-band visits and newly admitted nodes. Unknown/behind/near/front camera observations partition all per-camera queries (including repeated rejected lattice locations; they are not unique-node totals). Cells partition missing-corner versus valid; successful valid cells have exactly6tetra each partitioned all-one-sign/crossing; face candidates partition area-rejected/witness-rejected/accepted. Runner checks all identities and exact counter repeat. Diagnostic overhead counted by existing fixed operation/deadline rules, no unsupported claim of zero cost.

## Tests prepared, NOT RUN

Nonempty plane/slant and disconnected-gap meshes, allface witnesses and edge-winding checks, deterministic indexed output; exact±mu vote policy, front-only node absence, legitimate mixed signs, no masked/occluded vote, canonical shared-boundary identity/no duplicated camera weight, reciprocal-vs-linear depth, tolerated nonorthonormal pose allocation, finite behind-camera unknown, source invalidity, NaN vote, duplicate samples, operation/node caps, exact-zero explicit failure. These synthetic contracts do not guarantee truthful thin-sheet reconstruction. Independent Python source/witness verifier is unchanged from reviewed v4 (exact rational projected orientation), and is still bounded to30s.

## Proposed bounded runner, only after exact authorization

```
/usr/bin/python3.13 /tmp/relief-projective-field-v2/runner.py --config /tmp/relief-projective-field-v2/config.json --work /tmp/relief-projective-field-v2-evidence
```

Runner inherits reviewed StageC process-group TERM/KILL cleanup,300s aggregate,2GiB child address space,32MiB per output file, cores disabled, fresh private external work directory, child HOME/TMPDIR there. Compile only external tests and replay, run tests+independent primitive guards before actual, then two same-snapshot replays and independent wholeface verification. Any failure stops, records posthashes, no tuning/retry. Success is deterministic supported experimental geometry only, not quality acceptance. Original native engine4384 and JNI427 are pinned unchanged. Historical capture manifest/receipt and source snapshot are pinned and bound; historical APK/Kotlin records are NOT reinterpreted as current file hashes. No live APK/currentKotlin dependency is required by this native replay.

## Source-only v2 correction

The preserved unexecuted v1 freeze7262f070 omitted its intended pixel classification envelope and contained a malformed q[3] initializer. Static review caught this before any compilation/execution. v2 explicitly adds the gamma64 pixel-cell preimage enclosure using validated image dimensions, corrects all three camera interval components, and updates only runner/config paths and documentation. Exact estimator thresholds/masks/pitch/tau/extraction/witness/output caps are unchanged. `ALLOCATION_PROOF.md` states the enclosure argument and its review assumptions; no observed run is used as proof.
