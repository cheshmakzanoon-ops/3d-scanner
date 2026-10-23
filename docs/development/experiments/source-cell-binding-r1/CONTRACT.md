# Source-cell binding contract (frozen before first measurement)

This is a read-only derivative of oracle observer `2455a274…`, using source `173dc71d7d99e97a8e2d4257b52cf7033e37a74f`. All eight supplied images, oracle cameras, fixed search bounds and settings remain identical. Geometry/masks remain evaluation-only. No image-derived registration, global front or quality improvement is claimed.

## A: Actual native decisions

Additional private surface.cpp hooks begin immediately before the native face set and end before post-face progress/cleanup. The cell hook records actual camera/x/y/pixel IDs and imageVertices accumulator IDs immediately before the original missing-corner condition. Missing cells finish before the original continue. Complete cells copy original depth floats, computed diagonal lengths and the actual selected diagonal. Each original face-lambda invocation binds local corners before entering unchanged validTriangle logic.

Triangle hooks wrap actual evaluated conditions, once and in original order: repeated/invalid ID aggregate, depth aggregate, each of the three short-circuit edge comparisons, area comparison, duplicate insertion result. Computed max/min depth, footprint, edge limit, normal and centroid are copied only after their original calculations. The original winding comparison is logged once, only after duplicate suppression. Duplicate/refused proposals have winding NOT_EVALUATED (availability flag zero and zero unavailable words); raw-lift winding is never presented as native winding. The tracer adds no projection, norm, NCC, normal-estimation or consistency query to solver execution. All fields use explicit little-endian words, not struct padding. Stage/thread/count/state mismatches abort.

Fixed cell record: 40 uint64 words / 320 bytes: sequence,camera,x,y; four pixel IDs; four signed accumulator IDs; four float-depth bit words; complete flag, diagonal03 flag, two double-length bits; twenty reserved zero words. Missing-corner cells have zero unevaluated depth/diagonal words.

Fixed attempt record: 32 uint64 words / 256 bytes: sequence,cell,camera; three local corner IDs; three accumulator IDs; three float-depth bit words; evaluated predicate mask, true predicate mask,status; maxZ,minZ,footprint,limit; normal XYZ; centroid XYZ; winding flip, native face insertion index, winding-available flag; four reserved zeros. Status 0 accepted, 1 invalid/repeated ID, 2 depth, 3 edge, 4 area, 5 global duplicate. A true predicate bit describes a rejection condition, not support confidence.

## B: Explicitly derived observed support

This is NOT a native per-camera raw triangulation. Native diagonal/edge/area choices use fused accumulator positions, while depth-jump uses original map depths. Final fused faces and sourceCamera do not alone bind original depth surfaces.

For each attempted triangle, retain its original accepted sample IDs, native decision and source camera, including every refusal. A derived-support candidate requires native geometric admission BEFORE global duplicate suppression (native status 0 or 5); duplicate camera evidence is retained. Lift the same logged diagonal/corners to original verified XYZ, never fused XYZ. Require finite positive camera depth > .01, original pixel reprojection within .25 pixels, nonzero exact projected area and 3D cross product. Use exact rational arithmetic on represented input floats for these additional raw-lift checks; they are adapter checks, not native acceptance modifications. Refused lifts remain indexed. Original missing-corner holes are not filled.

Derived orientation uses the raw triangle cross product and exact algebraic camera center -R^-1 t. The camera-facing sign must be nonzero; reverse corner orientation if negative. This is an explicit triangle orientation constraint, NOT a measured/fabricated point normal, nor native winding/transpose-center bit parity. All pre-face points, immutable original samples, attributes, normal evidence and memberships remain available unchanged, including unreferenced points and unsupported normals.

Strict reverse identities are counted separately from native directed confirmations. Native original forward attempts/statuses/source plans remain intact; no added reverse queries. No current result establishes physical sheet identity or a complete R3 certificate. Exact union/closed-boundary/orientation/visibility certificate predicates will not be weakened by this checkpoint. Incidence is not coverage.

## Bounds and publication

Eight views, width 160, height 120, native start 3 and stride 2 give at most 76 × 56 × 8 = 34,048 cells, with at most 68,096 triangle attempts. Their fixed records total at most 28,327,936 bytes. All tracer writes charge the existing shared 128 MiB sidecar budget. Complete case outputs remain bounded to 256 MiB. Native process and offline adapter each use actual 1 GiB address-space and 600-second CPU/wall limits. Builds remain two jobs, each step at most 900 seconds. No radius/threshold/resolution sweep, sample deletion, source-plan changes or retries to fit budgets. Overflow aborts the whole arm, never truncates.

Offline audit output is at most 16 MiB, checked before publication. Its work is bounded by all 34,048 cells / 68,096 attempts, 35,112 sample/accumulator rows and 105,336 witness records; no unbounded spatial search or candidate generation. Existing R3 rational clipping budgets are not exercised because no certificate/front executes. Every original oracle artifact must match its independently reviewed pinned hash, in addition to baseline/observer parity. Source/config/package/input identities are sealed before measurements. Any first failure is retained; no downstream admission on partial data.

Pre-run gate additionally requires independent dry application of every patch to the pinned archive and inspection of the resulting diff. The runner performs the same anchor/file-scope check and writes `patch-preflight.json` plus `observer-source.diff` before either build. The original failed anchor attempt is retained; this correction changes no native expression order or reconstruction policy.
