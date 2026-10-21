# Rejected experimental global-field v4 — compact review archive

**Decision: reject a default switch.** Same immutable capture, poses, camera-z maps and original observations; no engine/default/cache/JNI changes. This is an observed-patch oriented signed-distance field with interpolated vertices, NOT the original verified-sample mesh and NOT a projective camera-z TSDF.

The bounded experiment compiled, passed C++ synthetic contracts, independent whole-face verification and exact repeated output. Those are execution/support checks, NOT quality acceptance. Only44vertices/24faces remained. Original truth-image coverage46.8165% fell to46/294723pixels (0.0156079%);137933previously covered truthpixels were lost and none gained. On the same46common pixels, p95 residual worsened0.0439574→0.0453097. The candidate has0nonmanifold edges but **1nonmanifold vertex**: edge-only diagnostics are insufficient. The complete compact metric fields and every incompleteness status are in `evidence/quality-summary.json`; original native crossings6190 are separate from budget-limited external crossings and candidate native audit is unavailable.

## Included

- Exact reviewed v4 C++ header/replay/tests, bounded Python runner and independent verifier under `source/` (not reformatted).
- Compact terminal evidence, explicit timing/storage scopes, artifact hashes and shared snapshot/decoder references.
- Preserved v3 failure summary and source reverse deltas. Applying `history/*.v4-to-v3.patch` to copies of the corresponding v4source files recovers the v3 versions; unchanged C++ hashes are recorded. v3 stopped before real replay because its strict floating orientation primitive was not invariant at a near-boundary point. v4 retains that fixture and uses exact rational orientation on represented projected IEEE coordinates, with no enlarged epsilon or geometry change.

## Deliberately omitted

Raw meshes, full observed-patch/provenance arrays, frozen snapshot/decoder duplicates, binaries, private machine-path configs, full input pin lists, camera/photo metadata and large reports. The exact internal artifacts remain hash-addressed by the summaries. This compact archive alone is not a standalone reproduction dataset. Reproduction requires the original restricted inputs/config and explicit authorization; do not regenerate different depths/poses and call it the same experiment.

## Constraints retained

Pitch is the frozen sample projected stride-footprint median; truncation2pitch; no adaptive coarsening. One contribution per camera, no supportingViews multiplication; conflict/opposed evidence excluded; only admitted complete observed cells. Shared global six-tetra extraction and shared edge vertices. Whole-face support means one original triangular camera-ray cone plus ±.25pitch plane slab; it does not mean exact source-plane coincidence. Unknown gaps are not capped. Exact-zero lattice endpoint degeneracy fails the whole candidate, and no general plane/grid-invariance guarantee is claimed.

Actual C++ Workspace28,070,872bytes plus65,536stack/meta allowance=28,136,408bytes below32MiB. Input/decoder and independent Python verifier are separate; this is NOT process RSS. Runner300s aggregate/2GiB child bounds and process-group cleanup are fixed; each independent verifier has30s. The7.7146s replay-receipt total includes compilation, linkage, tests, two replays and two verifiers, NOT candidate runtime. The3.7131s measurement run is also not reconstruction runtime.

## Interpretation, not a repair claim

A plausible cause of sparse output is conflict invalidation from derivative-based triangle normals followed by the eight-observed-corner requirement; only138candidate faces reached final rejection/acceptance accounting (24accepted,114rejected). These aggregate observations do not isolate causal loss stages. A differently defined projective field would be a new algorithm needing separate design, exact instrumentation parity, review and authorization—not a justification to loosen current parameters. No follow-on algorithm is implemented in this archive.
