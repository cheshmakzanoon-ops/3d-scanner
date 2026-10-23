# Evaluation-only fixed-point support ceiling

At 1% of the supplied reference diagonal, **1360 / 3734 (36.4221%) observable reference samples cannot be reached** by any mesh built from the frozen 4855 indexed points with triangle edges <= 2 rho. The conservative sampled completeness ceiling is **63.5779%**, versus baseline **33.8779%**. Connectivity might recover some of the remaining 29.7001 percentage points; this is only headroom, not an achievable promise. Missing point support is a fundamental blocker to full accurate visible-surface recovery, independently of manifold/connectivity improvements. This diagnostic alone does not prove the precise upstream cause of absent support (texture, matching, rejection, etc.).

## Proof
For triangle x = la*a + lb*b + lc*c with nonnegative barycentric weights summing to one, |x-a| <= lb|b-a| + lc|c-a| <= 2rho. If a reference q lies within epsilon of the triangle, |q-a| <= epsilon+2rho. Therefore nearest distance from q to ALL indexed points greater than epsilon+2rho proves q cannot lie within epsilon of ANY legal triangle. We enlarge the threshold by 2.82e-12 numerical slack. No support, witness, topology or orientation constraints are required: ignoring them only weakens the bound. Including all unknown-normal and unreferenced points is deliberately generous.

This is a finite frozen-sample surface-distance bound, not a continuous-area theorem, silhouette/projected coverage bound, or evidence that reachable samples have feasible triangles. No baseline-edge-limit assumption is made. It does not measure which baseline misses are individually irreparable. Fixed-point restrictions are essential; moving/adding vertices or changing the radius invalidates this bound.

## Measurements
- rho: 0.04965026414510365; maximum edge 0.0993005282902073.
- Oracle-scale reference diagonal: 2.8178005387220617; 1% tolerance 0.02817800538722062.
- All 4855 points retained, including 2363 unknown-normal points.
- 9172 raw verified observations, 9171 accepted source memberships (one below native acceptance threshold; not excluded from the separate raw-observation comparison).
- Frozen evaluator generates 6000 deterministic area-uniform samples; its unchanged >=2-view mask retains 3734, excluding 2266 unobservable samples.
- Completeness ceilings at 0%, 0.5%, 1%, 2%, 5% diagonal: 57.1773%, 60.4713%, 63.5779%, 69.6572%, 84.4403%.
- At 1% unreachable / sampled by observable plane: -X 365/740; +X 372/803; -Y 0/1021; +Z 623/1170.
- Exact cKDTree independently parsed native accumulators: distances identical to brute-force numpy (max difference zero). Closest threshold decision margin 3.70199e-5.
- Separate hypothetical raw-9172-observation comparison at the SAME fixed radius yields identical unreachable counts at all five thresholds. This supports absent raw support, rather than fusion alone, as the obstruction captured by this coarse bound. Raw observations are not substituted into the actual front.

## Reproduction and provenance
Run from /workspace/scratch/6b683a119bde with `source host-validation/activate.sh`, then Python -B on diagnose.py, check.py and raw-observation-check.py here. Main run bounded by prlimit AS=1GiB, CPU=120s, file size=16MiB; finished in ~3.4 seconds. No installs, solver calls, whole mesher reruns, truth feedback, new dataset/render, ICP, crop or fitted scale. All outputs outside checkout. Frozen evaluator imported with bytecode writing disabled. Input hashes unchanged before/after.

receipt.json binds exact absolute input paths/hashes, script hash and sample-distance CSV hash. independent-check.json binds receipt hash. SHA256SUMS binds all output artifacts. Initial source-membership-count assertion failed before numerical measurement; corrected to distinguish 9172 observations from 9171 memberships, then successful bounded evaluation. This did not mutate inputs or execute any solver.
