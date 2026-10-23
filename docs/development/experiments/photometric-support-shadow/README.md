# Rejected photometric support studies (source-only archives)

Neither study changes production matching, accepts geometry, or establishes scan quality.
Both policies were frozen before their measurements. Archives retain the original
sources, policies, results and limitations without compiled binaries or images.

## Hard center-connected support

The 7,776 fixed patch evaluations yielded 1,021 full-window NCC positives. Of 693
wrong positives, 106 were shadow-vetoed and 587 were UNKNOWN. All 328 correct
positives were UNKNOWN. Treating UNKNOWN as rejection would remove every correct
positive. This diagnostic was not promoted. Repeated/flat seed cases duplicate;
the report discloses the limited roll coverage and does not call counts independent.

The archive contains its own README with exact sibling-directory layout,
recorded compiler/NumPy versions, source hashes, and binary-hash portability limits.

## Soft reference-bilateral support

This separate, newly frozen trial evaluates full fixed depth/plane curves on 324
pixel decisions. The uniformly weighted proposal-search comparator accepted zero
correct and one wrong hypothesis; bilateral weighting accepted five correct and
17 wrong. UNKNOWN increased from 36 to 40. Every label is photometric-only:
independently solved source depths/reciprocity were NOT_RUN. This trial failed its
predeclared criterion and was not promoted.

The post-hoc oracle audit (never supplied to candidate selection) verifies genuine
slanted-plane warps, with NCC above .993 and geometric roundtrip error below 1e-12.
The uniform comparator's best slanted depths were correct but failed the unchanged
uniqueness threshold. Thus accepted-recall comparisons are weak; these results do
not establish that bilateral weighting is generally invalid. The archive records
all limits, duplicate single-plane roll controls, sources and replay commands.

Reference-only bilateral weighting was studied from the mathematical mechanism in
[COLMAP](https://github.com/colmap/colmap/blob/main/src/colmap/mvs/gpu_mat_ref_image.h)
and [OpenMVS](https://github.com/cdcseacave/openMVS/blob/master/libs/MVS/DepthMap.h).
These diagnostic implementations are original; no OpenMVS implementation was copied.
The fixed sigma/support choices are experimental, not claimed source defaults.

## Integrity and replay

Check SHA256SUMS before extracting into scratch space. The compressed packages total
less than 80 KiB and exclude executable binaries and regenerable full cost curves.
Replay overwrites result files, so preserve recorded copies and work outside the repo.
Per-package hashes bind recorded outputs; different toolchains can change compiled
binary hashes and floating-point details. Do not erase differences to claim parity.
