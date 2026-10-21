# One missing diagonal: measured experiment rejected

**Decision: REJECT for the default pipeline; production unchanged.** Allowing exactly one finite-zero diagonal in an otherwise observed3×3 normal neighborhood reduced strict crossings, but lost surface coverage and substantially increased nonmanifold edges. More normal support/fusion was not a quality win. No further normal-support relaxation is justified by this result.

| Same frozen input | Original | Experimental8-of-9 |
|---|---:|---:|
| Supported recomputed normals | 1,014 | 1,538 |
| Fused observations | 271 | 400 |
| Faces | 6,741 | 6,711 |
| Native strict crossings, complete | 6,190 | 5,742 |
| Nonmanifold edges | 166 | 257 |
| Boundary edges | 4,873 | 4,876 |
| Projected truth coverage | 46.8165% | 46.7490% |
| Common-support absolute depth residual p95 | 0.04682731 | 0.04665260 |

Common support contains137,719 truth pixels;260 previously covered pixels were lost and61 gained. The small common-support residual improvement is in fixed synthetic-fixture depth units, not meters or scanner accuracy certification. Among common pixels15,533 improved,10,282 worsened and111,904 were unchanged. Native crossing counts are complete; the independent external checker reported **NOT_RUN_BUDGET/unknown for both** under its unchanged default budget. Do not substitute that unknown result with zero or silently expand limits.

## Why this bounded candidate was tested

A read-only reader decoded the already admitted snapshot, reproduced all6,867 original normal-validity values and early-return totals, and measured actual9-position depth/confidence/jump masks. Of1,529 complete-nine windows, original support was only1,014. Another1,025 samples had complete center/cardinals and exactly one missing diagonal;1,005 passed the unchanged jump/confidence checks on every observed neighbor. All1,005 originally started a new accumulator. This was evidence for a controlled experiment—not evidence that missing corners were safe.

The candidate skipped at most one diagonal whose depth was exactly zero and whose unavailable-cell confidence was finite in[0,1]. Every remaining observed location still passed original depth/jump/confidence/PCA/radius and downstream pair/anchor/visibility guards. It added no point, no replacement normal and no filled geometry. Missing center/cardinal, multiple missing diagonals, negative/nonfinite depth, observed discontinuity, zero observed confidence and malformed data remained rejected. Among the1,005 eligible windows,524 gained supported normals;480 remained ill-conditioned and1 sparse-support. The experiment did not simply accept all eligible windows.

## Regression and input gates

-20 named synthetic guard/control groups passed, including observed occlusion/thin layers and ill-conditioning. Hidden geometry existing **only** in an unavailable corner is not observable from the same eight plane samples; no rejection guarantee is claimed for that case.
- Both unchanged downstream surface/fusion test executables passed.
- The original evidence suite intentionally rejects one missing diagonal. A separately named experimental contract copy changed **only that one assertion** inside its three-scale loop to require support with exactly eight real observation IDs; all other assertions were retained. This is explicitly **not** an unchanged original evidence-suite pass.
- All1,529 actual complete-nine normal outputs, and all originally supported normal outputs, were bit-identical to the exact original adapter reference. Each original validity/camera/pixel matched admitted StageC evidence. New support was restricted to the measured one-diagonal subset.
- Direct replay used the same snapshot and original native export functions. All570 named input pins remained unchanged. Original engine/shim and production sources were immutable. Candidate cache semantics were never used; this engine must not be applied to persisted production caches or the full coordinator.

## Reproduction without duplicate artifacts

This continues the published `fusion-telemetry` diagnostic at commit142bb0baebcea71662714e64aa635f08f4b1fd75. `shared-source-refs.json` identifies exact existing snapshot/replay/secure helpers rather than duplicating them. Copy those files to the corresponding new external diagnostic directories before preparing canonical pinned configs. Configs, native binaries, snapshots, full mesh JSON/PLY and indexed sample rows stay external, referenced by SHA256.

`neighborhood/` contains the read-only reader/runner. `candidate/` contains the one-header native patch, exact original-adapter reference, synthetic tests, actual-input bit-identity/support checker and bounded private-build/replay runner. To reconstruct `candidate_contract_evidence_test.cpp`, apply `candidate-contract-evidence.patch` to a **second private copy** of the original evidence test at its recorded source path, then copy the result under that filename; verify SOURCE_PINS.json. Never patch the production test or silently drop its old-policy assertion.

Read-only reader bounds were2GiB per process, compile/read60s and180s aggregate. Candidate bounds were2GiB per process, max2compiler jobs, private build120s, standalone compiles60s, tests30s, support/replay60s and480s aggregate, with process-group cleanup and fail-closed receipts. Source hashes bind native code independently of later docs-only commits. Neither named input pins nor this archive claim complete operating-system linker provenance.

`measurement/` contains the minimal independent measurement delta, sanitized aggregate and exact lineage hashes. It uses the original actual camera/photo mapping and a fixed gauge without surface fitting; raw direct-fuser JSON is not disguised as a coordinator result. This lineage is the trace-v2 original6,190-crossing mesh, not the older6,137/6,176 provenance or6,162 profile baseline.

## Next diagnostic, not a fix

Classify actual nonmanifold-edge incidence by source-camera combinations and endpoint observation-camera sets on these exact two meshes. Reproduce native edge totals and native near-degenerate face exclusions before interpreting labels. This can test whether overlapping camera-grid surfaces share fused endpoints without becoming a coherent surface. It does not authorize face deletion, geometry rewriting, more normal relaxation, or a replacement surface algorithm.
