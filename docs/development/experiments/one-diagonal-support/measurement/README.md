# Direct-fuser one-missing-diagonal experiment: REJECT candidate

Actual same frozen snapshot and original camera gauge. This measurement is posthoc synthetic evidence, not Android/full-controller/physical/cache qualification. No production edit was made. Candidate source/test/native build details belong to the accompanying native archive.

| Actual metric | StageC original | Candidate |
|---|---:|---:|
| Vertices / faces | 5753 / 6741 | 5624 / 6711 |
| Native intersections (complete flags) | 6190 | 5742 |
| Nonmanifold edges | 166 | 257 |
| Boundary edges | 4873 | 4876 |
| Truth-image coverage | 46.8165% | 46.7490% |
| Common-support depth p95 | 0.04682731 | 0.04665260 |

**Reject candidate; preserve current production behavior.** Intersection count improves but nonmanifold/boundary topology worsens and coverage loses260 versus61 gained truth pixels. Both are defective open meshes. The actual original baseline is trace-v2/StageC6190, not earlier profile-pair6162.

Common-support comparison uses exactly137719 truth pixels covered by BOTH outputs, no residual filtering.15533 pixels improve,10282 worsen,111904 unchanged. Median paired change in absolute residual is0. The small p95 gain cannot establish overall improvement, and this intersection mask is conditioned on both outputs—not held-out reference support.

Both external5000-face intersection checks remain null/NOT_RUN_BUDGET. Raw native topologyIncomplete=false for both and their native counts are complete; never mislabel external unknown as clean. Projection footprint is not3D completeness. Synthetic units are not physical units.

## Reproduce source, not invented evidence
Copy projection.py, measure.py and run.py from the committed basePath/baseCommit in source-pins.json to a new external directory. Verify base hashes, apply `patch -p1 < profile-adapter-to-direct-fuser.patch`, verify directFuserSha256. All three exact targets were reconstructed and byte-checked externally. No duplicate full adapter sources are archived.

Projection delta only returns already-computed depth/truth/distance arrays; original rasterization/gauge/analytic truth/sampling methods remain unchanged. measure.py explicitly consumes standalone native mesh.json+PLY and actual original captured cameras, not a fabricated coordinator result. Native complete-topology flags are required; external check budget is unchanged. Common support uses truth AND finite original AND finite candidate depths, reports both residuals on identical pixels plus paired residual change and lost/gained coverage.

The exact executed config and source/runtime pin inventory stay external, hash-bound here. To rerun, independently review/rebind machine paths, clean commit, original trace-v2 capture/result/photoSHA, StageC exact replay, and terminal candidate scoped receipt with identical snapshot hash and raw mesh triplet hashes. Candidate terminal status must be EXPERIMENT_ONLY_ONE_MISSING_DIAGONAL_REPLAY_READY_NOT_QUALITY_ACCEPTED. Do not fabricate missing original evidence. Review actual config/source pins before authorizing the existing bounded runner; no command here authorizes native builds or production changes.

Runner retains fresh pycache, -B, optimized-Python rejection, loader injection rejection,60s CPU/wall,1GiB address space and single BLAS thread with pre/post hashes. Actual measurement succeeded4.5526s, inputs unchanged; status remains DIRECT_FUSER_MEASURE_ONLY_NOT_QUALITY_PASS. Exact raw measurements and receipt hashes are in aggregate-measurements.json.

Aggregate preserves every native scalar and all measured summaries. Large native arrays are omitted with length and canonical JSON hash, while raw file hashes bind original exact bytes. Full JSON/geometry/config and machine paths remain external; no binary/image/checkpoint/large arrays are published. Source hashes and summaries do not replace provenance review or complete physical evaluation.
