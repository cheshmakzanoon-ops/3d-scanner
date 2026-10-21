# Proposed offline conflict-selection experiment — NOT AUTHORIZED / NOT RUN

## Question and evidence
All 6,137 strict crossings in the fresh COMPACT baseline are between source-camera grids, but within-grid counts are zero. Can deterministic selection among the existing observed faces remove conflicting cross-view layers without unacceptable loss of observed projected coverage? This tests a surface ownership strategy, not a correction to depth/fusion and not a production solution.

## Scope
- Consume only the bound actual fresh-baseline checkpoint/PLY/JSON and the verified exact native conflict graph. No regenerated depth, invented vertices, hole filling, edge flips, reorientation, coordinates, cameras, normals, thresholds or production code changes.
- Every candidate is a subset of original indexed triangles with their unchanged vertices/provenance. Use original native confidence/support metadata; never use analytic fixture truth to choose faces.
- Separate offline outputs only. Original captures, graph, engine/JNI and source remain immutable. Require root-approved frozen source/config and reviewer sign-off before execution.

## Predeclared small candidate set (no sweep or tuning)
1. Three source-camera-only subsets as diagnostic controls: each preserves one original view's observed mesh. These demonstrate the coverage cost of selecting a whole view and must not be called fixes.
2. One deterministic greedy independent set of the verified crossing graph: visit faces by descending minimum vertex distinct-camera observation support, then descending minimum original vertex confidence; resolve exact ties by original source-camera index then original face index. Keep a face only if none of its already selected neighbors conflicts. Every graph-isolated face is retained. This criterion uses measured evidence only; its bias toward whichever source wins ties is explicitly a limitation.
3. Original complete baseline as the unchanged reference. No iterative parameter adjustment after results.

The greedy ordering is a proposed heuristic, not an optimized or guaranteed maximum-area independent set. Total surface area is not a reliable standalone objective because overlapping views double-count area. Do not declare quality based only on intersection deletion.

## Validation / measurement
- Require exact original vertex/index subset membership and unchanged coordinate/color bytes. Re-run the same native topology predicate at its unchanged limits on each exported candidate, since candidate subset alters tolerance bounding box; report both frozen-original-graph conflicts and fresh native report. Any new ambiguous/strict outcomes are reported, not hidden.
- Record retained faces, vertices used, source-camera distribution, surface area, boundary edges/components/nonmanifold edges, complete strict crossings and ambiguous pairs. Budget exhaustion blocks a completeness claim.
- Use the existing frozen fixture evaluator only after all candidates are frozen: same UUID→photoSHA binding, one-shot gauge, fixed camera projections, same fixed domain/grid/sample definitions and seeds. Measure projected union coverage across cameras and the same vertical-residual percentiles/out-of-domain fraction; do not use this truth to select candidates or claim physical accuracy.
- Freeze exact metric implementation/source, versions, domains and receipt hashes before executing. If a metric needs unavailable exact camera order, stop rather than guess it.

## Merit criteria and stopping
This is descriptive/Pareto evidence, not a new quality gate. A candidate merits further investigation only if complete strict crossing count falls and projected coverage/error are not worse than baseline on the existing measured definitions; report raw deltas, never retrofit numeric tolerances. If every candidate trades crossing removal for lower coverage or higher residuals, stop and document that face deletion alone does not solve the problem. Any candidate remaining above zero strict crossings is not a clean-surface result. Even a Pareto improvement needs a held-out fixture/test and explicit production design review before integration.

## Alternative: measure why fusion rejected candidates
Stronger causal evidence would capture exact native DenseView calibration/poses plus accepted depth maps and original inputs, then reproduce unchanged fusion while counting original neighborhood-normal support and rejection reasons (same-view, unsupported/incompatible normal, radius, visibility, anchor/pair metric). That needs a separately approved evidence-only test seam; current checkpoint sample normals are not the recomputed fusion normals. Do not infer gate failure from the 278/6865 fusion rate or relax a gate before this measurement.
